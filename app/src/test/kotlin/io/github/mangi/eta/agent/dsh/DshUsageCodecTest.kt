package io.github.mangi.eta.agent.dsh

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话插件那份用量文件的解析（`HETA-USAGE-JSON:` + JSON）。
 *
 * 为什么值得单独测：这份数据是**显示给用户看的账**，比清单更容易"看起来对但其实错"——
 *   · 一轮里各步要**累加**（每一步的 inputTokens 都是那一步整段请求，取最后一步就少算）；
 *   · `steps` 取的是步号（max），不是累加次数；
 *   · 缺字段是"没有这个数"（null），不是 0 —— 拿 0 冒充会让用户以为这一轮没花缓存。
 * 所以夹具照真形状（`@deepseek-ai/dsh-llm` 的 TokenUsage）抄，逐项钉住。
 *
 * 纯 JVM：`org.json` 由 `testImplementation(libs.json)` 提供（同 [DshLiveInventoryCodecTest]）。
 */
class DshUsageCodecTest {

    private val body = DshUsageCodec.MARKER +
        "{\"at\":1790000000000,\"sessions\":[{\"id\":\"s-1\",\"at\":1790000000000,\"turns\":[" +
        "{\"turn\":1,\"steps\":2,\"inputTokens\":30000,\"outputTokens\":900," +
        "\"cacheReadTokens\":24000,\"cacheWriteTokens\":1200,\"reasoningTokens\":300," +
        "\"totalTokens\":30900,\"at\":1790000000000}," +
        "{\"turn\":2,\"steps\":1,\"inputTokens\":31000,\"outputTokens\":500," +
        "\"cacheReadTokens\":30000,\"at\":1790000001000}]}]}\n"

    @Test
    fun readsEveryTurnOfEverySession() {
        val snapshot = DshUsageCodec.parse(body)
        assertTrue("期望解析成功", snapshot != null)
        assertEquals(1790000000000L, snapshot!!.at)
        assertEquals(1, snapshot.sessions.size)

        val session = snapshot.sessions[0]
        assertEquals("s-1", session.id)
        assertEquals(2, session.turns.size)

        val first = session.turns[0]
        assertEquals(1, first.turn)
        assertEquals(2, first.steps)
        assertEquals(30000, first.inputTokens)
        assertEquals(900, first.outputTokens)
        assertEquals(24000, first.cacheReadTokens)
        assertEquals(1200, first.cacheWriteTokens)
        assertEquals(300, first.reasoningTokens)
        assertEquals(30900, first.totalTokens)

        // 缺的字段是 null，不是 0：界面上"没报"与"报了 0"是两件事。
        val second = session.turns[1]
        assertNull(second.cacheWriteTokens)
        assertNull(second.reasoningTokens)
        assertNull(second.totalTokens)
        assertEquals(30000, second.cacheReadTokens)
    }

    @Test
    fun latestTurnIsTheHighestTurnNotTheLastWritten() {
        val snapshot = DshUsageCodec.parse(body)!!
        assertEquals(2, snapshot.sessions[0].latestTurn?.turn)
    }

    @Test
    fun brokenRowsAreDroppedInsteadOfKillingTheWholeSnapshot() {
        // 一条畸形轮次（没有 turn）只丢它自己；没有 id 的会话整条丢。
        val text = DshUsageCodec.MARKER +
            "{\"sessions\":[{\"id\":\"s-1\",\"turns\":[{\"steps\":1},{\"turn\":3,\"steps\":1}]}," +
            "{\"turns\":[{\"turn\":1}]}]}"
        val snapshot = DshUsageCodec.parse(text)!!

        assertEquals(1, snapshot.sessions.size)
        assertEquals(listOf(3), snapshot.sessions[0].turns.map { it.turn })
    }

    @Test
    fun missingOrCorruptTextIsNullInsteadOfThrowing() {
        assertNull(DshUsageCodec.parse("没有 marker 的一段话"))
        assertNull(DshUsageCodec.parse(DshUsageCodec.MARKER + "{这不是 JSON"))
        // 空快照（插件刚挂上、还没有任何轮次）解析得出来，只是没有会话。
        val empty = DshUsageCodec.parse(DshUsageCodec.MARKER + "{\"at\":1,\"sessions\":[]}")
        assertEquals(0, empty!!.sessions.size)
    }

    @Test
    fun readerPicksTheSessionItWasAskedForAndFallsBackToTheNewest() {
        val directory = Files.createTempDirectory("eta-usage").toFile()
        val file = java.io.File(directory, DshUsageReader.SHARED_RELATIVE)
        file.parentFile?.mkdirs()
        file.writeText(body)

        // 按会话 id 挑：挑得到就用它。
        assertEquals(2, DshUsageReader.latestTurn(directory.absolutePath, "s-1")?.turn)
        // id 对不上（插件刚写、会话刚建）时退回最近活跃的那个会话，而不是整轮没有用量。
        assertEquals(2, DshUsageReader.latestTurn(directory.absolutePath, "s-unknown")?.turn)
        // 文件不在：null（界面少一行，不撒谎）。
        assertNull(DshUsageReader.latestTurn(directory.absolutePath + "/nope", "s-1"))

        // 时间闸门：只认本回合开始之后写过的轮次 —— 上一轮那份（at 更早）必须被挡掉，
        // 否则"一轮一进程"下读到的就是上一轮的账。
        assertEquals(
            2,
            DshUsageReader.latestTurn(directory.absolutePath, "s-1", notBefore = 1790000000500L)?.turn,
        )
        assertNull(DshUsageReader.latestTurn(directory.absolutePath, "s-1", notBefore = 1790000002000L))

        directory.deleteRecursively()
    }
}
