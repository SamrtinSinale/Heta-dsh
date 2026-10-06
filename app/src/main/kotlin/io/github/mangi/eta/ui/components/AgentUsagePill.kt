package io.github.mangi.eta.ui.components

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.formatCompactTokenCount
import java.text.NumberFormat
import java.util.Locale
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
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
@Composable
internal fun AgentUsageActions(
    usage: TokenUsageUi,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val total = billedTokenTotal(usage)
    val turn = usage.turn
    val steps = usage.steps
    val hasStats = turn != null && steps != null
    if (total == null && !hasStats) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (hasStats) {
            AgentStatPill(
                icon = Icons.Rounded.Speed,
                label = stringResource(R.string.usage_stats_counts, turn ?: 0, steps ?: 0),
            )
        }
        if (total != null) {
            AgentUsagePill(usage = usage, total = total, locale = locale)
        }
    }
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

/** 用量药丸：点开是「本轮用量」详情（客户端的 TurnUsagePanel）。 */
@Composable
private fun AgentUsagePill(usage: TokenUsageUi, total: Int, locale: Locale) {
    val context = LocalContext.current
    var showDetails by remember { mutableStateOf(false) }
    val label = stringResource(
        R.string.usage_pill_consumed,
        stringResource(R.string.usage_pill_count, formatCompactTokenCount(total, locale)),
    )
    Box {
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

/** 精确值（带千分位）—— 详情里用这个，药丸上用缩写。 */
internal fun formatExactTokenCount(value: Int, locale: Locale): String =
    NumberFormat.getIntegerInstance(locale).format(value)

/** 详情里那几行（标签 → 精确值，带本地千分位）。没报的那几行不出现。 */
internal fun agentUsageDetailRows(
    context: Context,
    usage: TokenUsageUi,
    locale: Locale,
): List<Pair<String, String>> {
    fun exact(value: Int): String = formatExactTokenCount(value, locale)
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
