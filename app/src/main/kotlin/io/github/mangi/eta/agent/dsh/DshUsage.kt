package io.github.mangi.eta.agent.dsh

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「这一轮花了多少」—— dsh 会话插件（`heta-usage.mjs`）写出来的那份账。
 *
 * 为什么 App 要读它：ACP 那条通道的用量出口只有 `usage_update`，带的是 `used`（当前上下文占用
 * 量）与 `size`（窗口），值是 `tokenMeter.measure(session).totalTokens` —— **估算的上下文压力**，
 * 不是模型报回来的账。cache 命中、输入、输出、轮次、步数在 ACP 上一个字段都没有，而那份账在
 * 会话进程里就有（`session/event` 的 `assistant/message` 带 `usage`，见 heta-usage.mjs）。
 *
 * 口径（与插件一致，也是各家用量界面的口径）：
 *   · 一轮 = 一条用户消息到一次回复收尾（dsh 的 `turn`）；
 *   · [steps] = 这一轮走到第几步（dsh 的 `step`，每次模型调用一步）；
 *   · [inputTokens] / [cacheReadTokens] / [outputTokens] 是这一轮**各步之和** —— 每一步的
 *     `inputTokens` 都是那一步整段请求的大小，加起来才是"这一轮一共花了多少"。
 *
 * 字段名与 `TokenUsage`（`@deepseek-ai/dsh-llm`）逐字对应，App 侧一个映射就够。
 */
internal data class DshTurnUsage(
    val turn: Int,
    val steps: Int,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val cacheReadTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val totalTokens: Int? = null,
    /** 这一轮最后一次写入的时间（毫秒）；界面不用它，排查时用。 */
    val at: Long = 0L,
)

/** 一个会话的若干轮（新到旧都有，界面只取最新的那一轮）。 */
internal data class DshUsageSession(
    val id: String,
    val at: Long,
    val turns: List<DshTurnUsage>,
) {
    /** 这个会话最新的那一轮；没有轮次就是 null。 */
    val latestTurn: DshTurnUsage? get() = turns.maxByOrNull { it.turn }
}

/** 一份用量快照（插件只留最近几个会话、每个会话最近若干轮）。 */
internal data class DshUsageSnapshot(
    val at: Long,
    val sessions: List<DshUsageSession>,
)

/**
 * 解析插件写出来的那一行：`HETA-USAGE-JSON:` + JSON。
 *
 * 与清单那套同样的底线：**绝不抛异常给调用方**。读不动就是 null（界面少一行用量），而不是让
 * 一条 ACP 事件把整轮对话带崩。
 */
internal object DshUsageCodec {

    /** 与 `heta-usage.mjs` 里的 MARKER 是同一个字符串。 */
    const val MARKER = "HETA-USAGE-JSON:"

    private const val SESSIONS = "sessions"
    private const val TURNS = "turns"
    private const val ID = "id"
    private const val AT = "at"
    private const val TURN = "turn"
    private const val STEPS = "steps"
    private const val INPUT = "inputTokens"
    private const val OUTPUT = "outputTokens"
    private const val CACHE_READ = "cacheReadTokens"
    private const val CACHE_WRITE = "cacheWriteTokens"
    private const val REASONING = "reasoningTokens"
    private const val TOTAL = "totalTokens"

    fun parse(text: String): DshUsageSnapshot? {
        val marker = text.indexOf(MARKER)
        if (marker < 0) return null
        val line = text.substring(marker + MARKER.length).lineSequence().first()
        val payload = runCatching { JSONObject(line.trim()) }.getOrNull() ?: return null
        val at = payload.optLong(AT, 0L)
        val sessions = payload.optJSONArray(SESSIONS) ?: return DshUsageSnapshot(at, emptyList())
        return DshUsageSnapshot(at, readSessions(sessions))
    }

    private fun readSessions(array: JSONArray): List<DshUsageSession> {
        val sessions = ArrayList<DshUsageSession>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            // 没有 id 的会话在界面上没法寻址（App 要按自己的会话挑），直接丢这一条。
            val id = item.optString(ID).takeIf { it.isNotBlank() } ?: continue
            sessions += DshUsageSession(
                id = id,
                at = item.optLong(AT, 0L),
                turns = readTurns(item.optJSONArray(TURNS)),
            )
        }
        return sessions
    }

    private fun readTurns(array: JSONArray?): List<DshTurnUsage> {
        if (array == null) return emptyList()
        val turns = ArrayList<DshTurnUsage>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            // 单条坏行只丢这一条：一个畸形轮次不该把整份用量打掉。
            val turn = intOrNull(item, TURN) ?: continue
            turns += DshTurnUsage(
                turn = turn,
                steps = intOrNull(item, STEPS) ?: 0,
                inputTokens = intOrNull(item, INPUT),
                outputTokens = intOrNull(item, OUTPUT),
                cacheReadTokens = intOrNull(item, CACHE_READ),
                cacheWriteTokens = intOrNull(item, CACHE_WRITE),
                reasoningTokens = intOrNull(item, REASONING),
                totalTokens = intOrNull(item, TOTAL),
                at = item.optLong(AT, 0L),
            )
        }
        return turns
    }

    private fun intOrNull(json: JSONObject, key: String): Int? {
        if (!json.has(key) || json.isNull(key)) return null
        return when (val raw = json.opt(key)) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }
    }
}

/**
 * 读**共享那份**用量文件（`<rootfs>/opt/dsh/heta-usage.json`）。
 *
 * 为什么只读这一份、不走 su：这个读发生在 **ACP 的读循环**上（每个 step 的 `usage_update` 一次），
 * 而 `su` 那条路最坏能卡到 15 秒 —— 卡住读循环就是卡住整条 ACP 流（连"停止"都按不动）。插件把
 * 共享那份写成 0644、目录 0755（同 heta-status 的理由），普通文件读就够，毫秒级。
 *
 * 读不到就当这一轮没有用量：界面少一行，**不撒谎**（绝不拿上一次的账冒充这一轮）。
 */
internal object DshUsageReader {

    const val SHARED_RELATIVE = "opt/dsh/heta-usage.json"

    fun read(rootfsPath: String): DshUsageSnapshot? {
        if (rootfsPath.isBlank()) return null
        val text = runCatching { File(rootfsPath, SHARED_RELATIVE).readText() }.getOrNull() ?: return null
        return DshUsageCodec.parse(text)
    }

    /**
     * 这个会话最新那一轮。
     *
     * [sessionId] 是 dsh 的会话 id（ACP `session/new` 返回的那个，与 `session.id` 同一个）——
     * 按它挑，多开几个会话也不会串。id 对不上（插件刚写、会话刚建）时退回"最近活跃的那个会话"：
     * 宁可显示一条稍微不确定的用量，也不要因为一次时序差就整轮没有。
     *
     * [notBefore] 是**本回合开始的时刻**：每个回合都是一个新进程，插件的内存是空的，文件里在
     * 那一刻还是**上一轮**那份 —— 只认这之后写过的轮次，才能保证读到的是这一轮的账（真机上一轮
     * 一进程，这个坑一定会踩到）。传 0 表示不设闸门（单测与排查用）。
     */
    fun latestTurn(rootfsPath: String, sessionId: String?, notBefore: Long = 0L): DshTurnUsage? {
        val snapshot = read(rootfsPath) ?: return null
        val session = sessionId
            ?.takeIf { it.isNotBlank() }
            ?.let { id -> snapshot.sessions.firstOrNull { it.id == id } }
            ?: snapshot.sessions.maxByOrNull { it.at }
        return session?.turns.orEmpty().filter { it.at >= notBefore }.maxByOrNull { it.turn }
    }
}
