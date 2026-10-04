package io.github.mangi.eta.agent.dsh

import android.content.Context

/**
 * 当前选中的 agent 预设。
 *
 * 只存一个 id：这个值的**唯一去向**是 [DshPresetPlane.registryRow] 的 `config.default`，
 * 而覆盖层每轮 run 都重新生成，所以"换预设"= 改这一个字符串，下一轮生效（和页面上其它改动
 * 同一条语义：「改动下次开对话才生效」）。
 *
 * 为什么不写进 profile 补丁层：那是用户手改的文件（[DshProfileStore] 在维护，注释要保留），
 * 而这里是 Heta 自己的一个部署选项 —— 混在一起会让"用户在文件里改了什么"和"App 选了什么"
 * 分不清。官方那边对应的是注册表的 `selectedDefault`（volatile 字段，靠客户端 UI 设），
 * ACP 没有那条通道，所以退到部署默认值这条 YAML 路径。
 */
internal object DshPresetSelection {
    private const val PREFS = "eta_dsh_presets"
    private const val KEY_DEFAULT = "default_preset"

    /** 选中的预设 id；没存过、或存的值已经不在四份声明里，就回落到官方默认值。 */
    fun selected(context: Context): String =
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DEFAULT, null)
        }.getOrNull()
            ?.takeIf { it in DshPresetPlane.PRESET_IDS }
            ?: DshPresetPlane.DEFAULT_PRESET

    /**
     * 记下选中的预设。
     *
     * 用 `commit()` 而不是 `apply()`：调用方接着就会拿 [selected] 去生成覆盖层，而 `apply()`
     * 是异步落盘 —— 用户点完立刻开新对话时，可能会读到还没写下去的值。写一次几毫秒，值得。
     *
     * @returns 是否真的写成功（id 不在 [DshPresetPlane.PRESET_IDS] 里直接 false）。
     */
    fun select(context: Context, id: String): Boolean {
        if (id !in DshPresetPlane.PRESET_IDS) return false
        return runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DEFAULT, id)
                .commit()
        }.getOrDefault(false)
    }
}
