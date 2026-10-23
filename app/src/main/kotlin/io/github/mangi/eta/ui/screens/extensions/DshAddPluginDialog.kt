package io.github.mangi.eta.ui.screens.extensions

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.dsh.DshPluginInstaller
import io.github.mangi.eta.ui.components.EtaWindowDialog
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 保留包名/仓库/路径、安装引导与安装源，表单不再嵌套填充色块。 */
@Composable
internal fun DshAddPluginDialog(
    show: Boolean,
    working: Boolean,
    onDismiss: () -> Unit,
    onInstall: (String, DshPluginInstaller.Registry) -> Unit,
) {
    if (!show) return
    EtaWindowDialog(
        show = show,
        title = stringResource(R.string.extensions_add_plugin),
        onDismissRequest = { if (!working) onDismiss() },
    ) {
        DshAddPluginContent(working = working, onDismiss = onDismiss, onInstall = onInstall)
    }
}

@Composable
internal fun DshAddPluginContent(
    working: Boolean,
    onDismiss: () -> Unit,
    onInstall: (String, DshPluginInstaller.Registry) -> Unit,
) {
    var spec by remember { mutableStateOf("") }
    var registry by remember { mutableStateOf(DshPluginInstaller.Registry.Npmmirror) }
    var guideExpanded by remember { mutableStateOf(false) }
    val trimmed = spec.trim()
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 长引导和大字体只滚动表单，取消/安装始终留在滚动区域外。
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.extensions_add_plugin_hint),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            DshPluginSpecField(value = spec, onValueChange = { spec = it }, enabled = !working)

            DshFlatRow(
                onClick = { guideExpanded = !guideExpanded },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.extensions_add_plugin_guide),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (guideExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (guideExpanded) {
                Text(
                    text = stringResource(R.string.extensions_add_plugin_guide_package),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Text(
                    text = stringResource(R.string.extensions_add_plugin_guide_github),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Text(
                    text = stringResource(R.string.extensions_add_plugin_guide_local),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Text(
                text = stringResource(R.string.extensions_add_plugin_source),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Column(modifier = Modifier.fillMaxWidth().selectableGroup()) {
                DshPluginInstaller.Registry.entries.forEach { option ->
                    DshFlatRow(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !working,
                        selected = option == registry,
                        onClick = { registry = option },
                    ) {
                        Text(
                            text = option.label,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (option == registry) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MiuixTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            // 保留完整提示，不因视觉精简隐藏安装来源与升级限制。
            Text(
                text = stringResource(R.string.extensions_add_plugin_trust),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = stringResource(R.string.extensions_add_plugin_no_update),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        DshFlatDialogActions(
            confirmText = stringResource(R.string.extensions_add_plugin_install),
            onCancel = { if (!working) onDismiss() },
            onConfirm = { if (trimmed.isNotEmpty() && !working) onInstall(trimmed, registry) },
            cancelEnabled = !working,
            confirmEnabled = trimmed.isNotEmpty() && !working,
        )
    }
}

/** 单行输入只用下划线提示编辑区域；焦点改变线色，不铺主题色背景。 */
@Composable
private fun DshPluginSpecField(value: String, onValueChange: (String) -> Unit, enabled: Boolean) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val colors = MiuixTheme.colorScheme
    val fieldDescription = stringResource(R.string.extensions_add_plugin_hint)
    val lineColor = if (focused && enabled) colors.primary else colors.onSurfaceVariantSummary.copy(alpha = 0.35f)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        interactionSource = interactionSource,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        textStyle = MiuixTheme.textStyles.body2.copy(
            color = if (enabled) colors.onSurface else colors.disabledOnSurface,
        ),
        cursorBrush = SolidColor(colors.primary),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .semantics { contentDescription = fieldDescription }
            .drawBehind {
                val stroke = if (focused && enabled) 2.dp.toPx() else 1.dp.toPx()
                val y = size.height - stroke / 2
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            },
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = stringResource(R.string.extensions_add_plugin_example),
                        style = MiuixTheme.textStyles.body2,
                        color = colors.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                innerTextField()
            }
        },
    )
}
