package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.dsh.DshPresetGuides
import io.github.mangi.eta.agent.dsh.DshPresetPlane
import io.github.mangi.eta.ui.markdown.MarkdownTone
import io.github.mangi.eta.ui.markdown.StaticMarkdown
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.layout.DialogDefaults

/**
 * 四个官方预设的单选列表 + 每行一个「说明」按钮。
 *
 * 名字与说明都来自**官方客户端那份文案**（[DshPresetGuides] 读 `assets/heta-presets/guide.json`，
 * 由 `gen_preset_guide.py` 从 `@deepseek-ai/dsh-client-ui-agent-preset` 逐字抽出）—— 包括
 * `presetStandardName` / `presetStandardDescription` 这些键。查不到（自定义预设）才退回 roster
 * 给的名字，那正是官方 "a declaration that names itself owns its copy" 的用法。
 *
 * 那个 ⓘ 就是真机反馈里说的"客户端可以看提醒，点开有示例用法"：点开是
 * [DshPresetGuideDialog]，里面是官方的模式说明与示例任务。
 */
@Composable
internal fun DshPresetChooser(
    selectedId: String,
    onSelected: (String) -> Unit,
    onGuide: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    Column(modifier = modifier.fillMaxWidth()) {
        DshPresetPlane.PRESET_IDS.forEach { id ->
            DshPresetRow(
                title = DshPresetGuides.name(context, id, locale) ?: id,
                summary = DshPresetGuides.description(context, id, locale),
                selected = id == selectedId,
                onSelect = { onSelected(id) },
                onGuide = { onGuide(id) },
            )
        }
    }
}

@Composable
private fun DshPresetRow(
    title: String,
    summary: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    onGuide: (() -> Unit)?,
) {
    EtaPreferenceRow(
        title = title,
        summary = summary,
        interaction = Modifier.selectable(
            selected = selected,
            role = Role.RadioButton,
            onClick = onSelect,
        ),
        endActions = {
            if (onGuide != null) {
                IconButton(
                    onClick = onGuide,
                    minWidth = 36.dp,
                    minHeight = 36.dp,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = stringResource(R.string.chat_preset_guide_action, title),
                        modifier = Modifier.size(20.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
            }
            RadioButton(
                selected = selected,
                onClick = null,
                modifier = Modifier.clearAndSetSemantics {},
            )
        },
    )
}

/**
 * 预设说明弹层：官方的「模式说明」与「示例任务」（示例用法）原文。
 *
 * 正文里有 Markdown（`### 标题`、`> 提示词`），所以直接用聊天那套渲染器 —— 原样当纯文本画出来
 * 会满屏 `###`。
 */
@Composable
internal fun DshPresetGuideDialog(
    presetId: String?,
    onDismiss: () -> Unit,
) {
    if (presetId == null) return
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val guide = DshPresetGuides.guide(context, presetId, locale) ?: return
    val labels = DshPresetGuides.labels(context, locale)
    WindowDialog(
        show = true,
        cornerRadius = DialogDefaults.CornerRadius,
        title = guide.name,
        summary = guide.description.takeIf { it.isNotBlank() },
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (guide.intro.isNotBlank()) {
                Text(
                    text = guide.intro,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (guide.explanation.isNotBlank()) {
                DshGuideSection(title = labels.modeExplanation, body = guide.explanation)
            }
            if (guide.usage.isNotBlank()) {
                DshGuideSection(title = labels.exampleTask, body = guide.usage)
            }
            EtaTextButton(
                text = stringResource(R.string.action_confirm),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun DshGuideSection(title: String, body: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
        StaticMarkdown(
            content = body,
            tone = MarkdownTone.Answer,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
