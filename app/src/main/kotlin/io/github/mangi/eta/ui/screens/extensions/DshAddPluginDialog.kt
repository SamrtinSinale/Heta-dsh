package io.github.mangi.eta.ui.screens.extensions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.dsh.DshPluginInstaller
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「添加插件」弹窗 —— 文案与结构照客户端（`dsh-client-ui-plugin-manager`）。
 *
 * 三件事按客户端来：
 *   · 一行输入：包名 / GitHub 仓库地址 / 本地目录路径，下面给一个示例（`dsh-plugin-whale-pet`）；
 *   · 一段**可展开**的「插件安装引导和示例」——客户端那不是外链，是内联的三条示例；
 *   · 安装源两个选项（`npm 官方源` / `中国大陆镜像源`），地址逐字取自客户端源码。
 *
 * 还有两段警示语也照抄（来源可信 / 暂不支持自动更新）——这类话不该我自己另写一套。
 */
@Composable
internal fun DshAddPluginDialog(
    show: Boolean,
    working: Boolean,
    onDismiss: () -> Unit,
    onInstall: (String, DshPluginInstaller.Registry) -> Unit,
) {
    if (!show) return
    var spec by remember { mutableStateOf("") }
    var registry by remember { mutableStateOf(DshPluginInstaller.Registry.Npmmirror) }
    var guideExpanded by remember { mutableStateOf(false) }
    val trimmed = spec.trim()
    EtaWindowDialog(
        show = show,
        title = stringResource(R.string.extensions_add_plugin),
        onDismissRequest = { if (!working) onDismiss() },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.extensions_add_plugin_hint),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            TextField(
                value = spec,
                onValueChange = { spec = it },
                label = stringResource(R.string.extensions_add_plugin_example),
                singleLine = true,
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
            )

            // 可展开的引导（客户端就是内联的一段，不是外链）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { guideExpanded = !guideExpanded }
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.extensions_add_plugin_guide),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (guideExpanded) {
                // 三条各写一次（不放进 listOf：占位符检查器按"最近的 ( 里的顶层逗号"数实参，
                // 放 listOf 里会被当成每个键都传了两个参数）。
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

            // 安装源（两个地址与文案逐字取自客户端）
            Text(
                text = stringResource(R.string.extensions_add_plugin_source),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            DshPluginInstaller.Registry.entries.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !working) { registry = option }
                        .padding(vertical = 6.dp),
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

            // 两段警示语（照抄客户端）
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

            MiuixDialogActions(
                confirmText = stringResource(R.string.extensions_add_plugin_install),
                onCancel = { if (!working) onDismiss() },
                // 空输入或正在装：什么都不做（按钮的 enabled 参数我没核过签名，就不赌了）。
                onConfirm = { if (trimmed.isNotEmpty() && !working) onInstall(trimmed, registry) },
            )
            Spacer(modifier = Modifier.width(2.dp))
        }
    }
}
