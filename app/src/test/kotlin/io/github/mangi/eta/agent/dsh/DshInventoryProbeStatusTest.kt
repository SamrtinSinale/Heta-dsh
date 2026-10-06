package io.github.mangi.eta.agent.dsh

import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态文件那条路的判据（[DshInventoryProbe.sharedMissReason]）。
 *
 * 为什么值得测：真机上"为什么没用上快的那条路"只能靠这一串字回答 —— 3.0.8.14 的真机反馈就是
 * `[home-file]`：只知道走了慢的那条，不知道为什么。所以每个分支都钉住，界面显示的就是它。
 */
class DshInventoryProbeStatusTest {

    private val now = System.currentTimeMillis()

    private fun body(at: Long): String =
        "HETA-INVENTORY-JSON:{\"at\":$at,\"entries\":[],\"hasPresets\":false,\"timedOut\":false}"

    @Test
    fun missingFileSaysSo() {
        assertEquals("shared-missing", DshInventoryProbe.sharedMissReason(null, null))
        assertEquals("shared-missing", DshInventoryProbe.sharedMissReason("   ", null))
    }

    @Test
    fun permissionFailureCarriesTheRealMessage() {
        val reason = DshInventoryProbe.sharedMissReason(
            null,
            FileNotFoundException("/x/opt/dsh/heta-inventory.json: open failed: EACCES (Permission denied)"),
        )

        assertTrue("要能看出是读不动、以及为什么：$reason", reason.startsWith("shared-failed:"))
        assertTrue(reason.contains("EACCES"))
    }

    @Test
    fun freshButUnparsableIsDistinguishedFromStale() {
        // 在、也新鲜，但解析不出清单（写了一半 / 被人改坏）：与"太旧"是两回事，界面上要分得开。
        assertEquals("shared-broken", DshInventoryProbe.sharedMissReason(body(now), null))
        assertEquals(
            "shared-stale",
            DshInventoryProbe.sharedMissReason(body(now - 11 * 60 * 1000L), null),
        )
        assertEquals("shared-nomarker", DshInventoryProbe.sharedMissReason("一段没有 marker 的文本", null))
    }
}
