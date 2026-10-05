package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import io.github.mangi.eta.agent.dsh.DshPresetPlane
import io.github.mangi.eta.agent.dsh.DshPresetSelection

/**
 * 进程内唯一的"当前预设"，供**两个**入口共用：聊天页顶栏的切换器、设置 → 扩展页的单选卡。
 *
 * 为什么要这一层：真正的事实是 [DshPresetSelection] 里那份偏好，而偏好不是可观察的 ——
 * 在扩展页选了「创造模式」再回到聊天页，顶栏那张卡如果各存一份自己的副本就会显示「标准模式」
 * （真机最容易被骂成"没生效"的那种形态）。所以两边都读这一份 [mutableStateOf]：谁写谁
 * [publish]，两边同时跟着变。
 *
 * 为什么它不在 [DshPresetSelection] 里：那个文件被本地 kotlinc 窄口径单测编译
 * （`scripts/test-local-dsh.sh`），classpath 里没有 Compose —— 加一个 `mutableStateOf` 就会
 * 让本地闸门连编译都过不去。偏好归数据层，可观察值归界面层，这条边界本来也更干净。
 */
internal object DshPresetUi {
    private val observable = mutableStateOf(DshPresetPlane.DEFAULT_PRESET)
    private var primed = false

    /**
     * 当前预设。第一次调用时从偏好读一次（幂等）—— 界面在组合里读它就会跟着重组。
     *
     * 读失败、或偏好里的值已经不在四份声明里时，[DshPresetSelection.selected] 自己会回落到
     * 官方默认值，这里不再兜一层。
     */
    fun current(context: Context): String {
        if (!primed) {
            primed = true
            observable.value = DshPresetSelection.selected(context)
        }
        return observable.value
    }

    /**
     * 发布一个新值。
     *
     * **只在偏好真的写成功之后调**（[DshPresetSelection.select] 返回 true），而且要在主线程上调：
     * 落盘那一步在 IO 上，发布这一步在界面状态上。两个写入口都要经过它，否则两边又会不一致。
     */
    fun publish(id: String) {
        if (id in DshPresetPlane.PRESET_IDS) observable.value = id
    }
}
