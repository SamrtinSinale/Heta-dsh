package io.github.mangi.eta.agent.dsh

import android.content.Context
import java.util.Locale
import org.json.JSONObject

/**
 * 官方客户端那份**预设说明**：模式说明、如何使用、示例用法（含"预期产出"那几句）。
 *
 * 内容不是我们写的，是 `@deepseek-ai/dsh-client-ui-agent-preset`（0.2.0-rc.2）里那份客户端文案
 * **逐字抽出来**的资产（`assets/heta-presets/guide.json`，由 `_dsh_store/gen_preset_guide.py`
 * 生成）。为什么要照抄：真机反馈原话是"客户端可以看提醒，点开有示例用法，和客户端保持一致" ——
 * 自己编一套说明就再也说不清"一致"了。资产里同时带着名字与说明（客户端那两个
 * `presetStandardName/Description` 键），所以预设的名字/说明也只有这一个来源。
 *
 * 语种：官方只有 `en` 与简体中文两份（没有繁体），所以 zh-Hant 回落到 zh-Hans —— 与"客户端一致"
 * 的边界就在这里，不自己造一份繁体。
 *
 * 读取很便宜（14 KB 的 JSON，进程内缓存一次），所以界面可以随手取。
 */
internal data class DshPresetGuide(
    val id: String,
    val name: String,
    val description: String,
    val intro: String,
    val explanation: String,
    val usage: String,
) {
    /** 弹层里那三个小标题（同样是官方原文）。 */
    data class Labels(
        val modeExplanation: String,
        val howToUse: String,
        val exampleTask: String,
    )
}

internal object DshPresetGuides {
    private const val ASSET = "heta-presets/guide.json"

    /** 进程内缓存：资产是只读的，界面每次开弹层都读一遍没必要。 */
    private var cached: JSONObject? = null

    fun guide(context: Context, presetId: String, locale: Locale): DshPresetGuide? {
        val root = read(context) ?: return null
        val key = localeKey(locale)
        val names = root.optJSONObject("names")?.optJSONObject(key)?.optJSONObject(presetId)
        val entry = root.optJSONObject("presets")?.optJSONObject(presetId)?.optJSONObject(key)
        if (names == null || entry == null) return null
        return DshPresetGuide(
            id = presetId,
            name = names.optString("name").ifBlank { presetId },
            description = names.optString("description"),
            intro = entry.optString("intro"),
            explanation = entry.optString("explanation"),
            usage = entry.optString("usage"),
        )
    }

    /** 预设名字（官方文案）；资产里没有这个 id（自定义预设）返回 null，调用方退回 roster 给的名字。 */
    fun name(context: Context, presetId: String, locale: Locale): String? =
        guide(context, presetId, locale)?.name?.takeIf { it.isNotBlank() }

    /** 预设说明（官方文案）。 */
    fun description(context: Context, presetId: String, locale: Locale): String? =
        guide(context, presetId, locale)?.description?.takeIf { it.isNotBlank() }

    fun labels(context: Context, locale: Locale): DshPresetGuide.Labels {
        val fallback = DshPresetGuide.Labels("模式说明", "如何使用", "示例任务")
        val entry = read(context)?.optJSONObject("labels")?.optJSONObject(localeKey(locale))
            ?: return fallback
        return DshPresetGuide.Labels(
            modeExplanation = entry.optString("modeExplanation").ifBlank { fallback.modeExplanation },
            howToUse = entry.optString("howToUse").ifBlank { fallback.howToUse },
            exampleTask = entry.optString("guideExampleTask").ifBlank { fallback.exampleTask },
        )
    }

    /** 官方只有 en 与 zh-Hans：中文一律用简体那份（繁体不自己造）。 */
    private fun localeKey(locale: Locale): String = if (locale.language == "zh") "zh-Hans" else "en"

    private fun read(context: Context): JSONObject? {
        cached?.let { return it }
        val parsed = runCatching {
            context.assets.open(ASSET).use { input ->
                JSONObject(input.readBytes().decodeToString())
            }
        }.getOrNull()
        cached = parsed
        return parsed
    }
}
