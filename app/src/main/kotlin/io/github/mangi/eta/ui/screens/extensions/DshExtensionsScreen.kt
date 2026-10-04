package io.github.mangi.eta.ui.screens.extensions

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.dsh.DshInventoryBundle
import io.github.mangi.eta.agent.dsh.DshInventoryRow
import io.github.mangi.eta.agent.dsh.DshRowState
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitch
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 扩展页：dsh 的 bundle 选中与插件行开关。
 *
 * 页面只做两件事 —— 读一次清单、把点击交给 [DshExtensionsStore]。所有界面文案走资源；
 * 数据层给的问题原文（bundle 的 problem、写入被拒的 reason）原样显示：那两种理由只有
 * 数据层知道，翻一遍反而把信息丢了。
 */
@Composable
internal fun DshExtensionsScreen(context: Context, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val store = remember { DshExtensionsStore(context, scope) }

    MiuixScaffoldPage(
        title = stringResource(R.string.extensions_title),
        onBack = onBack,
        actions = {
            // 刷新不只是给界面用的：补丁层是普通文件，终端里的 dsh 或用户自己也会改它。
            IconButton(enabled = !store.working, onClick = { store.reload() }) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.extensions_refresh),
                )
            }
        },
    ) {
        item(key = "hmr_note") {
            DshNote(stringResource(R.string.extensions_note_hmr))
        }

        val message = store.message
        if (message != null) {
            item(key = "message") {
                DshNote(
                    text = message,
                    color = if (store.messageIsError) StatusError else StatusSuccess,
                )
            }
        }

        if (!store.runtimeReady) {
            item(key = "runtime_missing") {
                ListEmptyState(
                    title = stringResource(R.string.extensions_runtime_missing),
                    summary = stringResource(R.string.extensions_runtime_missing_summary),
                )
            }
            return@MiuixScaffoldPage
        }
        if (store.loading) {
            item(key = "loading") {
                DshNote(stringResource(R.string.extensions_loading))
            }
            return@MiuixScaffoldPage
        }
        val inventory = store.inventory
        if (inventory == null) {
            item(key = "unreadable") {
                ListEmptyState(title = stringResource(R.string.extensions_unreadable))
            }
            return@MiuixScaffoldPage
        }

        // 数据层只报"清单里选中的"和"真读得出来的"包；选中的坏包也要列 —— 原因要看得见，
        // 也得能把它关掉（关掉正是那种坏清单的修法）。
        val bundles = inventory.bundles.filter { it.isBundle || it.selected }
        if (bundles.isNotEmpty()) {
            item(key = "bundles_title") {
                EtaPreferenceGroupTitle(stringResource(R.string.extensions_group_bundles))
            }
            item(key = "bundles") {
                EtaPreferenceGroup {
                    bundles.forEachIndexed { index, bundle ->
                        if (index > 0) EtaPreferenceDivider()
                        DshBundleSwitchRow(store, bundle)
                    }
                }
            }
        }

        // groupBy 用 LinkedHashMap：组标题就是 source（bundle 包名 / profile 补丁层 / home 补丁层 /
        // Heta 覆盖层），按首次出现排序，与清单里的合并顺序一致。
        inventory.rows.groupBy { it.source }.forEach { (source, rows) ->
            item(key = "source_title:$source") { EtaPreferenceGroupTitle(source) }
            item(key = "source_rows:$source") {
                EtaPreferenceGroup {
                    rows.forEachIndexed { index, row ->
                        if (index > 0) EtaPreferenceDivider()
                        DshPluginSwitchRow(store, row)
                    }
                }
            }
        }

        if (inventory.rows.isEmpty()) {
            item(key = "rows_empty") {
                ListEmptyState(
                    title = stringResource(R.string.extensions_empty_title),
                    summary = stringResource(R.string.extensions_empty_summary),
                )
            }
        }

        // 层本身的问题（补丁文件读不出来之类）：bundle 的问题在各自那一行上，这里只放层的。
        if (inventory.problems.isNotEmpty()) {
            item(key = "problems_title") {
                EtaPreferenceGroupTitle(stringResource(R.string.extensions_group_problems))
            }
            item(key = "problems") {
                EtaPreferenceGroup {
                    inventory.problems.forEachIndexed { index, problem ->
                        if (index > 0) EtaPreferenceDivider(hasLeading = false)
                        DshProblem(problem, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DshBundleSwitchRow(store: DshExtensionsStore, bundle: DshInventoryBundle) {
    // 读不出来的原因、以及"不许关"的原因，都贴在行下：两件事都是这一行自己的属性。
    // joinToString 之后非空化，是为了让下面那个 lambda 里不需要任何智能转换。
    val note = listOfNotNull(bundle.problem, bundle.readOnlyReason).joinToString("\n")
    val bottom: (@Composable () -> Unit)? = if (note.isEmpty()) {
        null
    } else {
        { DshProblem(note, Modifier.padding(vertical = 2.dp)) }
    }
    EtaSwitchPreference(
        title = bundle.name,
        // 没读出来的包行数是 0，那不是"它有 0 行"，所以不显示。
        summary = if (bundle.isBundle) stringResource(R.string.extensions_bundle_rows, bundle.rowCount) else null,
        checked = bundle.selected,
        // 管理/入口 bundle 不给关：关了运行时自己就起不来（数据层给的理由显示在下面）。
        enabled = !store.working && bundle.readOnlyReason == null,
        onCheckedChange = { store.setBundleSelected(bundle.name, it) },
        bottomAction = bottom,
    )
}

@Composable
private fun DshPluginSwitchRow(store: DshExtensionsStore, row: DshInventoryRow) {
    // 展开状态跟着这一行（patchId）走，不是跟着列表走。
    var expanded by remember(row.patchId) { mutableStateOf(false) }
    val unresolved = row.state == DshRowState.UNRESOLVED
    val toggleable = !unresolved && !store.working && row.readOnlyReason == null
    val unresolvedNote = if (unresolved) stringResource(R.string.extensions_row_unresolved) else null
    val patchedNote = if (row.mentionedBy.isEmpty()) {
        null
    } else {
        stringResource(R.string.extensions_row_mentioned_by, row.mentionedBy.joinToString(", "))
    }
    val summary = listOfNotNull(unresolvedNote, patchedNote, row.readOnlyReason).joinToString("\n")
    // 用 EtaPreference 而不是 EtaSwitchPreference：后者整行都是开关，没地方再放"展开"。
    // 行本身不带 onClick（开关和展开各自可点），灰行也能展开看细节。
    EtaPreference(
        enabled = toggleable,
        endActions = {
            EtaSwitch(
                checked = row.state == DshRowState.ENABLED,
                onCheckedChange = { store.setPluginEnabled(row, it) },
                enabled = toggleable,
            )
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = stringResource(R.string.extensions_detail_toggle),
                )
            }
        },
    ) {
        // `EtaPreferenceRow` 是"给了 titleContent 就不看 title/summary"（二选一），所以标题与说明
        // 必须在这里自己画（同 `SkillSwitchRow`）—— 3.0.7.4 两个都传了，结果不展开时整行是空的。
        Text(
            text = row.moduleName ?: row.patchId,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            color = if (toggleable) {
                MiuixTheme.colorScheme.onBackground
            } else {
                MiuixTheme.colorScheme.disabledOnSurface
            },
        )
        if (summary.isNotEmpty()) {
            Text(
                text = summary,
                style = MiuixTheme.textStyles.body2,
                color = if (toggleable) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.disabledOnSurface
                },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (expanded) {
            // 只放我们**真的知道**的四项。运行状态（官方那页的"运行中"）来自 dsh 进程里的活
            // 条目，补丁层里看不到 —— 宁可不显示，也不编一个出来。
            DshDetailLine(
                label = stringResource(R.string.extensions_detail_full_name),
                value = row.moduleName ?: stringResource(R.string.extensions_no_module_name),
            )
            DshDetailLine(
                label = stringResource(R.string.extensions_detail_patch_id),
                value = row.patchId,
            )
            DshDetailLine(
                label = stringResource(R.string.extensions_detail_config_state),
                value = stringResource(
                    when (row.state) {
                        DshRowState.ENABLED -> R.string.extensions_state_enabled
                        DshRowState.DISABLED -> R.string.extensions_state_disabled
                        DshRowState.UNRESOLVED -> R.string.extensions_row_unresolved
                    },
                ),
            )
            DshDetailLine(
                label = stringResource(R.string.extensions_detail_source),
                value = row.source,
            )
        }
    }
}

/** 展开后的一行「名字：值」。 */
@Composable
private fun DshDetailLine(label: String, value: String) {
    Text(
        text = "$label：$value",
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** 页面级说明与结果提示；footnote2 + 与分组标题同一套留白（同语音设置页的说明行）。 */
@Composable
private fun DshNote(
    text: String,
    color: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = color,
        modifier = modifier.padding(horizontal = 32.dp, vertical = 8.dp),
    )
}

/** 错误原文：数据层给的中文理由，照实显示，不翻译、不吞。 */
@Composable
private fun DshProblem(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.error,
        modifier = modifier,
    )
}
