package io.github.mangi.eta.ui.components

import android.content.Context
import io.github.mangi.eta.ui.model.TokenUsageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 回复下面那一行用量的分段规则。
 *
 * 为什么值得测：这几段是**报给用户看的账**，"少一段 / 多一段"就是撒谎或噪声 ——
 *   · 没报的那几段一个都不许画（原生那条会话报不出轮次 / 步数 / 缓存写）；
 *   · `totalTokens` 只在**与"输入 + 输出"不一致**时才单独写（一致时只是把同样的数又说一遍）；
 *   · 顺序固定：轮次 → 步数 → 输入 → 输出 → 缓存读 → 缓存写 → 共。
 * 界面本身看不了（没有真机），所以把这段规则钉在单测里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentUsageFooterTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun parts(usage: TokenUsageUi): List<String> = agentUsageFooterParts(context, usage)

    @Test
    fun dshTurnDrawsEveryNumberItReportedInAFixedOrder() {
        val parts = parts(
            TokenUsageUi(
                turn = 3,
                steps = 2,
                inputTokens = 12_400,
                outputTokens = 1_200,
                cachedTokens = 8_000,
                cacheWriteTokens = 400,
            ),
        )

        assertEquals(6, parts.size)
        assertTrue(parts[0].contains("3"))
        assertTrue(parts[1].contains("2"))
        assertTrue(parts[2].contains("12.4"))
        assertTrue(parts[3].contains("1.2"))
        assertTrue(parts[4].contains("8"))
        assertTrue(parts[5].contains("400"))
    }

    @Test
    fun missingNumbersAreOmittedInsteadOfDrawnAsZero() {
        // 原生那条会话：只有输入输出与缓存读，没有轮次 / 步数 / 缓存写 → 只画三段，顺序照旧。
        val parts = parts(TokenUsageUi(inputTokens = 900, outputTokens = 100, cachedTokens = 500))

        assertEquals(3, parts.size)
        assertTrue(parts[0].contains("900"))
        assertTrue(parts[1].contains("100"))
        assertTrue(parts[2].contains("500"))
    }

    @Test
    fun totalIsDrawnOnlyWhenItDisagreesWithInputPlusOutput() {
        val agreeing = parts(TokenUsageUi(inputTokens = 900, outputTokens = 100, totalTokens = 1_000))
        assertEquals(2, agreeing.size)

        val disagreeing = parts(TokenUsageUi(inputTokens = 900, outputTokens = 100, totalTokens = 1_750))
        assertEquals(3, disagreeing.size)
        assertTrue(disagreeing.last().contains("1.7"))
    }

    @Test
    fun emptyUsageDrawsNothing() {
        assertTrue(parts(TokenUsageUi()).isEmpty())
    }
}
