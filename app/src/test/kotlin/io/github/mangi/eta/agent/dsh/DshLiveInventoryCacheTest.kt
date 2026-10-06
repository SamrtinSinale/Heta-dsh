package io.github.mangi.eta.agent.dsh

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活清单缓存（进程级 + 落盘）。
 *
 * 为什么值得测：这个缓存决定"点开扩展页要不要等 27 秒"。四件事必须钉住 ——
 *   1. 成功的那份要**落盘**、重启后 [DshLiveInventoryCache.restore] 读得回来（这是"重启后第一次
 *      进页面不再干等"的全部依据）；
 *   2. 时间戳取原文里的 `at`（**不是**"现在"）：否则一份三天前的缓存会显示成刚更新过，永远不重探；
 *   3. [DshLiveInventoryCache.loadOnce] 的 `force`：有新鲜缓存时不许重复起 dsh，而顶部刷新按钮
 *      （`force = true`）必须真探一次；
 *   4. 探针失败**不许**顶掉一份好的缓存。
 *
 * 纯 JVM：落点是 `Files.createTempDirectory` 出来的普通目录（这个对象刻意不碰 Android 的 Context）。
 */
class DshLiveInventoryCacheTest {

    private val directory = Files.createTempDirectory("eta-live-inventory").toFile()

    @After
    fun tearDown() {
        DshLiveInventoryCache.reset()
        directory.deleteRecursively()
    }

    /** 与 `heta-status.mjs` 写出来的那份同一个形状：marker + JSON（多一个 `at`）。 */
    private fun body(at: Long): String =
        "HETA-INVENTORY-JSON:{\"at\":$at,\"entries\":[{\"entryId\":\"include\"," +
            "\"moduleName\":\"cordis:include\",\"enabled\":true,\"fiberPhase\":\"active\"}]," +
            "\"hasPresets\":false,\"timedOut\":false}\n"

    private fun parsed(at: Long): DshLiveInventory.Ready =
        DshLiveInventoryCodec.parse(body(at)) as DshLiveInventory.Ready

    private fun storedFile(): File = File(directory, DshLiveInventoryCache.FILE_NAME)

    @Test
    fun successfulProbeSurvivesAnAppRestart() {
        DshLiveInventoryCache.attach(directory)
        runBlocking { DshLiveInventoryCache.loadOnce { parsed(System.currentTimeMillis()) } }

        // 模拟一次 App 重启：进程级状态清干净，只留磁盘上那份。
        DshLiveInventoryCache.reset()
        DshLiveInventoryCache.attach(directory)
        DshLiveInventoryCache.restore()

        val restored = DshLiveInventoryCache.get()
        assertNotNull("落盘那份没读回来", restored)
        assertEquals("include", restored!!.entries[0].entryId)
        assertFalse("刚写下去的缓存不该算过期", DshLiveInventoryCache.isStale())
    }

    @Test
    fun restoredSnapshotKeepsTheTimeItWasCaptured() {
        // `at` 是三天前：读回来必须**算过期** —— 否则页面上会一直是三天前的运行状态，永远不去重探。
        val old = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000
        DshLiveInventoryCache.attach(directory)
        storedFile().writeText(body(old))

        DshLiveInventoryCache.restore()

        assertNotNull(DshLiveInventoryCache.get())
        assertTrue("三天前的那份必须算过期", DshLiveInventoryCache.isStale())
    }

    @Test
    fun freshCacheSkipsTheProbeButTheRefreshButtonStillForcesOne() {
        DshLiveInventoryCache.attach(directory)
        var probes = 0
        val fresh = parsed(System.currentTimeMillis())
        runBlocking { DshLiveInventoryCache.loadOnce { probes++; fresh } }
        assertEquals(1, probes)

        // 有缓存而且新鲜：不探（页面进进出出不该每次起一个 dsh）。
        runBlocking { DshLiveInventoryCache.loadOnce { probes++; fresh } }
        assertEquals(1, probes)

        // 顶部那个刷新按钮：force = true，必须真探一次。
        runBlocking { DshLiveInventoryCache.loadOnce(force = true) { probes++; fresh } }
        assertEquals(2, probes)
    }

    @Test
    fun failedProbeNeverReplacesTheGoodSnapshot() {
        DshLiveInventoryCache.attach(directory)
        val good = parsed(System.currentTimeMillis())
        DshLiveInventoryCache.put(good)

        runBlocking {
            DshLiveInventoryCache.loadOnce(force = true) { DshLiveInventory.Failed("探针没跑起来") }
        }

        assertEquals(good, DshLiveInventoryCache.get())
        // 落盘那份也得还是好的：一次失败不该把缓存文件写坏。
        assertTrue(storedFile().readText().contains("\"include\""))
    }

    @Test
    fun unreadableDiskSnapshotIsIgnoredInsteadOfCrashing() {
        DshLiveInventoryCache.attach(directory)
        storedFile().writeText("{这不是 JSON，也没有 marker")

        DshLiveInventoryCache.restore()

        assertNull(DshLiveInventoryCache.get())
        assertFalse(DshLiveInventoryCache.isStale())
    }

    @Test
    fun withoutAttachNothingIsPersisted() {
        DshLiveInventoryCache.reset()
        runBlocking { DshLiveInventoryCache.loadOnce { parsed(System.currentTimeMillis()) } }

        assertNotNull(DshLiveInventoryCache.get())
        assertTrue("没 attach 就不该往磁盘上写", directory.listFiles().isNullOrEmpty())
    }
}
