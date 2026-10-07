package io.github.mangi.eta.ui.screens.extensions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaWindowDialog
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「卸载插件」弹窗：只列 **Heta 自己装过**的那些（账本 `heta-installed-plugins.json`）。
 *
 * 为什么不是"列出所有插件让你卸"：`node_modules` 里 169 个包大多是随包安装的官方包，删一个 dsh
 * 就废了。所以入口只对账本里的模块开 —— 这也是客户端那句"若需升级，请先卸载再安装新版"的前提。
 */
@Composable
internal fun DshUninstallPluginDialog(
    show: Boolean,
    working: Boolean,
    installed: List<String>,
    onDismiss: () -> Unit,
    onUninstall: (String) -> Unit,
) {
    if (!show) return
    EtaWindowDialog(
        show = show,
        title = stringResource(R.string.extensions_uninstall_plugin),
        onDismissRequest = { if (!working) onDismiss() },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (installed.isEmpty()) {
                Text(
                    text = stringResource(R.string.extensions_uninstall_empty),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                return@Column
            }
            installed.forEach { id ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = id,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                    )
                    TextButton(
                        text = stringResource(R.string.extensions_uninstall),
                        onClick = { if (!working) onUninstall(id) },
                        minWidth = 0.dp,
                        minHeight = 34.dp,
                        cornerRadius = 17.dp,
                    )
                }
            }
        }
    }
}
