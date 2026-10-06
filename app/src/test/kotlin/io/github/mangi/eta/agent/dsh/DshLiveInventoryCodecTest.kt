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

    @Test
    fun rowKeyStripsTheIncludePrefixAndFallsBackToTheModuleName() {
        // 真形状：Loader 的 id 带 `include:`，预设组合行里那条不一定带 —— 剥掉前缀才比得上。
        assertEquals("tool-plugin-manager", dshLiveRowKey("include:tool-plugin-manager", null))
        assertEquals("tool-plugin-manager", dshLiveRowKey("TOOL-PLUGIN-MANAGER", null))
        // 没有 id（或只有空白）才退回模块名；模块名也统一小写。
        assertEquals("m", dshLiveRowKey(null, "M"))
        assertEquals("m", dshLiveRowKey("   ", "m"))
        assertEquals("", dshLiveRowKey(null, null))
    }

    @Test
    fun presetProvidersIndexMatchesGlobalRowsAcrossTheIncludePrefix() {
        val presets = listOf(
            preset(id = "standard", rows = listOf(row(entryId = "tool-plugin-manager"))),
            preset(
                id = "ptc",
                rows = listOf(
                    // 同一个预设重复给同一行：只记一次。
                    row(entryId = "include:tool-plugin-manager"),
                    row(entryId = "include:tool-plugin-manager"),
                ),
            ),
        )

        val providers = dshLivePresetProviders(presets)
        val globalKey = dshLiveRowKey("include:tool-plugin-manager", MODULE_NAME)

        // 全局清单那条 id 带前缀、预设那条不带 —— 两边都要落到同一个键上。
        assertEquals(listOf("standard", "ptc"), providers[globalKey])
        // 没有 id 的行退回模块名，同样对得上。
        val byModuleName = dshLivePresetProviders(
            listOf(preset(id = "standard", rows = listOf(row(entryId = null)))),
        )
        assertEquals(listOf("standard"), byModuleName[dshLiveRowKey(null, MODULE_NAME)])
    }

    private fun preset(id: String, rows: List<DshLivePresetRow>) = DshLivePreset(
        id = id,
        name = id,
        isDefault = false,
        broken = null,
        rows = rows,
    )

    private fun row(entryId: String?) = DshLivePresetRow(
        entryId = entryId,
        moduleName = MODULE_NAME,
        enabled = true,
        conditional = false,
        condition = null,
        fiberPhase = "active",
        patchId = null,
    )

    @Test
    fun readsTheLocalizedMetaTitleAndDescription() {
        // 官方的 `meta`：title / description 各是"字面串或 locale → 文案"（LocalizedText）。
        val ready = ready(
            "HETA-INVENTORY-JSON:{\"entries\":[{\"entryId\":\"include:typert-loader\"," +
                "\"moduleName\":\"@deepseek-ai/dsh-typert-loader\",\"enabled\":true,\"fiberPhase\":\"active\"," +
                "\"meta\":{\"title\":{\"en\":\"typert-loader\",\"zh-Hans\":\"Typert 载入器\"}," +
                "\"description\":\"Loader integration for generated Typert package contributions\"}}]," +
                "\"timedOut\":false}\n",
        )

        val entry = ready.entries[0]
        assertEquals("typert-loader", entry.title?.resolve(listOf("en")))
        // 按偏好顺序取：zh-Hans 有就用它。
        assertEquals("Typert 载入器", entry.title?.resolve(listOf("zh-Hans", "en")))
        // 繁体没有单独一份时退到 zh-Hans（官方字典里也只有 zh-Hans 与 en）。
        assertEquals("Typert 载入器", entry.title?.resolve(listOf("zh-Hant", "zh-Hans", "en")))
        assertEquals(
            "Loader integration for generated Typert package contributions",
            entry.description?.resolve(listOf("en")),
        )
    }

    @Test
    fun missingMetaStaysMissingInsteadOfInventingATitle() {
        val ready = ready(
            "HETA-INVENTORY-JSON:{\"entries\":[{\"entryId\":\"a\",\"moduleName\":\"m\"," +
                "\"enabled\":true,\"fiberPhase\":\"active\"}],\"timedOut\":false}\n",
        )

        // 没有 meta 就是没有 —— 界面退回短包名，不在这里替它编一个标题。
        assertNull(ready.entries[0].title)
        assertNull(ready.entries[0].description)
    }

    @Test
    fun shortNameFollowsTheOfficialClientRules() {
        // 逐字照官方 client.js 那段：去 npm scope，再去 cordis: / cordis-plugin- / dsh-（含 host/client）。
        assertEquals("typert-loader", dshModuleShortName("@deepseek-ai/dsh-typert-loader"))
        assertEquals("plugin-manager/tools", dshModuleShortName("@deepseek-ai/dsh-plugin-manager/tools"))
        assertEquals("include", dshModuleShortName("cordis:include"))
        assertEquals("hmr", dshModuleShortName("cordis-plugin-hmr"))
        assertEquals("plugin-manager", dshModuleShortName("dsh-host-plugin-manager"))
        assertEquals("plain", dshModuleShortName("plain"))
    }

    private companion object {

        const val MODULE_NAME = "@deepseek-ai/dsh-plugin-manager/tools"

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
