package io.github.mangi.eta.agent.dsh

import android.content.Context

/**
 * 一个会话上的协作模式意图：计划模式（dsh 的 `/plan`）与目标模式（dsh 的 `/goal`）。
 *
 * 为什么按**会话**存：两者都是会话内的日志状态（计划模式是 `plan` 投影，目标模式是
 * `goal/change` 事件），不是部署级开关 —— 换一个对话就该是干净的一份。键用
 * `RunRequest.effectiveModelSessionId`，也就是 Heta 的对话 id：运行时那边拿它查 dsh 会话
 * （[DshAcpSessionStore]），界面这边拿它取当前对话，两边同一把钥匙。
 *
 * 为什么要有"没被碰过"这第三种状态（`null`）：每条消息都是一次新进程 + `session/resume`，
 * 会话状态由日志折叠回来 —— 所以"现在到底是什么状态"在 Heta 这边**不知道**，只知道自己想要
 * 什么。而 `null`（没碰过）与 `false`（要关掉）不是一回事：前者一条命令都不发，后者要真的执行
 * 一次 `/plan off`。插件那边按"想要的 vs 日志里的"收敛（见 `heta-preset-join.mjs`），所以两边
 * 一致时零命令、零日志噪声。
 */
internal data class DshSessionModes(
    /** 计划模式：true = 进、false = 出、null = 没碰过。 */
    val plan: Boolean? = null,
    /** 目标：非空 = 建/改、空串 = 清除、null = 没碰过。 */
    val goal: String? = null,
) {
    /** 两个意图都没被碰过 —— 覆盖层里就一个字都不加（普通会话的覆盖层因此与原样一致）。 */
    val untouched: Boolean get() = plan == null && goal == null

    /** 真的开着什么模式（输入框那个斜杠入口靠它决定要不要高亮）。 */
    val anyActive: Boolean get() = plan == true || !goal.isNullOrBlank()
}

/**
 * 模式意图的存储（SharedPreferences，按会话 id 分键）。
 *
 * 为什么不写进 profile 补丁层、也不写进覆盖层文件：那是"用户手改的配置"与"这一轮的运行时
 * 产物"，而这是 Heta 自己记的用户选择 —— 和 [DshPresetSelection] 同一条边界。
 *
 * 写入用 `commit()`（同步落盘）：调用方接着就可能开下一轮 run，而 run 一开始就重新生成覆盖层。
 * 调用方负责把它放到 IO（见 `AgentAppState.selectPlanMode` / `selectGoal`）。
 */
internal object DshSessionModeStore {
    private const val PREFS = "eta_dsh_modes"
    private const val PLAN_PREFIX = "plan:"
    private const val GOAL_PREFIX = "goal:"
    private const val ON = "on"
    private const val OFF = "off"

    /**
     * 读一个会话的模式意图。
     *
     * 读不出来（没有偏好、没有这个键、值不认识）就是 [DshSessionModes.untouched] —— 也就是
     * "什么都不做"：不猜、更不替用户开任何一个模式。
     */
    fun read(context: Context, sessionKey: String): DshSessionModes {
        if (sessionKey.isBlank()) return DshSessionModes()
        val prefs = runCatching { preferences(context) }.getOrNull() ?: return DshSessionModes()
        val plan = runCatching { prefs.getString(PLAN_PREFIX + sessionKey, null) }.getOrNull()
        val goal = runCatching { prefs.getString(GOAL_PREFIX + sessionKey, null) }.getOrNull()
        return DshSessionModes(
            plan = when (plan) {
                ON -> true
                OFF -> false
                else -> null
            },
            goal = goal,
        )
    }

    /** 记下计划模式的意图（进 / 出）。@returns 是否真的写成功。 */
    fun writePlan(context: Context, sessionKey: String, plan: Boolean): Boolean =
        write(context, PLAN_PREFIX + sessionKey, if (plan) ON else OFF)

    /** 记下目标：非空 = 建/改，空串 = 清除。@returns 是否真的写成功。 */
    fun writeGoal(context: Context, sessionKey: String, objective: String): Boolean =
        write(context, GOAL_PREFIX + sessionKey, objective.trim())

    private fun write(context: Context, key: String, value: String): Boolean =
        runCatching { preferences(context).edit().putString(key, value).commit() }
            .getOrDefault(false)

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
