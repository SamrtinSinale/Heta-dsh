package io.github.mangi.eta.agent.runtime

internal data class AgentTokenUsage(
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    /** 缓存**命中**（读）的 token：原生那条路叫 cached，dsh 那边叫 cacheRead。 */
    val cachedTokens: Int? = null,
    /**
     * 缓存**写入**的 token（dsh 的 `cacheWriteTokens`）。
     *
     * 与 [cachedTokens] 分开：写缓存也要钱（Anthropic 那条路上比读贵得多），合成一个数就看不
     * 出来"这一轮到底是命中还是重建了缓存"。原生那条路不报这个值，所以它是 null。
     */
    val cacheWriteTokens: Int? = null,
    /** 模型报的整轮总量（dsh 的 `totalTokens`）；原生那条路用的是 [contextTokens]。 */
    val totalTokens: Int? = null,
    /** dsh 的轮次与步数（只有 dsh 这条会话报得出来）：界面上"第几轮 / 走了几步"。 */
    val turn: Int? = null,
    val steps: Int? = null,
    /** 时间口径（照客户端 deriveStats）：模型用时 / 工具用时 / TTFT / 解码时长与 token（算 TPS）。 */
    val llmMs: Long? = null,
    val toolMs: Long? = null,
    val ttftMs: Long? = null,
    val ttftSteps: Int? = null,
    val decodeMs: Long? = null,
    val decodeTokens: Int? = null,
) {
    val isEmpty: Boolean
        get() = contextTokens == null &&
            inputTokens == null &&
            outputTokens == null &&
            reasoningTokens == null &&
            cachedTokens == null &&
            cacheWriteTokens == null &&
            totalTokens == null &&
            turn == null &&
            steps == null &&
            llmMs == null &&
            toolMs == null &&
            ttftMs == null &&
            ttftSteps == null &&
            decodeMs == null &&
            decodeTokens == null
}
