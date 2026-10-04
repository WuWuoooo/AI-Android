package com.ai.android.agent

import com.ai.android.model.AgentState
import com.ai.android.model.ChatMessage
import com.ai.android.model.Conversation
import com.ai.android.model.TokenStats
import com.ai.android.model.ToolCall
import com.ai.android.model.ToolResult
import com.ai.android.provider.ProviderManager
import com.ai.android.provider.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

sealed class AgentEvent {
    data class ReasoningDelta(val text: String) : AgentEvent()
    data class ContentDelta(val text: String) : AgentEvent()
    data class MessageDone(val message: ChatMessage) : AgentEvent()
    data class ToolStart(val call: ToolCall) : AgentEvent()
    data class ToolEnd(val result: ToolResult) : AgentEvent()
    data class ToolMessage(val message: ChatMessage) : AgentEvent()
    data class Stats(val stats: TokenStats) : AgentEvent()
    data class State(val state: AgentState) : AgentEvent()
    data class Error(val msg: String) : AgentEvent()
    data object Done : AgentEvent()
}

data class AgentConfig(
    val maxRounds: Int = 30,
    val temperature: Float = 0.3f,
    val reasoningEffort: String = "",
    val systemPromptExtra: String = "",
    val networkRetries: Int = 2,
)

class AgentCore(
    private val providers: ProviderManager,
    private val registry: ToolRegistry,
    private val config: () -> AgentConfig = { AgentConfig() },
    private val skillPromptProvider: (String) -> String = { "" },
) {

    fun run(conversation: Conversation): Flow<AgentEvent> = channelFlow {
        val cfg = config()
        var round = 0
        try {
            while (true) {
                round++
                if (round > cfg.maxRounds) {
                    send(AgentEvent.Error("已达到最大循环次数（${cfg.maxRounds}），停止执行"))
                    send(AgentEvent.State(AgentState(status = AgentState.Status.COMPLETED)))
                    break
                }

                val provider = providers.active
                    ?: throw IllegalStateException("没有可用的 Provider，请先到「设置」中填入 API Key")

                send(AgentEvent.State(AgentState(
                    status = AgentState.Status.THINKING,
                    progressText = "思考中（第 $round 轮）",
                    startedAt = System.currentTimeMillis(),
                )))

                val request = buildRequest(conversation, cfg)
                val content = StringBuilder()
                val reasoning = StringBuilder()
                var toolCalls: List<ToolCall> = emptyList()
                var stats: TokenStats? = null
                var streamError: String? = null

                var attempt = 0
                while (true) {
                    content.clear(); reasoning.clear()
                    toolCalls = emptyList(); stats = null; streamError = null

                    var gotAny = false
                    provider.chatStream(request, providers.buildTools(), cfg.reasoningEffort, cfg.temperature)
                        .collect { ev ->
                            when (ev) {
                                is StreamEvent.Reasoning -> {
                                    gotAny = true; reasoning.append(ev.text)
                                    send(AgentEvent.ReasoningDelta(ev.text))
                                }
                                is StreamEvent.Content -> {
                                    gotAny = true; content.append(ev.text)
                                    send(AgentEvent.ContentDelta(ev.text))
                                }
                                is StreamEvent.ToolCalls -> { gotAny = true; toolCalls = ev.calls }
                                is StreamEvent.Stats -> { stats = ev.stats; send(AgentEvent.Stats(ev.stats)) }
                                is StreamEvent.Error -> streamError = ev.msg
                                StreamEvent.Done -> Unit
                            }
                        }

                    val err = streamError
                    if (err == null) break
                    if (gotAny || attempt >= cfg.networkRetries) {
                        send(AgentEvent.Error(err))
                        send(AgentEvent.State(AgentState(status = AgentState.Status.ERROR)))
                        send(AgentEvent.Done)
                        return@channelFlow
                    }
                    attempt++
                    delay(800L * attempt)
                }

                val assistantMsg = ChatMessage(
                    role = ChatMessage.Role.ASSISTANT,
                    content = content.toString(),
                    reasoning = reasoning.toString(),
                    toolCalls = toolCalls,
                    tokenStats = stats,
                )
                conversation.addMessage(assistantMsg)
                send(AgentEvent.MessageDone(assistantMsg))

                if (toolCalls.isEmpty()) {
                    send(AgentEvent.State(AgentState(status = AgentState.Status.COMPLETED)))
                    break
                }

                for (call in toolCalls) {
                    send(AgentEvent.State(AgentState(
                        status = AgentState.Status.TOOL_CALLING,
                        currentTool = call.name,
                        progressText = "执行工具: ${call.name}",
                        startedAt = System.currentTimeMillis(),
                    )))
                    send(AgentEvent.ToolStart(call))
                    val result = registry.execute(call)
                    send(AgentEvent.ToolEnd(result))

                    val toolMsg = ChatMessage(
                        role = ChatMessage.Role.TOOL,
                        content = result.output,
                        toolResults = listOf(result),
                    )
                    conversation.addMessage(toolMsg)
                    send(AgentEvent.ToolMessage(toolMsg))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(AgentEvent.Error(e.message ?: e.javaClass.simpleName))
            send(AgentEvent.State(AgentState(status = AgentState.Status.ERROR)))
        }
        send(AgentEvent.Done)
    }

    /** 用 AI 总结会话标题（异步，不阻塞主流程） */
    suspend fun summarizeTitle(userText: String, assistantText: String): String {
        val provider = providers.active ?: return userText.take(16)
        val prompt = """
请用不超过 10 个字总结下面这段对话的主题，直接输出标题，不要标点、不要引号、不要解释。

用户: ${userText.take(200)}
助手: ${assistantText.take(300)}
        """.trimIndent()
        return runCatching {
            provider.chatOnce(listOf(ChatMessage.user(prompt)), temperature = 0.1f).trim()
                .take(20).ifBlank { userText.take(16) }
        }.getOrDefault(userText.take(16))
    }

    /** 从当前对话末尾继续生成 */
    suspend fun continueFrom(conversation: Conversation): String {
        val provider = providers.active ?: throw IllegalStateException("没有可用的 Provider")
        val msgs = conversation.messages.toMutableList()

        val lastAssistantIdx = msgs.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (lastAssistantIdx < 0) return ""

        val lastContent = msgs[lastAssistantIdx].content
        val continuePrompt = ChatMessage.system(
            "你之前已经输出了以下内容（不要重复）：\n\n$lastContent\n\n" +
                "请从断掉的地方继续输出，不要重复已有内容，不要加任何前缀说明。"
        )
        val req = listOf(
            ChatMessage.system(buildSystemPrompt("", "", "")),
            continuePrompt,
            ChatMessage.user("请继续"),
        )
        val out = provider.chatOnce(req, temperature = 0.3f)
        return out.trim().removePrefix(lastContent.takeLast(50)).trim()
    }

    private fun buildRequest(conversation: Conversation, cfg: AgentConfig): List<ChatMessage> {
        val history = conversation.messages.toList()
        val sysExtra = history.filter { it.role == ChatMessage.Role.SYSTEM }
            .joinToString("\n") { it.content }
        val lastUser = history.lastOrNull { it.role == ChatMessage.Role.USER }?.content.orEmpty()
        val skillPrompt = runCatching { skillPromptProvider(lastUser) }.getOrDefault("")
        val rest = history
            .filter { it.role != ChatMessage.Role.SYSTEM }
            .filter { !(it.role == ChatMessage.Role.ASSISTANT && it.content.isBlank() && it.toolCalls.isEmpty() && it.reasoning.isBlank()) }
            .filter { !(it.role == ChatMessage.Role.TOOL && it.content.isBlank()) }
        return buildList {
            add(ChatMessage.system(buildSystemPrompt(cfg.systemPromptExtra, sysExtra, skillPrompt)))
            addAll(rest)
        }
    }

    private fun buildSystemPrompt(extra: String, userExtra: String, skillPrompt: String): String {
        val base = """
你是「AI Android」——运行在 Android 手机上的智能助手 Agent，可以调用工具帮用户完成真实任务。

【工具使用铁律】
1. 调用任何工具前，先确认必填参数都已提供。参数示例写在工具描述里，直接参考。
2. 如果工具报错信息说"缺少必填参数 xxx"，请补齐参数后**重试一次**，不要原样重复调用。
3. 修改文件前必须先用 read_file 确认内容；改局部用 edit_file，不要整文件覆写。
4. **危险操作必须先 ask_user 征得同意**：删除文件/目录、run_shell_command 中带 rm/sudo/mkfs/dd、
   支付、发消息、修改系统设置、发送任何用户数据到外部。
5. 工具报错时先读懂错误再调整，不要用相同参数重试。
6. 同类工具二选一：
   - 短命令（<60s）→ run_shell_command；长任务/装包/跑 Python → terminal_exec
   - 一眼看清屏幕 → accessibility_control action=screen；需要视觉细节 → action=screenshot
7. 回复用中文，简洁直接，不复述工具原始输出。
8. 不确定的信息先用 web_search，不要编造。
9. 文件默认根目录 /sdcard。

当前设备：Android 手机（无 root），工作目录 /sdcard。
        """.trimIndent()
        val sb = StringBuilder(base)
        if (userExtra.isNotBlank()) sb.append("\n\n【用户设定的长期要求】\n").append(userExtra)
        if (extra.isNotBlank()) sb.append("\n\n").append(extra)
        if (skillPrompt.isNotBlank()) sb.append("\n\n【本次任务匹配到的技能指引】\n").append(skillPrompt)
        return sb.toString()
    }
}