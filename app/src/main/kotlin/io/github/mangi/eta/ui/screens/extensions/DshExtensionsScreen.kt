package io.github.mangi.eta.ui.screens.extensions

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
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
import io.github.mangi.eta.agent.dsh.DshLiveEntry
import io.github.mangi.eta.agent.dsh.DshLiveInventory
import io.github.mangi.eta.agent.dsh.DshLivePreset
import io.github.mangi.eta.agent.dsh.DshRowState
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupItem
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitch
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.StatusWarning
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 扩展页：dsh 的 bundle 选中与插件行开关。
 *
 * 页面只做三件事 —— 读一次清单、把点击交给 [DshExtensionsStore]、按搜索词过滤本地行。
 * 所有界面文案走资源；数据层给的问题原文（bundle 的 problem、写入被拒的 reason）原样显示：
 * 那两种理由只有数据层知道，翻一遍反而把信息丢了。
 */
@Composable
internal fun DshExtensionsScreen(context: Context, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val store = remember { DshExtensionsStore(context, scope) }
    // 搜索词只活在这一页里：按模块名 / 行编号做本地过滤，不碰清单、也不重新探针。
    var query by remember { mutableStateOf("") }

    MiuixScaffoldPage(
        title = stringResource(R.string.extensions_title),
        onBack = onBack,
        actions = {
            // 刷新不只是给界面用的：补丁层是普通文件，终端里的 dsh 或用户自己也会改它。
            // 写或探针已经在飞时别再点：两次探针没意义，写还在飞时重探读到的是写之前的形态。
            EtaTextButton(
                text = stringResource(
                    if (store.working || store.probing) {
                        R.string.extensions_refreshing
                    } else {
                        R.string.extensions_refresh
                    },
                ),
                enabled = !store.working && !store.probing,
                onClick = { store.reload() },
            )
        },
    ) {
        item(key = "hmr_note") {
            DshNote(stringResource(R.string.extensions_note_hmr))
        }
        // 顺序就是数据层给的那个顺序，这里不排序、也不假装能排。
        item(key = "order_note") {
            DshNote(stringResource(R.string.extensions_note_order))
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
        // 活清单的两种非正常状态先说出来：探针失败**不是**错误页，它是"这一页改用补丁层视图"，
        // 所以用提示色而不是错误色，并且照旧把文件视图渲染在下面。
        val live = store.live
        val liveProblem = store.liveProblem
        if (liveProblem != null) {
            item(key = "live_problem") {
                DshNote(
                    text = stringResource(R.string.extensions_live_unavailable, liveProblem),
                    color = StatusWarning,
                )
            }
        }
        if (store.liveLoading) {
            item(key = "live_loading") {
                DshNote(stringResource(R.string.extensions_live_loading))
            }
        }

        val inventory = store.inventory
        if (inventory == null) {
            item(key = "unreadable") {
                ListEmptyState(title = stringResource(R.string.extensions_unreadable))
            }
            return@MiuixScaffoldPage
        }

        // 搜索框：纯本地过滤，输入多少都不会重新探针。
        item(key = "search") {
            SearchBar(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                expanded = false,
                onExpandedChange = {},
                inputField = {
                    InputField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = { query = it },
                        expanded = false,
                        onExpandedChange = {},
                        label = stringResource(R.string.extensions_search_hint),
                    )
                },
                content = {},
            )
        }

        val searching = query.isNotBlank()
        val liveEntries = live?.entries.orEmpty().filter { dshRowMatches(query, it.moduleName, it.patchId) }
        val fileRows = inventory.rows.filter { dshRowMatches(query, it.moduleName, it.patchId) }
        // 活清单没落定之前，插件行一行都不画 —— 两套数据的开关口径不一样，按中间态画会闪
        //（开关出现一下又没了），见 [DshExtensionsStore.liveSettled]。
        val rowsReady = live != null || store.liveSettled
        val rowCount = if (live != null) liveEntries.size else fileRows.size

        // 数据层只报"清单里选中的"和"真读得出来的"包；选中的坏包也要列 —— 原因要看得见，
        // 也得能把它关掉（关掉正是那种坏清单的修法）。
        // 插件包没有模块名，搜索命中不了它：搜索时这一整段先收起来。
        val bundles = inventory.bundles.filter { it.isBundle || it.selected }
        if (bundles.isNotEmpty() && !searching) {
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

        if (rowsReady && searching && rowCount == 0) {
            // 搜不到任何行：分组标题一个都不画，只留一句白话的空态。
            item(key = "search_empty") {
                ListEmptyState(title = stringResource(R.string.extensions_search_empty))
            }
        } else if (rowsReady && live != null) {
            // 活清单可用：插件行按官方那页的字段渲染（模块名 / 配置状态 / 运行状态 / 预设）。
            dshLiveSections(store, live, liveEntries, searching)
        } else if (rowsReady) {
            // 取不到活清单：保持原来的文件视图（按补丁层来源分组），开关照旧写 profile 文件。
            // groupBy 用 LinkedHashMap：组标题就是 source（bundle 包名 / profile 补丁层 / home 补丁层 /
            // Heta 覆盖层），按首次出现排序，与清单里的合并顺序一致；搜索时只剩过滤后的组，
            // 所以分组标题也跟着结果走。
            fileRows.groupBy { it.source }.forEach { (source, rows) ->
                item(key = "source_title:$source") { EtaPreferenceGroupTitle(source) }
                // 一行一个 item：卡片外观与行间分割线交给 EtaPreferenceGroupItem（长列表的逐项组合，
                // 见它的 KDoc），展开的详情留在**这一行自己的 item** 里 —— 见 DshExpandableDetails。
                rows.forEachIndexed { index, row ->
                    item(key = "source_row:$source:${row.patchId}") {
                        EtaPreferenceGroupItem(
                            isFirst = index == 0,
                            isLast = index == rows.lastIndex,
                            hasLeading = true,
                        ) {
                            DshPluginSwitchRow(store, row)
                        }
                    }
                }
                item(key = "source_gap:$source") { DshGroupGap() }
            }

            if (fileRows.isEmpty() && !searching) {
                item(key = "rows_empty") {
                    ListEmptyState(
                        title = stringResource(R.string.extensions_empty_title),
                        summary = stringResource(R.string.extensions_empty_summary),
                    )
                }
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

/** 搜索命中判定：模块名或行编号里含这个关键词就算命中（子串、不区分大小写）。 */
private fun dshRowMatches(query: String, moduleName: String?, patchId: String?): Boolean {
    if (query.isBlank()) return true
    val needle = query.trim().lowercase()
    return moduleName?.lowercase()?.contains(needle) == true ||
        patchId?.lowercase()?.contains(needle) == true
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
        // 本地刚点过的那个值优先：写完不再重读清单（见 DshExtensionsStore.pendingBundles）。
        checked = store.bundleSelected(bundle),
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
    // 配置状态也读"本地刚点过的那个"：写完不再重读清单，否则开关会弹回去。
    val state = store.stateFor(row)
    val unresolved = state == DshRowState.UNRESOLVED
    val toggleable = !unresolved && !store.working && row.readOnlyReason == null
    val unresolvedNote = if (unresolved) stringResource(R.string.extensions_row_unresolved) else null
    val patchedNote = if (row.mentionedBy.isEmpty()) {
        null
    } else {
        stringResource(R.string.extensions_row_mentioned_by, row.mentionedBy.joinToString(", "))
    }
    val summary = listOfNotNull(unresolvedNote, patchedNote, row.readOnlyReason).joinToString("\n")
    // 用 EtaPreference 而不是 EtaSwitchPreference：后者整行都是开关，展开的详情没地方挂。
    // **整行点击 = 展开 / 收起**（行尾没有箭头按钮），所以这里的 `enabled` 只表示"整行可点"，
    // 与开关能不能动无关（灰色行也要能展开看细节，所以恒为 true）；开关仍然只是开关：
    // EtaSwitch 自己吃掉点击，不会连带展开。
    EtaPreference(
        enabled = true,
        onClick = { expanded = !expanded },
        onClickLabel = stringResource(R.string.extensions_detail_toggle),
        endActions = {
            // 行尾只留开关。
            EtaSwitch(
                checked = state == DshRowState.ENABLED,
                onCheckedChange = { store.setPluginEnabled(row.patchId, row.moduleName, it) },
                enabled = toggleable,
            )
        },
    ) {
        // `EtaPreferenceRow` 是"给了 titleContent 就不看 title/summary"（二选一），所以标题与说明
        // 必须在这里自己画（同 `SkillSwitchRow`）—— 3.0.7.4 两个都传了，结果不展开时整行是空的。
        //
        // 展开的详情**不在这里**：它挂在行的下面（见 DshExpandableDetails）。塞进这一行的话，
        // 行一变高、右侧那个"垂直居中"的开关与箭头就跟着往下跑（真机反馈③）。
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
    }
    DshExpandableDetails(visible = expanded) {
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
                when (state) {
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

/**
 * 一行下面那块展开的详情。
 *
 * 为什么单独一块、而不是塞进上面那一行的 `titleContent`：`EtaPreferenceRow` 的布局是
 * `actions?.placeRelative(constraints.maxWidth - actions.width, (height - actions.height) / 2)`
 * —— 右侧的开关与箭头**垂直居中**于整行；详情一进这一行，行就变高，它们跟着往下跑（真机反馈③）。
 * 拿出来之后行本身高度恒定，详情在它下面自己长。
 *
 * 动画：`AnimatedVisibility` 的进出场（展开从上往下长、收起缩回去，外带淡入淡出），
 * 与仓里别处（`AgentWorkProcess` / `AgentChatInputBar`）同一套写法。
 */
@Composable
private fun DshExpandableDetails(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)) + expandVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        ),
        exit = fadeOut(tween(120)) + shrinkVertically(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        ),
    ) {
        Column(
            // 与行的正文对齐：行自己左右各缩进 16dp（EtaPreferenceRow 的 SidePadding），
            // 详情跟着缩进同样一段；底部留一点，免得贴着分割线。
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            content = content,
        )
    }
}

/**
 * 组与组之间那段留白。
 *
 * `EtaPreferenceGroup`（整块一张卡片）自带；逐项组合的 `EtaPreferenceGroupItem` 不带
 *（它只管卡片外观与行间分割线），所以逐项组合的列表要自己补一个 —— 16dp 与
 * `EtaPreferenceDefaults.GroupSpacing` 对齐。
 */
@Composable
private fun DshGroupGap() {
    Spacer(Modifier.height(16.dp))
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

/**
 * 活清单那一段：插件行 + 预设。
 *
 * [entries] 是**过滤后**的插件行（搜索按模块名 / 行编号，见 [dshRowMatches]）；[searching] 为
 * true 时页面正在搜索，预设那一段先不画（搜索只认插件行，分组标题也跟着过滤结果走）。
 *
 * 为什么开关仍落在**文件**上：官方的开关走 dsh 的 `pluginManager` op，而 ACP 通道没有 remote
 * 出口 —— 所以开关照旧写 profile 补丁层（[DshExtensionsStore.setPluginEnabled]），
 * **下一次对话生效**（hmr 在 profile 里是关的，页面顶上那条说明一直在说这件事）。
 */
private fun LazyListScope.dshLiveSections(
    store: DshExtensionsStore,
    live: DshLiveInventory.Ready,
    entries: List<DshLiveEntry>,
    searching: Boolean,
) {
    if (!searching) item(key = "live_note") { DshNote(stringResource(R.string.extensions_live_note)) }
    // 分组标题只在过滤后有内容时才画。
    if (entries.isNotEmpty()) {
        item(key = "live_title") {
            EtaPreferenceGroupTitle(stringResource(R.string.extensions_group_live))
        }
        // 一行一个 item（卡片外观与分割线由 EtaPreferenceGroupItem 负责，同 ProviderModelsTab 的模型
        // 列表）：98 条活条目时也只组合看得见的那几行；"展开一行"只影响它自己那一项。
        // 行高恒定 + 详情挂在行的下面 —— 见 DshExpandableDetails。
        entries.forEachIndexed { index, entry ->
            item(key = "live_row:${entry.entryId}") {
                EtaPreferenceGroupItem(
                    isFirst = index == 0,
                    isLast = index == entries.lastIndex,
                    hasLeading = true,
                ) {
                    DshLiveSwitchRow(store, entry)
                }
            }
        }
        item(key = "live_rows_gap") { DshGroupGap() }
    }
    if (searching || live.presets.isEmpty()) return
    item(key = "presets_title") {
        EtaPreferenceGroupTitle(stringResource(R.string.extensions_group_presets))
    }
    item(key = "presets_intro") { DshNote(stringResource(R.string.extensions_preset_intro)) }
    // 一张卡片一个预设：**点整行 = 设为新任务默认**（写 App 的偏好，下一轮 run 生效）。
    // 这里也刻意**不**逐条列出组合行：那是 29×4 行的噪声，而"预设"要回答的是"用哪一套"，不是
    // "这一套里第 17 行是什么"。组合详情在文件视图里逐行可查。
    live.presets.forEachIndexed { index, preset ->
        item(key = "preset:${preset.id}") {
            EtaPreferenceGroupItem(
                isFirst = index == 0,
                isLast = index == live.presets.lastIndex,
                hasLeading = true,
            ) {
                DshPresetChoiceRow(store, preset)
            }
        }
    }
    item(key = "presets_gap") { DshGroupGap() }
}

/**
 * 活清单里的一行插件。
 *
 * 开关给不给，看两件事：
 *   · 桥有没有给出**补丁行 id**（[DshLiveEntry.patchId]，就是 `entry.options.id`）—— 写文件按
 *     补丁行定位，而 `entryId` 是 Loader 树里的 id，两者不是一回事（见 `heta-inventory-bridge.mjs`）；
 *   · 补丁层里**真有**这一行（[DshExtensionsStore.patchRowFor] 查得到）。这一条与官方
 *     `listPlugins` 的 `candidate === undefined → unaddressable` 同口径：查不到的多半是"结构行"
 *     （例如根那条 `include` —— 它的 id 在补丁层里是**组名**，不是可开关的插件行），给它写
 *     `disabled: true` 就把整棵 include 子树关掉了；那之后连探针都起不来，而这一行又只出现在
 *     活清单里，用户在本页**没有**别的入口把它改回来。
 * 缺哪一条都禁掉开关，并在说明里写清楚原因。
 */
@Composable
private fun DshLiveSwitchRow(store: DshExtensionsStore, entry: DshLiveEntry) {
    // 展开状态跟着这一条 Loader 条目走，不是跟着列表走。
    var expanded by remember(entry.entryId) { mutableStateOf(false) }
    val row = store.patchRowFor(entry)
    val patchId = entry.patchId
    // 文件视图那一行给出两件事：这一行在补丁层里存不存在（见上面那段），以及"管理模块不许关"。
    val blocked = row?.readOnlyReason
    val switchable = patchId != null && row != null
    val toggleable = switchable && blocked == null && !store.working
    val configState = configStateLabel(store.enabledFor(entry))
    val runtimeState = runtimeStateLabel(entry.fiberPhase)
    val summary = listOfNotNull(
        stringResource(R.string.extensions_live_summary, configState, runtimeState),
        if (patchId == null) stringResource(R.string.extensions_live_no_patch_id) else null,
        if (patchId != null && row == null) stringResource(R.string.extensions_live_no_patch_row) else null,
        blocked,
    ).joinToString("\n")
    // 同 DshPluginSwitchRow：整行点击 = 展开 / 收起，行尾只留开关（没有箭头按钮）；
    // 开关仍然只是开关，点它不会连带展开。
    EtaPreference(
        enabled = true,
        onClick = { expanded = !expanded },
        onClickLabel = stringResource(R.string.extensions_detail_toggle),
        endActions = {
            // 这里重复一遍 `switchable` 的两个条件，是为了让 lambda 里能直接智能转换
            //（`patchId` / `row` 都是局部 val）—— 不然就要写 `entry.patchId!!`。
            if (patchId != null && row != null) {
                EtaSwitch(
                    // 开关跟着**活**状态走（本地刚点过的那个优先 —— 写完文件 dsh 还没重载）：
                    // dsh 实际用的就是它，补丁层里那一行只决定下一次启动。
                    checked = store.enabledFor(entry),
                    onCheckedChange = { store.setPluginEnabled(patchId, entry.moduleName, it) },
                    enabled = toggleable,
                )
            }
        },
    ) {
        // `EtaPreferenceRow` 是"给了 titleContent 就不看 title/summary"（二选一），所以标题与说明
        // 必须在这里自己画（同 DshPluginSwitchRow）—— 两个都传会让整行空白。
        Text(
            text = entry.moduleName,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            color = if (toggleable) {
                MiuixTheme.colorScheme.onBackground
            } else {
                MiuixTheme.colorScheme.disabledOnSurface
            },
        )
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
    DshExpandableDetails(visible = expanded) {
        DshDetailLine(stringResource(R.string.extensions_detail_entry_id), entry.entryId)
        DshDetailLine(stringResource(R.string.extensions_detail_full_name), entry.moduleName)
        DshDetailLine(
            stringResource(R.string.extensions_detail_patch_id),
            patchId ?: stringResource(R.string.extensions_live_no_patch_id),
        )
        DshDetailLine(stringResource(R.string.extensions_detail_config_state), configState)
        DshDetailLine(stringResource(R.string.extensions_detail_runtime_state), runtimeState)
        // 文件视图里有这一行才说得出"它来自哪一层"；没有就不编。
        row?.let { DshDetailLine(stringResource(R.string.extensions_detail_source), it.source) }
    }
}

/**
 * 一个预设的卡片：名字（官方那套叫法）+ 官方说明 + 状态标记。**点整行 = 设为新任务默认**。
 *
 * 名字与说明为什么不由 roster 给：官方那四份声明**故意不发布 name**（`dsh-client-ui-agent-preset`
 * 的 `isBuiltInPreset` 注释写着 "A shipped preset publishes no `name`; a declaration that names
 * itself owns its copy"），客户端是拿 id 去查自己的文案表。Heta 照做：id → 本页资源，
 * 查不到才退回 roster 给的 name（自定义预设将来走的就是那条）。
 *
 * 挂不起来的预设（[DshLivePreset.broken]）**不给点**：选它等于选一个没有工具的会话。官方原因
 * 照实显示 —— 那是这一行唯一能说明"为什么没有行"的东西。
 */
@Composable
private fun DshPresetChoiceRow(store: DshExtensionsStore, preset: DshLivePreset) {
    val broken = preset.broken
    val selected = store.selectedPresetId == preset.id
    val label = dshPresetLabel(preset.id) ?: preset.name
    val summary = if (broken != null) {
        stringResource(R.string.extensions_preset_broken_summary, broken)
    } else {
        dshPresetSummary(preset.id)
    }
    EtaPreference(
        enabled = broken == null,
        onClick = if (broken == null) ({ store.selectPreset(preset.id) }) else null,
        onClickLabel = if (broken == null) stringResource(R.string.extensions_preset_set_default) else null,
        endActions = {
            // 行尾只放状态：选中 = "新任务默认"；挂不起来 = "加载失败"（红字）。没有可选项时
            // （既没选中也不坏）什么都不放 —— 那正是"可以点它来选"的常态。
            val badge = when {
                broken != null -> stringResource(R.string.extensions_preset_broken)
                selected -> stringResource(R.string.extensions_preset_in_use)
                else -> null
            }
            if (badge != null) {
                Text(
                    text = badge,
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (broken != null) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
            }
        },
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            color = if (broken == null) {
                MiuixTheme.colorScheme.onBackground
            } else {
                MiuixTheme.colorScheme.disabledOnSurface
            },
        )
        if (summary != null) {
            Text(
                text = summary,
                style = MiuixTheme.textStyles.body2,
                color = if (broken == null) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.disabledOnSurface
                },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 官方那四个预设的名字；查不到（自定义预设）返回 null，由调用方退回 roster 给的 name。 */
@Composable
private fun dshPresetLabel(id: String): String? = when (id) {
    "standard" -> stringResource(R.string.extensions_preset_standard_name)
    "ptc" -> stringResource(R.string.extensions_preset_ptc_name)
    "minimal" -> stringResource(R.string.extensions_preset_minimal_name)
    "cordis" -> stringResource(R.string.extensions_preset_cordis_name)
    else -> null
}

/** 官方那四个预设的一句话说明；查不到返回 null（那就只显示名字）。 */
@Composable
private fun dshPresetSummary(id: String): String? = when (id) {
    "standard" -> stringResource(R.string.extensions_preset_standard_summary)
    "ptc" -> stringResource(R.string.extensions_preset_ptc_summary)
    "minimal" -> stringResource(R.string.extensions_preset_minimal_summary)
    "cordis" -> stringResource(R.string.extensions_preset_cordis_summary)
    else -> null
}

/** 配置状态：dsh 报的 `enabled` 是什么就说什么。 */
@Composable
private fun configStateLabel(enabled: Boolean): String = stringResource(
    if (enabled) R.string.extensions_state_enabled else R.string.extensions_state_disabled,
)

/**
 * 运行状态：Fiber 的 phase。
 *
 * 未知取值**原样显示**：dsh 以后新增一个状态时，显示 `reloading` 也好过我们替它编一个中文名
 * —— 编出来的名字会把"我们没跟上"伪装成"我们知道"。
 */
@Composable
private fun runtimeStateLabel(phase: String?): String = when (phase) {
    null -> stringResource(R.string.extensions_run_phase_none)
    "pending" -> stringResource(R.string.extensions_run_phase_pending)
    "loading" -> stringResource(R.string.extensions_run_phase_loading)
    "active" -> stringResource(R.string.extensions_run_phase_active)
    "failed" -> stringResource(R.string.extensions_run_phase_failed)
    "unloading" -> stringResource(R.string.extensions_run_phase_unloading)
    else -> phase
}

// 预设行的逐条状态（`presetRowState`）随"卡片式预设"一起删了：页面上不再逐行列出组合，
// 那部分是 29×4 行的噪声。预设组合行仍然被解析（DshLivePresetRow），只是不画。
