package io.github.mangi.eta.ui.components

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.dsh.DshRuntimeInstaller
import io.github.mangi.eta.agent.dsh.DshContextUsage
import io.github.mangi.eta.agent.dsh.DshUsageReader
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.formatCompactTokenCount
import java.text.NumberFormat
import java.util.Locale
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

/**
 * 回复那一行的用量与轮次/步数 —— **照客户端画**，不是自己编的一行小灰字。
 *
 * 形状逐条对着 dsh 客户端（`@deepseek-ai/dsh-client-ui-chat` 的 `TurnUsagePanel` / `TimePill`）：
 *   · 它们是消息 IconActions 里的两个「药丸」：透明底、无边框，悬停/展开才有底色；
 *   · 尺寸：高 28dp、图标 15dp、文字 12sp（footnote2）、图标与文字间距 4dp、内边距 6×8dp；
 *   · 文本：`用量 12.4K tok`（客户端的 `message.turnUsage.consumed` + `message.turnUsage.count`）、
 *     `3 轮 2 步`（`stats.counts`）；没有时间数据时客户端也画成不可点的纯药丸（TimePill 那条分支）；
 *   · 点开是「本轮用量」：缓存命中 N% / 未缓存输入 / 缓存读取 / 缓存写入 / 输出（其中推理 N）；
 *   · 药丸上是**缩写**、详情里是**精确值**（带千分位）—— 客户端就是"药丸缩写、详情精确"。
 *
 * 口径（与客户端一致，也是 dsh 适配器报回来的原样）：
 *   · `inputTokens` 是**未缓存**输入（Anthropic 那套线格式里 `input_tokens` 不含缓存，
 *     缓存读取 / 缓存写入是另外两个桶 —— 三个桶互不重叠，见 `dsh-llm-deepseek` 的 `updateUsage`）；
 *   · 总量 = 未缓存输入 + 缓存读取 + 缓存写入 + 输出（客户端 `billedInputTokens + outputTokens`）；
 *   · 缓存命中 = 缓存读取 ÷ 三个输入桶之和（客户端 `cacheHitPercent`），**部分命中绝不显示成 100%**。
 *
 * 没报的那几项一个都不画：拿 0 顶上等于编数（原生那条会话就报不出缓存写与轮次 / 步数）。
 */
/**
 * 动作行里那种小图标按钮（与复制 / 删除同一套：30dp 命中区、15dp 图标、同样的颜色）。
 *
 * 为什么是图标而不是药丸：用户要的是"在复制删除按钮右边加上用量图标和时间" —— 动作行本来就
 * 是一排图标，塞两个带字的药丸会把那一行撑长；图标点开才是详情。
 */
@Composable
private fun AgentActionIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, minWidth = 30.dp, minHeight = 30.dp) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(15.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
        )
    }
}

/** 用量图标：点开是「本轮用量」详情（缓存命中 / 未缓存输入 / 缓存读取 / 缓存写入 / 输出）。 */
@Composable
internal fun AgentUsageIconButton(usage: TokenUsageUi, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var showDetails by remember { mutableStateOf(false) }
    val rows = remember(usage, locale) { agentUsageDetailRows(context, usage, locale) }
    if (rows.isEmpty()) return
    Box(modifier = modifier) {
        AgentActionIcon(
            icon = Icons.Rounded.Storage,
            label = stringResource(R.string.usage_detail_title),
            onClick = { showDetails = true },
        )
        WindowListPopup(
            show = showDetails,
            alignment = PopupPositionProvider.Align.TopEnd,
            onDismissRequest = { showDetails = false },
        ) {
            AgentUsageDetails(
                context = context,
                usage = usage,
                total = billedTokenTotal(usage) ?: 0,
                locale = locale,
            )
        }
    }
}

/**
 * 回复的**结束时间**（客户端 `formatMessageClock`）：
 *   · 当天 → `HH:mm`；
 *   · 同年 → `{m}月{d}日 HH:mm`（英文是 `{m}/{d} HH:mm`）；
 *   · 跨年 → `{y}年{m}月{d}日 HH:mm`。
 *
 * 没有时间（原生那条会话不写 `at`）时什么都不画 —— 不编一个现在的时间出来。
 */
@Composable
internal fun AgentMessageClock(usage: TokenUsageUi, modifier: Modifier = Modifier) {
    val time = usage.finishedAtMs ?: return
    Text(
        text = formatMessageClock(LocalContext.current, time, System.currentTimeMillis()),
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
        maxLines = 1,
        modifier = modifier.padding(start = 4.dp),
    )
}

/** 上面那条规则的纯函数版（好单测）。 */
internal fun formatMessageClock(context: Context, timeMs: Long, nowMs: Long): String {
    val time = java.util.Calendar.getInstance().apply { timeInMillis = timeMs }
    val now = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
    val clock = String.format(
        java.util.Locale.ROOT,
        "%02d:%02d",
        time.get(java.util.Calendar.HOUR_OF_DAY),
        time.get(java.util.Calendar.MINUTE),
    )
    val sameDay = time.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
        time.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
    if (sameDay) return clock
    val template = if (time.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)) {
        context.getString(
            R.string.clock_md,
            time.get(java.util.Calendar.MONTH) + 1,
            time.get(java.util.Calendar.DAY_OF_MONTH),
        )
    } else {
        context.getString(
            R.string.clock_ymd,
            time.get(java.util.Calendar.YEAR),
            time.get(java.util.Calendar.MONTH) + 1,
            time.get(java.util.Calendar.DAY_OF_MONTH),
        )
    }
    return context.getString(R.string.clock_with_time, template, clock)
}

/** 一个药丸：图标 + 文本；[onClick] 为空时不可点（客户端 TimePill 没有时间数据时就是这样）。 */
@Composable
private fun AgentStatPill(
    icon: ImageVector,
    label: String,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 时间药丸：`2 步 · 185 tok/s`（客户端的 TimePill）。
 *
 * 点开是这一轮的 模型用时 / 工具调用用时 / 首 token 平均（TTFT）/ 输出速度（TPS）—— 与客户端
 * 「会话统计」同一张表、同一套文案，只是这里的数只算这一步 / 这一轮。
 */
@Composable
internal fun AgentTimePill(
    usage: TokenUsageUi,
    modifier: Modifier = Modifier,
    sessionTotals: Boolean = false,
    turns: Int? = null,
) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var showDetails by remember { mutableStateOf(false) }
    val steps = usage.steps ?: 0
    val label = buildString {
        if (sessionTotals) {
            append(stringResource(R.string.usage_stats_counts, turns ?: 0, steps))
        } else {
            append(stringResource(R.string.usage_stats_steps_only, steps))
        }
        tokensPerSecond(usage)?.let { speed ->
            append(" · ")
            append(stringResource(R.string.stats_tokens_per_second, speed))
        }
    }
    val rows = remember(usage, locale) { statsDetailRows(context, usage, locale) }
    Box(modifier = modifier) {
        AgentStatPill(
            icon = Icons.Rounded.Speed,
            label = label,
            onClick = { showDetails = true },
        )
        WindowListPopup(
            show = showDetails,
            alignment = PopupPositionProvider.Align.TopEnd,
            onDismissRequest = { showDetails = false },
        ) {
            AgentStatsDetails(
                context = context,
                rows = rows,
                title = stringResource(R.string.stats_dialog_title),
            )
        }
    }
}

/** 统计详情弹层：标题 + 一条分隔线 + 逐行「标签 → 值」。 */
@Composable
private fun AgentStatsDetails(
    context: Context,
    rows: List<Pair<String, String>>,
    title: String,
) {
    if (rows.isEmpty()) return
    ListPopupColumn {
        Row(
            modifier = Modifier
                .width(DetailWidth)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Speed,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        HorizontalDivider(modifier = Modifier.width(DetailWidth))
        rows.forEach { (label, value) ->
            Row(
                modifier = Modifier
                    .width(DetailWidth)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = value,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 输入框上面那一行（客户端的 `StatsPills`）：会话统计 + Token 用量，两个药丸居中。
 *
 * 会话统计那颗是 `3 轮 6 步 · 185 tok/s`，点开是「会话统计」（模型用时 / 工具调用用时 /
 * 首 token 平均（TTFT）/ 输出速度（TPS)）；Token 那颗是 `总量 · 缓存命中 N%`，点开是 Token 用量。
 */
@Composable
internal fun AgentSessionStatsPills(
    totals: TokenUsageUi,
    modifier: Modifier = Modifier,
) {
    if (totals.isEmpty) return
    val locale = LocalConfiguration.current.locales[0]
    val total = billedTokenTotal(totals)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (totals.steps != null) {
            AgentTimePill(
                usage = totals,
                modifier = Modifier.padding(horizontal = 6.dp),
                sessionTotals = true,
                turns = totals.turn,
            )
        }
        if (total != null) {
            AgentUsagePill(
                usage = totals,
                total = total,
                locale = locale,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

/**
 * 上下文**构成**那几行（系统提示词 / 工具定义 / 对话消息）—— 只有会话插件那份文件里有。
 *
 * 那三个数是官方说明里写明的**启发式估算**（估算器系统性低估 CJK 与 JSON schema），所以值前面
 * 带 `~`，而且它们加起来不等于占用值：这是"构成"的近似，不是总量。
 */
internal fun contextBreakdownOf(context: Context): DshContextUsage? {
    val root = runCatching { DshRuntimeInstaller.runtimeDirectory(context).absolutePath }
        .getOrNull()
        ?.takeIf { it.isNotBlank() } ?: return null
    return DshUsageReader.latestSession(root)?.context
}

/** 用量药丸：点开是「本轮用量」详情（客户端的 TurnUsagePanel）。 */
@Composable
private fun AgentUsagePill(
    usage: TokenUsageUi,
    total: Int,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showDetails by remember { mutableStateOf(false) }
    val label = usagePillLabel(context, usage, total, locale)
    Box(modifier = modifier) {
        AgentStatPill(
            icon = Icons.Rounded.Storage,
            label = label,
            onClick = { showDetails = true },
        )
        WindowListPopup(
            show = showDetails,
            alignment = PopupPositionProvider.Align.TopEnd,
            onDismissRequest = { showDetails = false },
        ) {
            AgentUsageDetails(context = context, usage = usage, total = total, locale = locale)
        }
    }
}

/** 详情弹层：标题（本轮用量 + 精确总量）、一条分隔线、然后逐行「标签 → 精确值」。 */
@Composable
private fun AgentUsageDetails(context: Context, usage: TokenUsageUi, total: Int, locale: Locale) {
    val rows = remember(usage, locale) { agentUsageDetailRows(context, usage, locale) }
    ListPopupColumn {
        Row(
            modifier = Modifier
                .width(DetailWidth)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Storage,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.usage_detail_title),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(
                    R.string.usage_pill_count,
                    formatExactTokenCount(total, locale),
                ),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
        HorizontalDivider(modifier = Modifier.width(DetailWidth))
        rows.forEach { (label, value) ->
            Row(
                modifier = Modifier
                    .width(DetailWidth)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = value,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

private val DetailWidth = 248.dp

/**
 * 药丸上那个总量：未缓存输入 + 缓存读取 + 缓存写入 + 输出（客户端 billedInputTokens + outputTokens）。
 *
 * 四个桶一个都没报时才退回模型报的 `totalTokens`：各家线格式对"总量含不含缓存"口径不一，
 * 自己按桶加出来的数才是能对上的那个。
 */
internal fun billedTokenTotal(usage: TokenUsageUi): Int? {
    val buckets = listOfNotNull(
        usage.inputTokens,
        usage.cachedTokens,
        usage.cacheWriteTokens,
        usage.outputTokens,
    )
    if (buckets.isNotEmpty()) return buckets.sum()
    return usage.totalTokens
}

/**
 * 缓存命中率（百分比文本）。[cacheRead] 为空、或输入侧一个数都没有时返回 null（那一行不画）。
 *
 * 精度照客户端那套的**诚实**要求：部分命中绝不四舍五入成 100% —— 一位小数会顶到 100.0 时
 * 就多用一位，直到这个数真的低于 100。
 */
internal fun cacheHitPercent(cacheRead: Int?, uncachedInput: Int?, cacheWrite: Int?): String? {
    if (cacheRead == null || cacheRead < 0) return null
    val denominator = (uncachedInput ?: 0).toLong() + cacheRead.toLong() + (cacheWrite ?: 0).toLong()
    if (denominator <= 0L) return null
    if (cacheRead.toLong() >= denominator) return "100"
    for (places in intArrayOf(1, 2, 3)) {
        val scale = if (places == 1) 10L else if (places == 2) 100L else 1000L
        val units = (cacheRead.toLong() * 100L * scale + denominator / 2) / denominator
        if (units < 100L * scale) return trimPercent(units, places)
    }
    return "99.9"
}

private fun trimPercent(units: Long, places: Int): String {
    val whole = units / (if (places == 1) 10L else if (places == 2) 100L else 1000L)
    val fraction = units % (if (places == 1) 10L else if (places == 2) 100L else 1000L)
    if (fraction == 0L) return whole.toString()
    return whole.toString() + "." + fraction.toString().padStart(places, '0').trimEnd('0')
}

/**
 * 药丸上那一行字：`用量 12.4K tok · 缓存命中 92%`。
 *
 * 前半句是客户端**每轮**药丸的措辞（`message.turnUsage.consumed` + `message.turnUsage.count`），
 * 后半句借它**会话统计**药丸的那句（`stats.cacheHit`）—— 用户要的就是"回复下面能一眼看见 cache"，
 * 藏进详情里等于没显示。没有缓存读取（原生那条会话报不出）时后半句不出现。
 */
internal fun usagePillLabel(
    context: Context,
    usage: TokenUsageUi,
    total: Int,
    locale: Locale,
): String {
    val head = context.getString(
        R.string.usage_pill_consumed,
        context.getString(R.string.usage_pill_count, formatCompactTokenCount(total, locale)),
    )
    val percent = cacheHitPercent(usage.cachedTokens, usage.inputTokens, usage.cacheWriteTokens)
        ?: return head
    return head + " · " + context.getString(R.string.stats_cache_hit, percent)
}

/**
 * 输出速度（TPS）：Σ输出 token ÷ (Σ解码时长 ÷ 1000)，≥10 取整、否则一位小数（照客户端
 * `formatTokensPerSecond`）。没有解码时长或没有输出 token 时返回 null（那一段不写）。
 */
internal fun tokensPerSecond(usage: TokenUsageUi): String? {
    val ms = usage.decodeMs ?: return null
    val tokens = usage.decodeTokens ?: return null
    if (ms <= 0L || tokens <= 0) return null
    val tps = tokens.toDouble() / (ms.toDouble() / 1000.0)
    val text = if (tps >= 10.0) Math.round(tps).toString()
    else (Math.round(tps * 10.0) / 10.0).toString()
    return text
}

/**
 * 时长文本（照客户端 `formatDuration`）：不到一分钟写 `12.3秒`，之后写 `2分42秒`。
 */
internal fun formatDurationMs(context: Context, ms: Long): String {
    val seconds = ms / 1000.0
    if (seconds < 60.0) {
        val rounded = Math.round(seconds * 10.0) / 10.0
        return context.getString(R.string.duration_seconds, rounded.toString())
    }
    val whole = Math.round(seconds)
    return context.getString(R.string.duration_minutes, whole / 60, whole % 60)
}

/**
 * 「会话统计」那几行：模型用时 / 工具调用用时 / 首 token 平均（TTFT）/ 输出速度（TPS）。
 *
 * 四项都只在该有值时出现（照客户端：`> 0` 才画）—— 拿 0 顶上等于编数。
 * [usage] 逐轮时是这一轮的数，会话级时是把每条回复加起来的数（见 [sessionTotalsFrom]）。
 */
internal fun statsDetailRows(
    context: Context,
    usage: TokenUsageUi,
    locale: java.util.Locale,
): List<Pair<String, String>> {
    val rows = ArrayList<Pair<String, String>>(4)
    // 有数就画：插件报回来的 0 也是"量过、就是 0"，不该整行消失（用户反馈工具调用用时不见了）。
    // 只有**没报**（null）才不画 —— 那才是"不知道"。
    usage.llmMs?.let {
        rows += context.getString(R.string.stats_dialog_llm_time) to formatDurationMs(context, it)
    }
    usage.toolMs?.let {
        rows += context.getString(R.string.stats_dialog_tool_time) to formatDurationMs(context, it)
    }
    val ttftSteps = usage.ttftSteps ?: 0
    usage.ttftMs?.takeIf { ttftSteps > 0 }?.let {
        rows += context.getString(R.string.stats_dialog_ttft) to formatDurationMs(context, it / ttftSteps)
    }
    tokensPerSecond(usage)?.let {
        rows += context.getString(R.string.stats_dialog_speed) to
            context.getString(R.string.stats_tokens_per_second, it)
    }
    return rows
}

/**
 * 会话累计：把每条回复的用量加起来。
 *
 * 客户端读的是整条会话日志的投影（`sessionStats`）；Heta 这条路上最接近的等价物就是"对话里所有
 * 回复的用量" —— 每一条都存了库，重启后照样算得出来，不必再去读文件。
 */
internal fun sessionTotalsFrom(messages: List<AgentChatMessageUi>): TokenUsageUi {
    var total = TokenUsageUi()
    messages.filterIsInstance<AgentMessageUi>().forEach { message ->
        val usage = message.usage ?: return@forEach
        total = total.copy(
            turn = (total.turn ?: 0) + (usage.turn?.let { 1 } ?: 0),
            steps = sumInt(total.steps, usage.steps),
            inputTokens = sumInt(total.inputTokens, usage.inputTokens),
            outputTokens = sumInt(total.outputTokens, usage.outputTokens),
            cachedTokens = sumInt(total.cachedTokens, usage.cachedTokens),
            cacheWriteTokens = sumInt(total.cacheWriteTokens, usage.cacheWriteTokens),
            reasoningTokens = sumInt(total.reasoningTokens, usage.reasoningTokens),
            llmMs = sumLong(total.llmMs, usage.llmMs),
            toolMs = sumLong(total.toolMs, usage.toolMs),
            ttftMs = sumLong(total.ttftMs, usage.ttftMs),
            ttftSteps = sumInt(total.ttftSteps, usage.ttftSteps),
            decodeMs = sumLong(total.decodeMs, usage.decodeMs),
            decodeTokens = sumInt(total.decodeTokens, usage.decodeTokens),
        )
    }
    return total
}

private fun sumInt(left: Int?, right: Int?): Int? =
    if (left == null && right == null) null else (left ?: 0) + (right ?: 0)

private fun sumLong(left: Long?, right: Long?): Long? =
    if (left == null && right == null) null else (left ?: 0L) + (right ?: 0L)

/** 精确值（带千分位）—— 详情里用这个，药丸上用缩写。 */
internal fun formatExactTokenCount(value: Int, locale: Locale): String =
    NumberFormat.getIntegerInstance(locale).format(value)

/** 详情里那几行（标签 → 精确值，带本地千分位）。没报的那几行不出现。 */
internal fun agentUsageDetailRows(
    context: Context,
    usage: TokenUsageUi,
    locale: Locale,
): List<Pair<String, String>> {
    // 客户端 `exactCount` 的写法是 `{count} tok` —— 数字后面那个单位不能省（用户点了这一条）。
    fun exact(value: Int): String =
        context.getString(R.string.usage_pill_count, formatExactTokenCount(value, locale))
    val rows = ArrayList<Pair<String, String>>(5)
    cacheHitPercent(usage.cachedTokens, usage.inputTokens, usage.cacheWriteTokens)?.let { percent ->
        rows += context.getString(R.string.usage_detail_cache_hit) to "$percent%"
    }
    usage.inputTokens?.let {
        rows += context.getString(R.string.usage_detail_uncached_input) to exact(it)
    }
    usage.cachedTokens?.let {
        rows += context.getString(R.string.usage_detail_cache_read) to exact(it)
    }
    usage.cacheWriteTokens?.takeIf { it > 0 }?.let {
        rows += context.getString(R.string.usage_detail_cache_write) to exact(it)
    }
    usage.outputTokens?.let { output ->
        val reasoning = usage.reasoningTokens?.takeIf { it > 0 }?.let { tokens ->
            context.getString(R.string.usage_detail_reasoning, exact(tokens))
        }
        rows += context.getString(R.string.usage_detail_output) to (exact(output) + reasoning.orEmpty())
    }
    return rows
}
