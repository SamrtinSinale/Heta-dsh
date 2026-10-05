package io.github.mangi.eta.agent.dsh

/**
 * 输入框里的斜杠命令：Heta 自己解析、自己执行，**不会**把这一行原样发给模型。
 *
 * 为什么要有它：计划模式与目标模式在 dsh 里就是斜杠命令（`/plan`、`/goal`），而 ACP 这条路上
 * 没有命令通道 —— 命令只能由 UI 半边发起（真正落到 dsh 上是 join 插件在会话建立时补的那次
 * `commands.execute()`，见 heta-preset-join.mjs）。所以 Heta 把这条命令的**语法**照抄过来：
 * 用户敲的、或从菜单里点出来的，就是官方那条命令；执行发生在 Heta 侧，下一轮 run 生效
 *（承载它的覆盖层每轮重新生成）。
 *
 * 语法照 dsh-plan-mode 的 handler 与 dsh-command-goal 的 parseGoalCommand 来：
 *
 *   · `/plan`             进入计划模式
 *   · `/plan off`         退出计划模式
 *   · `/plan <文本>`      进入计划模式，并把 `<文本>` 当成这一轮的用户消息（dsh 那边是 steer）
 *   · `/goal <目标>`      设目标（dsh 侧按现状 create 或 edit）
 *   · `/goal edit <目标>` 同上，保留官方写法
 *   · `/goal clear`       清除目标
 *   · `/goal`             只看当前目标（Heta 侧回一句提示）
 *
 * 认不出来的斜杠行**返回 null**：那种行当普通文本发出去（`/goal pause` 就这样 —— 目标域的
 * pause/resume 归模型的 `update_goal` 工具管，用户直接说这句话正是它要的"人的直接请求"）。
 */
internal sealed interface DshSlashCommand {
    /** 计划模式：`enabled` 就是要收敛到的意图。 */
    data class Plan(val enabled: Boolean) : DshSlashCommand

    /** 目标：`objective` 为 null 表示清除。 */
    data class Goal(val objective: String?) : DshSlashCommand

    /** `/goal`：Heta 侧没有 dsh 那种"回一句目标详情"的通道，改为提示当前记下的目标。 */
    data object ShowGoal : DshSlashCommand
}

/** 一条命令 + 命令之后剩下的正文（`/plan 先看一眼 X` 里的 `先看一眼 X`）。 */
internal data class DshSlashInvocation(
    val command: DshSlashCommand,
    val rest: String,
)

internal object DshSlashCommands {
    /**
     * 解析一行斜杠命令。不是命令（不以 `/` 开头、命令名不认识、语法不完整）时返回 null。
     *
     * 命令名大小写不敏感（`/PLAN` 也算），与 dsh 的 parseCommand 一致；正文原样保留 ——
     * 目标是一句人写的话，只去掉首尾空白（目标域要求 normalized）。
     */
    fun parse(input: String): DshSlashInvocation? {
        val line = input.trim()
        if (!line.startsWith('/')) return null
        val body = line.substring(1)
        val name = body.substringBefore(' ').substringBefore('\n').lowercase()
        val rest = body.substringAfter(' ', "").trim()
        return when (name) {
            "plan" -> when {
                rest.equals("off", ignoreCase = true) ->
                    DshSlashInvocation(DshSlashCommand.Plan(false), "")
                rest.isEmpty() -> DshSlashInvocation(DshSlashCommand.Plan(true), "")
                // `/plan <文本>`：进计划模式，同时把这句话交给模型（dsh 的 steer 语义）。
                else -> DshSlashInvocation(DshSlashCommand.Plan(true), rest)
            }

            "goal" -> when {
                rest.isEmpty() -> DshSlashInvocation(DshSlashCommand.ShowGoal, "")
                rest.equals("clear", ignoreCase = true) ->
                    DshSlashInvocation(DshSlashCommand.Goal(null), "")
                // `pause` / `resume` 归模型的 `update_goal` 工具：目标域要求"人的直接请求"，
                // 用户敲的这句话正是 —— 原样发给模型比 Heta 自己猜一个 phase 意图更准。
                rest.equals("pause", ignoreCase = true) ||
                    rest.equals("resume", ignoreCase = true) -> null
                // `/goal edit <目标>`：官方写法；`edit` 后面没东西是语法错，交给模型去理解。
                rest.startsWith("edit ", ignoreCase = true) -> {
                    val objective = rest.substring(4).trim()
                    if (objective.isEmpty()) {
                        null
                    } else {
                        DshSlashInvocation(DshSlashCommand.Goal(objective), "")
                    }
                }

                // `/goal edit` 后面什么都没有：语法不完整，不猜成目标 "edit"。
                rest.equals("edit", ignoreCase = true) -> null
                else -> DshSlashInvocation(DshSlashCommand.Goal(rest), "")
            }

            else -> null
        }
    }
}
