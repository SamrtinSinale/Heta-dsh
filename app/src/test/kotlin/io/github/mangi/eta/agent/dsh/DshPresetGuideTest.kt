package io.github.mangi.eta.agent.dsh

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 官方那份预设文案的资产自检（`assets/heta-presets/guide.json`）。
 *
 * 为什么值得钉：预设的名字、说明、"这个预设能干什么"和示例用法现在**只有这一个来源** ——
 * 从 `@deepseek-ai/dsh-client-ui-agent-preset` 逐字抽出来的官方客户端文案（真机反馈原话：
 * "客户端可以看提醒，点开有示例用法，和客户端保持一致"）。资产被改坏、少一份预设、少一个语种，
 * 界面上就是一片空白或英文，所以这里按"四份预设 × 两个语种 × 三段正文"逐格检查。
 *
 * 语种只有两份是有意的：官方客户端也只发布 `en` 与简体中文（zh-Hant 由读取侧回落到简体）。
 */
class DshPresetGuideTest {
    private fun asset(path: String): String {
        val base = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        return File(base, path).readText()
    }

    private fun root(): JSONObject = JSONObject(asset("heta-presets/guide.json"))

    @Test
    fun everyBuiltInPresetCarriesIntroExplanationAndUsageInBothLocales() {
        val presets = root().getJSONObject("presets")
        DshPresetPlane.PRESET_IDS.forEach { id ->
            val entry = presets.optJSONObject(id) ?: error("资产里没有预设 $id 的说明")
            listOf("zh-Hans", "en").forEach { locale ->
                val block = entry.optJSONObject(locale) ?: error("$id 缺 $locale")
                listOf("intro", "explanation", "usage").forEach { key ->
                    assertTrue("$id/$locale 的 $key 是空的", block.optString(key).isNotBlank())
                }
            }
        }
        assertEquals(
            "资产里的预设条数与官方四份声明不一致",
            DshPresetPlane.PRESET_IDS.size,
            presets.length(),
        )
    }

    @Test
    fun namesAndDescriptionsMatchTheOfficialClientCopy() {
        val names = root().getJSONObject("names").getJSONObject("zh-Hans")
        assertEquals("标准模式", names.getJSONObject("standard").getString("name"))
        assertEquals("PTC 模式", names.getJSONObject("ptc").getString("name"))
        assertEquals("极简模式", names.getJSONObject("minimal").getString("name"))
        assertEquals("创造模式", names.getJSONObject("cordis").getString("name"))
        // 说明是整句的官方原文（抽查关键词：少抄一句就红）。
        assertTrue(
            "标准模式的说明不像官方原文",
            names.getJSONObject("standard").getString("description").contains("检索、编辑和终端"),
        )
        assertTrue(
            "创造模式的说明不像官方原文",
            names.getJSONObject("cordis").getString("description").contains("插件"),
        )
    }

    @Test
    fun guideHeadingsAndUsageExamplesArePresent() {
        val labels = root().getJSONObject("labels").getJSONObject("zh-Hans")
        assertEquals("模式说明", labels.getString("modeExplanation"))
        assertEquals("如何使用", labels.getString("howToUse"))
        assertEquals("示例任务", labels.getString("guideExampleTask"))
        val usage = root().getJSONObject("presets").getJSONObject("standard")
            .getJSONObject("zh-Hans").getString("usage")
        // 示例用法那几段是 Markdown（`### 标题` + `> 提示词` + "预期产出"），界面直接交给渲染器。
        assertTrue("示例用法里没有提示词引用", usage.contains("> "))
        assertTrue("示例用法里没有预期产出", usage.contains("预期产出"))
    }
}
