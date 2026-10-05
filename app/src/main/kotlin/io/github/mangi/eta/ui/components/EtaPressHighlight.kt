/*
 * 按下高亮（0f…1f）：给"自己画的按下色块"用的一套状态机 + 动画。
 *
 * 三轮真机反馈合起来看，结论是两条，别再往回改：
 *   1. 轻点必须看得见 —— `collectIsPressedAsState()` 的 true/false 可能在同一帧内被消费完，
 *      重组只看到 false，动画那次上升会被取消，一帧都画不到（"必须要长按才能显示出色块"）。
 *      所以自己收交互，并保证从按下算起至少亮 [minimumVisibleMillis]。
 *   2. 动画要像 miuix 自带的那一套 —— 真机反馈"3.0.7.23 的点击动画是对的"：按下**渐变**亮起、
 *      松手**渐变**淡出（弹簧），而不是"瞬间点亮 + 线性淡出"（那是 3.0.7.28 的做法，被否掉了），
 *      也不是"完全没有动画"（3.0.7.30，同样被否掉）。
 */
package io.github.mangi.eta.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 亮起：稍快一点，按下就有回应。 */
private val PressEnterSpring = spring<Float>(
    dampingRatio = 0.9f,
    stiffness = Spring.StiffnessHigh,
)

/** 淡出：比亮起慢一档，松手后是"化掉"而不是"啪一下没了"。 */
private val PressExitSpring = spring<Float>(
    dampingRatio = 1f,
    stiffness = Spring.StiffnessMediumLow,
)

@Composable
internal fun rememberPressHighlight(
    interactionSource: InteractionSource,
    minimumVisibleMillis: Long = 120L,
): Float {
    val lit = remember { mutableStateOf(false) }
    LaunchedEffect(interactionSource) {
        var clearJob: Job? = null
        var litAtMillis = 0L
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    clearJob?.cancel()
                    clearJob = null
                    litAtMillis = SystemClock.uptimeMillis()
                    lit.value = true
                }

                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    clearJob?.cancel()
                    val remaining = minimumVisibleMillis - (SystemClock.uptimeMillis() - litAtMillis)
                    clearJob = launch {
                        if (remaining > 0L) delay(remaining)
                        lit.value = false
                    }
                }
            }
        }
    }
    return animateFloatAsState(
        targetValue = if (lit.value) 1f else 0f,
        animationSpec = if (lit.value) PressEnterSpring else PressExitSpring,
        label = "press_highlight",
    ).value
}
