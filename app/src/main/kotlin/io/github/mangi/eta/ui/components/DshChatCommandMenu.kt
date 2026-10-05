package io.github.mangi.eta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.dsh.DshSessionModes
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 菜单里的一行：写进输入框的**命令原文** + 一句白话说明。 */
private data class DshCommandItem(val line: String, val hintRes: Int)

/**
 * 命令菜单：计划模式与目标模式都从这里进去。
 *
 * 为什么是"把命令写进输入框"而不是"点一下就直接生效"：官方客户端就是这么做的（`/plan` 是
 * 输入框里的一条命令），而且这样用户**看得见自己正要执行什么** —— 点一下立刻改状态的按钮在
 * 真机上被骂过"看的一脸懵逼"。命令的语法见 [io.github.mangi.eta.agent.dsh.DshSlashCommands]，
 * 真正执行发生在发送时（Heta 侧解析，下一轮 run 落到 dsh）。
 *
 * `query` 是用户已经在输入框里敲的那一段（`/` 开头、还没有空格），用来过滤；为 null 时列出全部
 *（点左下角那个 `/` 按钮进来的情况）。
 */
@Composable
internal fun DshChatCommandMenu(
    query: String?,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = DshCommandMenuItems.filter { item ->
        query == null || item.line.startsWith(query)
    }
    if (items.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .squircleSurface(
                color = MiuixTheme.colorScheme.surfaceContainer,
                cornerRadius = 16.dp,
            )
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                cornerRadius = 16.dp,
            )
            .padding(vertical = 4.dp),
    ) {
        items.forEach { item ->
            DshCommandRow(item = item, onClick = { onPick(item.line) })
        }
    }
}

private val DshCommandMenuItems = listOf(
    DshCommandItem("/plan", R.string.chat_command_plan_hint),
    DshCommandItem("/plan off", R.string.chat_command_plan_off_hint),
    DshCommandItem("/goal ", R.string.chat_command_goal_hint),
    DshCommandItem("/goal clear", R.string.chat_command_goal_clear_hint),
)

@Composable
private fun DshCommandRow(item: DshCommandItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = item.line, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = item.line.trimEnd(),
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.primary,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(item.hintRes),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 输入框上方的模式标记：只在**真的有模式开着**时出现，一个圆角小条 + 一个 ×。
 *
 * 为什么要有它：模式开关从顶栏搬走之后，"现在是不是在计划模式里"必须有地方看得见 ——
 * 否则用户只能靠记忆。只在开着时出现，所以平时不会把输入框挤动（真机反馈过"切换模式后重新排版
 * 看的很难受"）。× 直接关掉，不用再敲一遍命令。
 */
@Composable
internal fun DshModeTags(
    modes: DshSessionModes,
    onPlanModeChange: (Boolean) -> Unit,
    onGoalChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val planOn = modes.plan == true
    val objective = modes.goal?.takeIf { it.isNotBlank() }
    if (!planOn && objective == null) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (planOn) {
            DshModeTag(
                text = stringResource(R.string.chat_mode_tag_plan),
                onRemove = { onPlanModeChange(false) },
            )
        }
        if (objective != null) {
            DshModeTag(
                text = stringResource(R.string.chat_mode_tag_goal, objective),
                onRemove = { onGoalChange("") },
            )
        }
    }
}

@Composable
private fun DshModeTag(text: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .height(30.dp)
            .widthIn(max = 260.dp)
            .squircleSurface(
                color = MiuixTheme.colorScheme.surfaceContainerHigh,
                cornerRadius = 15.dp,
            )
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                cornerRadius = 15.dp,
            )
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.chat_mode_tag_remove, text),
            modifier = Modifier
                .size(18.dp)
                .clickable(onClick = onRemove),
            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
    }
}
