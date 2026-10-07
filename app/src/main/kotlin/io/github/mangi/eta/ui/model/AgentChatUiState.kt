package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.model.ReasoningEffort

@Immutable
internal data class AgentChatUiState(
    val messages: List<AgentChatMessageUi>,
    val history: List<AgentModelClient.ConversationMessage> = emptyList(),
    // 完整脱敏历史独立于模型投影；摘要替换 history 时不覆盖 journal。
    val journal: List<AgentModelClient.ConversationMessage> = emptyList(),
    val input: String,
    val isStreaming: Boolean,
    val isCompacting: Boolean = false,
    val thinkingEnabled: Boolean,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.fromLegacy(thinkingEnabled),
    val availableReasoningEfforts: List<ReasoningEffort> = emptyList(),
    val pendingImages: List<PendingImageUi> = emptyList(),
    val pendingFileReferences: List<PendingFileReferenceUi> = emptyList(),
    val appliedRuntimeRunIds: List<String> = emptyList(),
    val messageEdit: MessageEditUiState? = null,
    val roleplay: RoleplayBinding? = null,
    val roleplayMessages: RoleplayMessageState = RoleplayMessageState(),
) {
    val canCompactContext: Boolean get() = !isStreaming && messageEdit == null && history.any {
        !it.contextSummary && (it.role == "assistant" || it.role == "tool")
    }
}

@Immutable
sealed interface AgentChatMessageUi {
    val id: String
}

@Immutable
data class UserMessageUi(
    override val id: String,
    val content: String,
    val images: List<String> = emptyList(),
    val isEdited: Boolean = false,
) : AgentChatMessageUi

@Immutable
data class AgentMessageUi(
    override val id: String,
    val content: String,
    val isStreaming: Boolean = false,
    val renderMarkdown: Boolean = true,
    val usage: TokenUsageUi? = null,
    val characterEditable: Boolean = false,
    val candidateCount: Int = 1,
    val selectedCandidate: Int = 0,
) : AgentChatMessageUi

enum class SystemNoticeCode(val wireValue: String) {
    Stopped("stopped"),
    EmptyResult("empty_result"),
    RuntimeFailed("runtime_failed"),
    ModelRetry("model_retry"),
    ContextCompaction("context_compaction"),
    Interrupted("interrupted");

    companion object {
        fun fromWireValue(value: String): SystemNoticeCode? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

/** Eta 自己生成的消息只保存稳定状态码，展示时再按当前语言解析。 */
@Immutable
data class SystemNoticeMessageUi(
    override val id: String,
    val code: SystemNoticeCode,
    val detail: String? = null,
    val contextTokens: Int? = null,
    /** 仅运行期存在的进行中标记，不随消息持久化；恢复的历史通知始终视为已结束。 */
    val running: Boolean = false,
) : AgentChatMessageUi

@Immutable
data class TokenUsageUi(
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    /** 缓存**命中**（读）的 token。 */
    val cachedTokens: Int? = null,
    /**
     * 缓存**写入**的 token（dsh 那条会话报得出来；原生那条路是 null）。
     *
     * 与 [cachedTokens] 分开是有意的：写缓存也要钱（有的供应商比读贵得多），合成一个数就看不出
     * "这一轮到底是命中还是重建了缓存"。
     */
    val cacheWriteTokens: Int? = null,
    /** 模型报的整轮总量；与"输入 + 输出"不一致时界面才单独写出来。 */
    val totalTokens: Int? = null,
    /** dsh 的轮次与步数（原生那条路没有这两个数，界面就不写这两段）。 */
    val turn: Int? = null,
    val steps: Int? = null,
    /** 时间口径（照客户端 deriveStats）：模型用时 / 工具用时 / TTFT / 解码时长与 token。 */
    val llmMs: Long? = null,
    val toolMs: Long? = null,
    val ttftMs: Long? = null,
    val ttftSteps: Int? = null,
    val decodeMs: Long? = null,
    val decodeTokens: Int? = null,
    /** 这一轮结束的墙上时间（毫秒）：回复动作行里那个时间戳。 */
    val finishedAtMs: Long? = null,
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
            decodeTokens == null &&
            finishedAtMs == null
}

@Immutable
data class ThinkingMessageUi(
    override val id: String,
    val content: String,
    val isStreaming: Boolean,
    val elapsedSeconds: Int? = null,
    val collapsed: Boolean = false,
) : AgentChatMessageUi

/**
 * 首页的 Run trace 入口卡片：展示 Agent 当前可调用的能力分组。
 */
@Immutable
data class RunTraceMessageUi(
    override val id: String,
    val capabilities: List<CapabilityUi>,
) : AgentChatMessageUi

@Immutable
data class CapabilityUi(
    val title: String,
    val items: List<String>,
)

/**
 * 工具调用摘要：出现在消息流中，显示当前/最近一步调用了哪些工具。
 */
@Immutable
data class ToolSummaryMessageUi(
    override val id: String,
    val tools: List<String>,
) : AgentChatMessageUi

@Immutable
data class ToolActivityMessageUi(
    override val id: String,
    val toolName: String,
    val status: ToolActivityStatusUi,
    val argumentsSummary: String,
    val command: String? = null,
    val resultSummary: String? = null,
    val imageCount: Int = 0,
) : AgentChatMessageUi

enum class ToolActivityStatusUi {
    Running,
    Success,
    Failed,
    Unknown,
}

/**
 * 建议语 chip 行。
 */
@Immutable
data class SuggestionChipsMessageUi(
    override val id: String,
    val prompts: List<String>,
) : AgentChatMessageUi

@Immutable
data class PendingImageUi(
    val id: String,
    val uri: String,
    val dataUrl: String,
    val mimeType: String,
)

@Immutable
data class PendingFileReferenceUi(
    val id: String,
    val reference: AgentFileReference,
)

@Immutable
data class MessageEditUiState(
    val targetMessageId: String,
    val previousInput: String,
    val previousImages: List<PendingImageUi>,
    val previousFileReferences: List<PendingFileReferenceUi>,
    val hasLaterTurns: Boolean,
    val preserveFollowingMessages: Boolean = false,
)
