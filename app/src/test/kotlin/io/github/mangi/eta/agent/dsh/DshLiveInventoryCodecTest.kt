package io.github.mangi.eta.agent.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桥那行 JSON 的解析。
 *
 * 夹具照的是**真跑出来的那一份**：本机用 Windows 的 node 起了一次随包 dsh（不 chroot，
 * 覆盖层就是取清单用的那份），桥打了 98 条 entries、0 条重复、`hasPresets: false` ——
 * 头两条（根 include、以及被配置关掉的 `tool-plugin-manager`）与预设那几行都按真形状抄在这里。
 *
 * 为什么值得单独测：整个页面的底线是"探针出任何事都不许变成一片空白"。所以这里把每条
 * **必须失败**的路径都钉住：没有 marker、JSON 坏了、桥报错、桥超时、清单是空的。
 *
 * 纯 JVM：`org.json` 由 `testImplementation(libs.json)` 提供，不需要 Robolectric。
 */
class DshLiveInventoryCodecTest {

    private fun ready(stdout: String): DshLiveInventory.Ready {
        val parsed = DshLiveInventoryCodec.parse(stdout)
        assertTrue("期望 Ready，实际 $parsed", parsed is DshLiveInventory.Ready)
        return parsed as DshLiveInventory.Ready
    }

    private fun failed(stdout: String): DshLiveInventory.Failed {
        val parsed = DshLiveInventoryCodec.parse(stdout)
        assertTrue("期望 Failed，实际 $parsed", parsed is DshLiveInventory.Failed)
        return parsed as DshLiveInventory.Failed
    }

    @Test
    fun readsEntriesAndKeepsTheLoaderIdsVerbatim() {
        val ready = ready(
            """
            dsh: 一些自己的日志，桥不管它
            $MARKER_LINE
            """.trimIndent(),
        )

        assertEquals(3, ready.entries.size)
        val root = ready.entries[0]
        assertEquals("include", root.entryId)
        assertEquals("cordis:include", root.moduleName)
        assertTrue(root.enabled)
        assertEquals("active", root.fiberPhase)
        // 关掉的条目没有 fiber（没挂上去），phase 必须是 null 而不是某个猜出来的值。
        assertEquals("include:tool-plugin-manager", ready.entries[1].entryId)
        assertFalse(ready.entries[1].enabled)
        assertNull(ready.entries[1].fiberPhase)
        // Loader 的 id 带 include 前缀，**原样保留** —— 界面与补丁行的对应关系靠它。
        assertEquals("include:hmr", ready.entries[2].entryId)
        assertTrue(ready.presets.isEmpty())
    }

    @Test
    fun readsPresetsAndKeepsConditionalRowsConditional() {
        val ready = ready(
            """
            $MARKER_LINE_WITH_PRESETS
            """.trimIndent(),
        )

        assertEquals(1, ready.presets.size)
        val preset = ready.presets[0]
        assertEquals("standard", preset.id)
        assertEquals("标准版", preset.name)
        assertTrue(preset.isDefault)
        assertEquals(2, preset.rows.size)
        val plain = preset.rows[0]
        assertEquals("a", plain.entryId)
        assertEquals("@deepseek-ai/dsh-plugin-manager", plain.moduleName)
        assertEquals(true, plain.enabled)
        assertFalse(plain.conditional)
        assertNull(plain.condition)
        assertEquals("active", plain.fiberPhase)
        // 条件行：`enabled` 是字符串 `conditional`，不是布尔。两者都不能被归一成一个布尔。
        val conditional = preset.rows[1]
        assertEquals("b", conditional.entryId)
        assertNull(conditional.enabled)
        assertTrue(conditional.conditional)
        assertEquals("!ctx.get('x')", conditional.condition)
        assertNull(conditional.fiberPhase)
    }

    @Test
    fun missingMarkerBecomesAFailureInsteadOfAnException() {
        val failure = failed("dsh: 我起来了，但桥没打任何东西\n")

        assertTrue("原因里要能看出是 marker 没出现：${failure.reason}", failure.reason.contains("HETA-INVENTORY-JSON:"))
    }

    @Test
    fun brokenJsonBecomesAFailureInsteadOfAnException() {
        val failure = failed("HETA-INVENTORY-JSON:{\"entries\":[,,,\n")

        assertTrue("原因里要能看出是 JSON 的问题：${failure.reason}", failure.reason.contains("JSON"))
    }

    @Test
    fun bridgeReportedErrorBecomesAFailure() {
        val failure = failed("HETA-INVENTORY-JSON:{\"entries\":[],\"error\":\"loader 还没起来\"}\n")

        assertTrue("桥自己报的错要原样带上：${failure.reason}", failure.reason.contains("loader 还没起来"))
    }

    @Test
    fun timedOutSnapshotIsRefusedInsteadOfShownAsACompleteInventory() {
        // 超时那份快照可能少条目；按完整清单显示等于告诉用户"某个插件没挂上"，那是不成立的结论。
        val failure = failed("$MARKER_LINE".replace("\"timedOut\":false", "\"timedOut\":true"))

        assertTrue("原因里要能看出是超时：${failure.reason}", failure.reason.contains("超时"))
    }

    @Test
    fun emptyEntryListIsRefusedBecauseTheLoaderAlwaysHasARootEntry() {
        val failure = failed("HETA-INVENTORY-JSON:{\"entries\":[],\"hasPresets\":false,\"timedOut\":false}\n")

        assertTrue("原因里要能看出清单是空的：${failure.reason}", failure.reason.contains("空"))
    }

    @Test
    fun aMalformedEntryIsSkippedInsteadOfTakingTheWholePageDown() {
        val ready = ready(
            "HETA-INVENTORY-JSON:{\"entries\":[{\"moduleName\":\"没有 id\"}," +
                "{\"entryId\":\"include:ok\",\"moduleName\":\"@deepseek-ai/dsh-ok\"," +
                "\"enabled\":true,\"fiberPhase\":\"active\"}],\"timedOut\":false}\n",
        )

        assertEquals(1, ready.entries.size)
        assertEquals("include:ok", ready.entries[0].entryId)
    }

    @Test
    fun unknownFiberPhaseIsPassedThroughVerbatim() {
        // dsh 以后新增一个状态时，界面显示原文也好过我们替它编一个中文名。
        val ready = ready(
            "HETA-INVENTORY-JSON:{\"entries\":[{\"entryId\":\"a\",\"moduleName\":\"m\"," +
                "\"enabled\":true,\"fiberPhase\":\"reloading\"}],\"timedOut\":false}\n",
        )

        assertEquals("reloading", ready.entries[0].fiberPhase)
    }

    private companion object {

        const val MARKER_LINE = "HETA-INVENTORY-JSON:{\"entries\":[" +
            "{\"entryId\":\"include\",\"moduleName\":\"cordis:include\",\"enabled\":true,\"fiberPhase\":\"active\"}," +
            "{\"entryId\":\"include:tool-plugin-manager\",\"moduleName\":\"@deepseek-ai/dsh-plugin-manager/tools\"," +
            "\"enabled\":false,\"fiberPhase\":null}," +
            "{\"entryId\":\"include:hmr\",\"moduleName\":\"@deepseek-ai/dsh-hmr\",\"enabled\":true," +
            "\"fiberPhase\":\"active\"}],\"hasPresets\":false,\"timedOut\":false}"

        const val MARKER_LINE_WITH_PRESETS = "HETA-INVENTORY-JSON:{\"entries\":[" +
            "{\"entryId\":\"include\",\"moduleName\":\"cordis:include\",\"enabled\":true,\"fiberPhase\":\"active\"}]," +
            "\"agentPresets\":[{\"id\":\"standard\",\"name\":\"标准版\",\"isDefault\":true,\"rows\":[" +
            "{\"entryId\":\"a\",\"moduleName\":\"@deepseek-ai/dsh-plugin-manager\",\"enabled\":true,\"fiberPhase\":\"active\"}," +
            "{\"entryId\":\"b\",\"moduleName\":\"@deepseek-ai/dsh-hmr\",\"enabled\":\"conditional\"," +
            "\"condition\":\"!ctx.get('x')\",\"fiberPhase\":null}]}],\"hasPresets\":true,\"timedOut\":false}"
    }
}
