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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/** Agent 运行时事件（供 UI 订阅） */
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
    object Done : AgentEvent()
}

/** Agent 运行配置 */
data class AgentConfig(
    val maxRounds: Int = 30,
    val temperature: Float = 0.3f,
    val reasoningEffort: String = "",
    val systemPromptExtra: String = "",
)

/**
 * Agent 核心循环：
 * reasoning → content → tool_calls → 执行工具 → 结果回灌 → 下一轮，直到无工具调用。
 */
class AgentCore(
    private val providers: ProviderManager,
    private val registry: ToolRegistry,
    private val config: () -> AgentConfig = { AgentConfig() },
    /** 根据用户最新输入匹配技能提示（Skills 系统） */
    private val skillPromptProvider: (String) -> String = { "" },
) {

    fun run(conversation: Conversation): Flow<AgentEvent> = channelFlow {
        val cfg = config()
        var round = 0
        try {
            while (true) {
                round++
                if (round > cfg.maxRounds) {
                    send(AgentEvent.Error("已达到最大循环次数（${cfg.maxRounds}），停止执行喵"))
                    send(AgentEvent.State(AgentState(status = AgentState.Status.COMPLETED)))
                    break
                }

                val provider = providers.active
                    ?: throw IllegalStateException("没有可用的 Provider，请先到「设置」中填入 API Key")

                send(
                    AgentEvent.State(
                        AgentState(
                            status = AgentState.Status.THINKING,
                            progressText = "思考中（第 $round 轮）",
                            startedAt = System.currentTimeMillis(),
                        )
                    )
                )

                val request = buildRequest(conversation, cfg)
                val content = StringBuilder()
                val reasoning = StringBuilder()
                var toolCalls: List<ToolCall> = emptyList()
                var stats: TokenStats? = null
                var streamError: String? = null

                provider.chatStream(request, providers.buildTools(), cfg.reasoningEffort, cfg.temperature)
                    .collect { ev ->
                        when (ev) {
                            is StreamEvent.Reasoning -> {
                                reasoning.append(ev.text)
                                send(AgentEvent.ReasoningDelta(ev.text))
                            }
                            is StreamEvent.Content -> {
                                content.append(ev.text)
                                send(AgentEvent.ContentDelta(ev.text))
                            }
                            is StreamEvent.ToolCalls -> toolCalls = ev.calls
                            is StreamEvent.Stats -> {
                                stats = ev.stats
                                send(AgentEvent.Stats(ev.stats))
                            }
                            is StreamEvent.Error -> streamError = ev.msg
                            StreamEvent.Done -> Unit
                        }
                    }

                val err = streamError
                if (err != null && content.isEmpty() && toolCalls.isEmpty()) {
                    send(AgentEvent.Error(err))
                    send(AgentEvent.State(AgentState(status = AgentState.Status.ERROR)))
                    break
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
                    send(
                        AgentEvent.State(
                            AgentState(
                                status = AgentState.Status.TOOL_CALLING,
                                currentTool = call.name,
                                progressText = "执行工具: ${call.name}",
                                startedAt = System.currentTimeMillis(),
                            )
                        )
                    )
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

    /** 组装发送给模型的消息：系统提示词 + 历史 */
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

工具使用原则：
1. 完成任务优先：能一步做的不要拆多步；多步任务按顺序调用工具。
2. 修改文件前先 read_file 确认内容；改局部用 edit_file 精确替换，不要整文件覆写。
3. 删除文件、支付、密码等敏感操作前，必须先用 ask_user 征得用户同意。
4. 工具报错时先读懂错误信息再调整参数重试，不要用相同参数重复调用。
5. 回复用中文，简洁直接，不要复述工具的原始输出。
6. 不确定的信息先用 web_search 查询，不要编造答案。
7. 文件默认根目录 /sdcard；长任务、装库、跑 Python 用 terminal 工具。

当前设备：Android 手机（无 root），工作目录 /sdcard。
        """.trimIndent()
        val sb = StringBuilder(base)
        if (userExtra.isNotBlank()) sb.append("\n\n【用户设定的长期要求】\n").append(userExtra)
        if (extra.isNotBlank()) sb.append("\n\n").append(extra)
        if (skillPrompt.isNotBlank()) sb.append("\n\n【本次任务匹配到的技能指引】\n").append(skillPrompt)
        return sb.toString()
    }
}
