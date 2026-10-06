package io.github.mangi.eta.ui.components

import android.content.Context
import io.github.mangi.eta.ui.model.TokenUsageUi
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 那两个药丸的**数与口径**（形状照客户端，规则钉在这里）。
 *
 * 为什么值得测：这几个数是报给用户看的账，错一位就是撒谎。三件事：
 *   · 总量按**桶**加（未缓存输入 + 缓存读取 + 缓存写入 + 输出），一个桶都没有才退回模型报的总量；
 *   · 缓存命中 = 缓存读取 ÷ 三个输入桶之和，**部分命中绝不显示成 100%**；
 *   · 详情里没报的那几行不出现，输出那一行带「（其中推理 N）」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentUsagePillTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun totalIsTheSumOfTheBilledBuckets() {
        // 客户端 billedInputTokens + outputTokens：四个桶相加，缓存那两个也算钱。
        assertEquals(
            36_100,
            billedTokenTotal(
                TokenUsageUi(
                    inputTokens = 900,
                    cachedTokens = 24_000,
                    cacheWriteTokens = 1_200,
                    outputTokens = 10_000,
                ),
            ),
        )
        // 一个桶都没有（原生那条路只报了总量）才退回 totalTokens。
        assertEquals(1_000, billedTokenTotal(TokenUsageUi(totalTokens = 1_000)))
        assertNull(billedTokenTotal(TokenUsageUi()))
    }

    @Test
    fun cacheHitNeverRoundsAPartialHitUpToHundred() {
        // 999 / 1000：一位小数会顶到 100.0，必须往下多给一位。
        assertEquals("99.9", cacheHitPercent(999, 1, 0))
        assertEquals("92.3", cacheHitPercent(923, 77, 0))
        assertEquals("100", cacheHitPercent(1_000, 0, 0))
        // 输入侧一个数都没有、或压根没报缓存读取：不画那一行。
        assertNull(cacheHitPercent(0, 0, 0))
        assertNull(cacheHitPercent(null, 100, 0))
    }

    @Test
    fun detailRowsFollowTheClientAndSkipWhatWasNotReported() {
        val rows = agentUsageDetailRows(
            context,
            TokenUsageUi(
                inputTokens = 900,
                cachedTokens = 24_000,
                cacheWriteTokens = 1_200,
                outputTokens = 10_000,
                reasoningTokens = 3_000,
            ),
            Locale.US,
        )

        // 顺序照客户端：缓存命中 → 未缓存输入 → 缓存读取 → 缓存写入 → 输出；值是**精确值**。
        assertEquals(5, rows.size)
        assertTrue(rows[0].second.endsWith("%"))
        assertEquals("900", rows[1].second)
        assertEquals("24,000", rows[2].second)
        assertEquals("1,200", rows[3].second)
        assertTrue(rows[4].second.startsWith("10,000"))
        assertTrue(rows[4].second.contains("3,000"))
    }

    @Test
    fun pillLabelCarriesTheCacheHitAndDropsItWhenNothingWasCached() {
        val withCache = usagePillLabel(
            context,
            TokenUsageUi(inputTokens = 77, cachedTokens = 923, outputTokens = 11_400),
            12_400,
            Locale.US,
        )
        // 前半句是每轮药丸的措辞，后半句是会话统计那句 —— 两个都照客户端。
        assertTrue("药丸文本：$withCache", withCache.contains("12.4K"))
        assertTrue("药丸文本：$withCache", withCache.contains("92.3%"))

        // 原生那条会话没报缓存读取：后半句不出现（不编一个 0% 出来）。
        val withoutCache = usagePillLabel(
            context,
            TokenUsageUi(inputTokens = 900, outputTokens = 100),
            1_000,
            Locale.US,
        )
        assertTrue("药丸文本：$withoutCache", !withoutCache.contains("%"))
    }

    @Test
    fun durationFollowsTheClientFormat() {
        // 客户端 formatDuration：不到一分钟写 `12.3秒`，之后写 `2分42秒`。
        assertTrue(formatDurationMs(context, 12_340).startsWith("12.3"))
        assertTrue(formatDurationMs(context, 162_000).contains("2"))
        assertTrue(formatDurationMs(context, 162_000).contains("42"))
        // 一分钟整也要写成 1分0秒（客户端就是这么写的）。
        assertTrue(formatDurationMs(context, 60_000).contains("1"))
    }

    @Test
    fun tokensPerSecondMatchesTheClientRounding() {
        // ≥10 取整、否则一位小数（客户端 formatTokensPerSecond）。
        assertEquals("185", tokensPerSecond(TokenUsageUi(decodeMs = 1_000, decodeTokens = 185)))
        assertEquals("9.5", tokensPerSecond(TokenUsageUi(decodeMs = 2_000, decodeTokens = 19)))
        // 没有解码时长或没有输出：那一段不写。
        assertNull(tokensPerSecond(TokenUsageUi(decodeTokens = 100)))
        assertNull(tokensPerSecond(TokenUsageUi(decodeMs = 1_000)))
    }

    @Test
    fun statsRowsFollowTheClientAndSkipZeroes() {
        val rows = statsDetailRows(
            context,
            TokenUsageUi(
                llmMs = 16_687,
                toolMs = 0,
                ttftMs = 15_388,
                ttftSteps = 6,
                decodeMs = 1_299,
                decodeTokens = 75,
            ),
            Locale.US,
        )

        // 工具用时是 0 → 那一行不画；其余三行照客户端的顺序与措辞。
        assertEquals(3, rows.size)
        assertTrue(rows.none { it.first.contains("Tool") })
        assertTrue(rows[2].second.contains("tok/s"))
    }

    @Test
    fun rowsForANativeSessionSkipTurnStatsAndCacheWrite() {
        // 原生那条会话：只有输入输出与缓存读取 —— 缓存写那一行不出现。
        val rows = agentUsageDetailRows(
            context,
            TokenUsageUi(inputTokens = 900, outputTokens = 100, cachedTokens = 500),
            Locale.US,
        )

        assertEquals(4, rows.size)
        assertTrue(rows.none { it.first.contains("Cache write") || it.first.contains("写入") })
        // 精确值走本地千分位（药丸上才是缩写）。
        assertEquals("1,234,567", formatExactTokenCount(1_234_567, Locale.US))
    }
}
