package io.github.mangi.eta.ui.screens.extensions

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 扩展页局部使用：只淡化内容，不叠加默认 indication、色块或缩放动画。 */
@Composable
internal fun DshFlatRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean? = null,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable RowScope.() -> Unit,
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val opacity by animateFloatAsState(
        targetValue = when {
            !enabled -> 0.38f
            pressed -> 0.6f
            else -> 1f
        },
        animationSpec = tween(90),
        label = "dsh-flat-action-opacity",
    )
    // 用标准 clickable/selectable 保留读屏、键盘和禁用语义，不换成裸 pointerInput。
    val interaction = if (selected == null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onClick = { if (enabled) onClick() },
        )
    } else {
        Modifier.selectable(
            selected = selected,
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = { if (enabled) onClick() },
        )
    }
    Row(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .then(interaction)
            .border(
                width = 1.dp,
                color = if (focused && enabled) MiuixTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .graphicsLayer { alpha = opacity }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        content = content,
    )
}

@Composable
internal fun DshFlatAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasized: Boolean = false,
    icon: ImageVector? = null,
) {
    val color = if (emphasized) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
    DshFlatRow(onClick = onClick, modifier = modifier, enabled = enabled) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
        }
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            fontWeight = if (emphasized) FontWeight.Medium else FontWeight.Normal,
            textAlign = TextAlign.Center,
            color = color,
        )
    }
}

/** 只供扩展弹窗使用，避免为了局部去色改动全 App 的确认按钮。 */
@Composable
internal fun DshFlatDialogActions(
    confirmText: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean,
    cancelEnabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        DshFlatAction(
            text = stringResource(R.string.action_cancel),
            onClick = onCancel,
            enabled = cancelEnabled,
            modifier = Modifier.padding(end = 16.dp),
        )
        DshFlatAction(
            text = confirmText,
            onClick = onConfirm,
            enabled = confirmEnabled,
            emphasized = true,
        )
    }
}
