package io.github.mangi.eta.ui.components

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.formatCompactTokenCount
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 回复下面那一行用量：轮次 / 步数 / 输入 / 输出 / 缓存读 / 缓存写。
 *
 * 为什么只画"报了的"那几段：原生那条会话报不出轮次 / 步数 / 缓存写（那是 dsh 的账），而 dsh 那条
 * 会话的缓存写也可能缺席。缺一段就不画那一段 —— 拿 0 顶上等于编数（这一页的底线是不撒谎）。
 *
 * 什么时候画：**回复落定之后**（见调用点）。流式期间数字每步都在变，跟着跳只会让人以为出错。
 */
@Composable
internal fun AgentUsageFooter(
    usage: TokenUsageUi,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val parts = agentUsageFooterParts(context, usage)
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString(" · "),
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.75f),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * 那一行的分段文本（纯函数，好单测）。
 *
 * [totalTokens] 只在**与"输入 + 输出"不一致**时才单独写出来：一致时它只是把同样的数又说了一遍，
 * 多一段只会让人怀疑前面两段是不是漏了什么。
 */
internal fun agentUsageFooterParts(context: Context, usage: TokenUsageUi): List<String> {
    val locale = context.resources.configuration.locales[0]
    fun tokens(value: Int): String = formatCompactTokenCount(value, locale)
    val parts = ArrayList<String>(6)
    usage.turn?.let { parts += context.getString(R.string.usage_footer_turn, it) }
    usage.steps?.let { parts += context.getString(R.string.usage_footer_steps, it) }
    usage.inputTokens?.let { parts += context.getString(R.string.usage_footer_input, tokens(it)) }
    usage.outputTokens?.let { parts += context.getString(R.string.usage_footer_output, tokens(it)) }
    usage.cachedTokens?.let { parts += context.getString(R.string.usage_footer_cache_read, tokens(it)) }
    usage.cacheWriteTokens?.let {
        parts += context.getString(R.string.usage_footer_cache_write, tokens(it))
    }
    val sum = (usage.inputTokens ?: 0) + (usage.outputTokens ?: 0)
    usage.totalTokens?.takeIf { it > 0 && it != sum }?.let {
        parts += context.getString(R.string.usage_footer_total, tokens(it))
    }
    return parts
}
