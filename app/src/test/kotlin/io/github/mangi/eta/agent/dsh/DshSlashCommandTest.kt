package io.github.mangi.eta.agent.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 斜杠命令的解析表。
 *
 * 为什么这一层值得单测：它决定"用户敲的这一行到底进不进模型"。认错一条（例如把 `/goal pause`
 * 当成目标原文）就会给会话设一个叫 "pause" 的目标，而且**没有任何界面会提示这件事**——
 * 下一轮 run 才在后台生效。语法对照的是 dsh-plan-mode 的 handler 与 dsh-command-goal 的
 * parseGoalCommand（两个文件都逐行核过）。
 */
class DshSlashCommandTest {
    @Test
    fun planEntersAndLeavesPlanMode() {
        assertEquals(
            DshSlashInvocation(DshSlashCommand.Plan(true), ""),
            DshSlashCommands.parse("/plan"),
        )
        assertEquals(
            DshSlashInvocation(DshSlashCommand.Plan(false), ""),
            DshSlashCommands.parse("/plan off"),
        )
        // 大小写不敏感，前后空白不管。
        assertEquals(
            DshSlashInvocation(DshSlashCommand.Plan(true), ""),
            DshSlashCommands.parse("  /PLAN  "),
        )
    }

    @Test
    fun planWithTextEntersPlanModeAndKeepsTheTextAsThePrompt() {
        assertEquals(
            DshSlashInvocation(DshSlashCommand.Plan(true), "先看一眼这个 bug"),
            DshSlashCommands.parse("/plan 先看一眼这个 bug"),
        )
        // `off` 只有在**整段正文就是 off** 时才算退出：`/plan off by accident` 是正文。
        assertEquals(
            DshSlashInvocation(DshSlashCommand.Plan(true), "off by accident"),
            DshSlashCommands.parse("/plan off by accident"),
        )
    }

    @Test
    fun goalCreatesEditsClearsAndShows() {
        assertEquals(
            DshSlashInvocation(DshSlashCommand.Goal("把 README 的用法一节补上"), ""),
            DshSlashCommands.parse("/goal 把 README 的用法一节补上"),
        )
        assertEquals(
            DshSlashInvocation(DshSlashCommand.Goal("换一个目标"), ""),
            DshSlashCommands.parse("/goal edit 换一个目标"),
        )
        assertEquals(
            DshSlashInvocation(DshSlashCommand.Goal(null), ""),
            DshSlashCommands.parse("/goal clear"),
        )
        assertEquals(
            DshSlashInvocation(DshSlashCommand.ShowGoal, ""),
            DshSlashCommands.parse("/goal"),
        )
    }

    @Test
    fun unknownOrIncompleteLinesGoToTheModelAsPlainText() {
        // 不是命令。
        assertNull(DshSlashCommands.parse("看一下 /plan 是怎么实现的"))
        assertNull(DshSlashCommands.parse(""))
        assertNull(DshSlashCommands.parse("/unknown thing"))
        // `/goal pause|resume` 归模型的 update_goal 工具（目标域要求"人的直接请求"，这句话正是）。
        assertNull(DshSlashCommands.parse("/goal pause"))
        assertNull(DshSlashCommands.parse("/goal resume"))
        // `/goal edit` 后面什么都没有：语法不完整，不要猜成目标。
        assertNull(DshSlashCommands.parse("/goal edit"))
    }

    @Test
    fun goalObjectiveKeepsInnerSpacesButDropsOuterOnes() {
        val parsed = DshSlashCommands.parse("/goal   写 一个   README   ")
        assertTrue(parsed?.command is DshSlashCommand.Goal)
        assertEquals("写 一个   README", (parsed?.command as DshSlashCommand.Goal).objective)
    }

    @Test
    fun planModeRestBodyKeepsItsShape() {
        // 正文里的换行与空格原样带着（模型看到的就是用户写的）。
        val parsed = DshSlashCommands.parse("/plan 第一行\n第二行")
        assertEquals("第一行\n第二行", parsed?.rest)
    }
}
