package io.github.mangi.eta.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
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
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 预设相关的两个弹层**共用同一套尺寸**：一样宽、一样高。
 *
 * 为什么：真机反馈原话 ——"你预设弹窗都做的大小一样才对啊，你一个窄高一个宽矮几个意思，预设弹窗
 * 就和那个提醒弹窗一样宽一样高就行了"。所以宽度上限与内容区高度各只有一处声明，两个弹层都用它；
 * 内容区是**固定**高度，不是 `heightIn`：切页签（模式说明 / 如何使用）时不能跳高度。
 *
 * 宽度上限在手机上会被屏幕宽度截住 —— 两个弹层同样被截，所以"一样宽"在真机上照样成立。
 */
internal val PresetDialogMaxWidth = 420.dp
internal val PresetDialogContentHeight = 300.dp

/**
 * 一个预设行：`名字 ⓘ …………………… ○`。
 *
 * 说明按钮（那个感叹号）紧跟在**名字后面**，不与行尾的单选圈挤在一起 —— 真机反馈原话：
 * "那个选择预设的对号和感叹弹窗位置不是很好，应该放在标准模式字后面不是在对号旁边"。
 * `EtaRadioButtonPreference` 会把两者都放在行尾，所以这里用 `EtaPreferenceRow` 的
 * `titleContent` 自己排（那个槽位一旦给了，标题与说明都得自己画 —— 同 `DshPluginSwitchRow`）。
 *
 * 名字与说明由调用方给：内置四份来自官方那份资产（[DshPresetGuides]），扩展页那份来自 roster
 *（自定义预设只有 roster 认识）。
 */
@Composable
internal fun DshPresetRow(
    name: String,
    description: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    onGuide: (() -> Unit)?,
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // 按下态自己画：`EtaPreferenceRow` 的按下反馈来自主题的 indication，画出来是铺满整行的矩形
    //（真机反馈"点击按下的色块还是矩形色块"）。所以这里 `indication = null`，自己按状态给色块。
    //
    // 形状按它在**这一组里的位置**给 —— 真机反馈原话："不要四个模式都是圆角矩形，改成整体模式
    // 切换是圆角矩形，中间两个不要圆角，标准模式上面两个角是 R 角，创造模式底部两个是 R 角"。
    // 半径用分组卡片的那个（[EtaCardDefaults.CornerRadius]）：扩展页那四行本来就套在
    // `EtaPreferenceGroupItem` 里，色块的角要和卡片的角对齐。
    val interactionSource = remember { MutableInteractionSource() }
    // 轻点也要看得见，且要渐变（同 3.0.7.23 的观感）：见 [rememberPressHighlight]。
    val highlight = rememberPressHighlight(interactionSource)
    val corner = EtaCardDefaults.CornerRadius
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .squircleSurface(
                // 灰色按下块：`surfaceContainerHigh` 在弹层底色（`surfaceContainer`）上几乎看不出
                // 差别，真机反馈"你怎么把灰色矩形色块给写没了"—— 改用 onSurface 8% 的灰，看得见。
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f * highlight),
                topStart = if (isFirst) corner else 0.dp,
                topEnd = if (isFirst) corner else 0.dp,
                bottomStart = if (isLast) corner else 0.dp,
                bottomEnd = if (isLast) corner else 0.dp,
            ),
    ) {
        EtaPreferenceRow(
            title = null,
            modifier = modifier,
            enabled = enabled,
            interaction = Modifier.selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                interactionSource = interactionSource,
                indication = null,
                onClick = onSelect,
            ),
            titleContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Medium,
                        color = if (enabled) {
                            MiuixTheme.colorScheme.onBackground
                        } else {
                            MiuixTheme.colorScheme.disabledOnSurface
                        },
                    )
                    if (onGuide != null) {
                        IconButton(
                            onClick = onGuide,
                            minWidth = 32.dp,
                            minHeight = 32.dp,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = stringResource(R.string.chat_preset_guide_action, name),
                                modifier = Modifier.size(18.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        }
                    }
                }
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        style = MiuixTheme.textStyles.body2,
                        color = if (enabled) {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        } else {
                            MiuixTheme.colorScheme.disabledOnSurface
                        },
                    )
                }
            },
            endActions = {
                RadioButton(
                    selected = selected,
                    onClick = null,
                    enabled = enabled,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            },
        )
    }
}

/** 内置四份预设的单选列表（放在 [PresetDialogContentHeight] 那个固定高度里）。 */
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
        val ids = DshPresetPlane.PRESET_IDS
        ids.forEachIndexed { index, id ->
            DshPresetRow(
                name = DshPresetGuides.name(context, id, locale) ?: id,
                description = DshPresetGuides.description(context, id, locale),
                selected = id == selectedId,
                onSelect = { onSelected(id) },
                onGuide = { onGuide(id) },
                isFirst = index == 0,
                isLast = index == ids.lastIndex,
            )
        }
    }
}

/**
 * 预设说明弹层：官方那份「模式说明」与「如何使用」，**左右两个页签切换**。
 *
 * 页签与内容都按 [PresetDialogMaxWidth] / [PresetDialogContentHeight] 固定 —— 切页签不跳尺寸
 *（真机反馈："如何使用和模式说明也应该固定宽高"）。正文里的 Markdown（`### 标题`、`> 提示词`）
 * 交给聊天那套渲染器，原样当纯文本画会满屏 `###`。
 *
 * 底部**没有确认按钮**（真机反馈："点开预设提醒弹窗底部还有个确认，这是bug"）—— 它只是一份说明。
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
    var selectedTab by remember(presetId) { mutableIntStateOf(0) }
    val tabs = listOf(labels.modeExplanation, labels.howToUse)
    val bodies = listOf(guide.explanation, guide.usage)
    WindowDialog(
        show = true,
        cornerRadius = DialogDefaults.CornerRadius,
        title = guide.name,
        summary = guide.intro.takeIf { it.isNotBlank() }
            ?: guide.description.takeIf { it.isNotBlank() },
        maxWidth = PresetDialogMaxWidth,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DshGuideTabs(
                labels = tabs,
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it },
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PresetDialogContentHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 12.dp),
            ) {
                StaticMarkdown(
                    content = bodies.getOrElse(selectedTab) { "" },
                    tone = MarkdownTone.Answer,
                )
            }
            EtaTextButton(
                text = stringResource(R.string.chat_preset_guide_close),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}

/** 说明弹层顶部那个横框：两个选项左右排，点哪个切哪个（选中态由 `selectable` 说出来）。 */
@Composable
private fun DshGuideTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .squircleSurface(
                color = MiuixTheme.colorScheme.surfaceContainerHigh,
                cornerRadius = 12.dp,
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .then(
                        if (selected) {
                            Modifier.squircleSurface(
                                color = MiuixTheme.colorScheme.surface,
                                cornerRadius = 9.dp,
                            )
                        } else {
                            Modifier
                        }
                    )
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onSelect(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected) {
                        MiuixTheme.colorScheme.onSurface
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
            }
        }
    }
}
