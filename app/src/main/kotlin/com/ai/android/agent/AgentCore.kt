package com.ai.android.agent

import android.util.Log
import com.ai.android.model.AgentState
import com.ai.android.model.ChatMessage
import com.ai.android.model.Conversation
import com.ai.android.model.TokenStats
import com.ai.android.model.ToolCall
import com.ai.android.model.ToolResult
import com.ai.android.provider.ProviderManager
import com.ai.android.provider.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    /** 流式/HTTP 失败后的自动重试次数（0 = 禁用） */
    val networkRetries: Int = 2,
    /** 上下文自动压缩：每隔 N 轮把旧轮次总结成摘要（0 = 禁用） */
    val contextCompressRounds: Int = 0,
)

class AgentCore(
    private val providers: ProviderManager,
    private val registry: ToolRegistry,
    private val config: () -> AgentConfig = { AgentConfig() },
        /** ⭐ 每次请求时注入长期记忆到系统提示词（含该对话所属项目的独立记忆）。
     *  传 [Conversation] 以便按 projectId 取项目记忆。 */
    private val memoryPromptProvider: (Conversation) -> String = { "" },
) {

    /** 上下文自动压缩状态（单次 run 内有效） */
    private class CompState(var count: Int = 0, var summary: String = "")

    fun run(conversation: Conversation): Flow<AgentEvent> = channelFlow {
        val cfg = config()
        var round = 0
        val comp = CompState()
        try {
            while (true) {
                                round++
                if (cfg.maxRounds > 0 && round > cfg.maxRounds) {
                    send(AgentEvent.Error("已达到最大循环次数（${cfg.maxRounds}），停止执行"))
                    send(AgentEvent.State(AgentState(status = AgentState.Status.COMPLETED)))
                    break
                }
                val provider = providers.active
                    ?: throw IllegalStateException("没有可用的 Provider，请先到「设置」中填入 API Key")

                // ⭐ 上下文自动压缩：每满 N 轮，把较旧的轮次总结成摘要，替换掉旧上下文（不改动 UI 消息列表）
                if (cfg.contextCompressRounds > 0 && round > 1 &&
                    (round - 1) % cfg.contextCompressRounds == 0
                ) {
                    runCatching { compressHistory(provider, conversation, comp) }
                }

                send(AgentEvent.State(AgentState(
                    status = AgentState.Status.THINKING,
                    progressText = "思考中（第 $round 轮）",
                    startedAt = System.currentTimeMillis(),
                )))

                                                                var request = buildRequest(conversation, cfg, comp)
                val content = StringBuilder()
                val reasoning = StringBuilder()
                var toolCalls: List<ToolCall> = emptyList()
                var stats: TokenStats? = null
                var streamError: String? = null
                                                var finishReason: String = ""   // 跟踪 finish_reason
                                var autoContinueRounds = 0      // 自动续写轮次（上限 6）

                var attempt = 0
                while (true) {
                    content.clear(); reasoning.clear()
                    toolCalls = emptyList(); stats = null; streamError = null; finishReason = ""

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
                                is StreamEvent.ToolCalls -> { toolCalls = ev.calls }
                                is StreamEvent.Stats -> { stats = ev.stats; send(AgentEvent.Stats(ev.stats)) }
                                is StreamEvent.Error -> streamError = ev.msg
                                is StreamEvent.FinishInfo -> finishReason = ev.reason
                                StreamEvent.Done -> Unit
                            }
                        }

                                                            // ⭐ 静默停修复（v2，最终版）：分两类"没说完"信号判定是否自动续写——
                    //  ① 显式截断（finish_reason=length/max_tokens）→ 必续
                    //  ② 国产兼容服务（DeepSeek/Kimi/通义）**正常写完也常不发 finish_reason（空串）**：
                    //     本轮正文 >= 300 字才认为"没说完"→ 续写；模型收到"请继续"后若产出很短（<300）
                    //     说明其实已说完 → 自然早停，避免反复空转（这就是"看着像又停了"的根因）
                    //  ③ 正常结束（stop/end_turn/stop_sequence）/ 工具调用（tool_calls/tool_use）→ 有 cleanEnd，不续
                    val cleanEnd = finishReason in setOf(
                        "stop", "content_filter", "tool_calls", "function_call",
                        "end_turn", "stop_sequence", "tool_use",
                    )
                    // ⭐ 静默停修复（v2）：
                    //  - 显式截断（length/max_tokens）→ 必续
                    //  - 国产服务**漏发 finish_reason**（finishReason 空、无 error、有正文、无工具）：
                    //    本轮正文 >= 300 字才认为"没说完"→ 续写；
                    //    模型收到"请继续"后若产出很短/空（<300），说明其实已说完 → 自然早停，不空转。
                    val explicitTrunc = finishReason == "length" || finishReason == "max_tokens"
                    val missingSignalButLong = !cleanEnd && streamError == null &&
                        toolCalls.isEmpty() && content.length >= 300
                    val shouldContinue = (explicitTrunc || missingSignalButLong) && autoContinueRounds < 6
                    Log.d("AgentCore", "continue decision: finish='$finishReason' explicit=$explicitTrunc " +
                        "missingLong=$missingSignalButLong len=${content.length} rounds=$autoContinueRounds → $shouldContinue")
                    if (shouldContinue) {
                        autoContinueRounds++
                        Log.d("AgentCore", "auto-continue round $autoContinueRounds")
                        // 把已生成的 partial 先存为消息，再发"请继续"指令
                        val partialMsg = ChatMessage(
                            role = ChatMessage.Role.ASSISTANT,
                            content = content.toString(),
                            reasoning = reasoning.toString(),
                            toolCalls = toolCalls,
                            tokenStats = stats,
                        )
                        conversation.addMessage(partialMsg)
                        send(AgentEvent.MessageDone(partialMsg))
                        // 发"请继续输出剩余内容"作为 user 消息
                        val contMsg = ChatMessage.user("（请继续输出剩余内容，不要重复已输出的部分）")
                        conversation.addMessage(contMsg)
                        send(AgentEvent.ToolMessage(contMsg))
                        // 更新 request 让下一轮基于新上下文
                        request = buildRequest(conversation, cfg, comp)
                        continue
                    }

                    // ⭐ 修复「思考着突然就停下」：流**中途**报错（网络抖动 / SSE 断流 / 5xx）时，
                    //    旧逻辑只要收到过任意增量就放弃整轮 → AI 说到一半戛然而止、不再续写。
                    //    现在：网络类错误只要没用完重试次数就**自动重试**（每次 attempt 前 content 已 clear，
                    //    会重新生成完整内容，不会重复叠加）；4xx（除 408 超时 / 429 限流）重试无意义才放弃。
                    val err = streamError
                    if (err == null) break

                    val isClientError = Regex("""HTTP 4\d\d""").containsMatchIn(err) &&
                        !err.contains("HTTP 408") && !err.contains("HTTP 429")

                                        if (isClientError) {
                        // ⭐ v1.2.0 #2：4xx（工具结果 400 等）不再静默整轮放弃，
                        //    保留 partial + 自动重试一次（与断流修复对齐）
                        if (attempt < 1) {
                            Log.d("AgentCore", "4xx retry (attempt $attempt, finish=$finishReason): $err")
                            attempt++
                            // 重试前清掉本轮已生成的孤儿消息（避免重复 partial）
                            delay(800L)
                            continue
                        }
                        send(AgentEvent.Error(err))
                        send(AgentEvent.State(AgentState(status = AgentState.Status.ERROR)))
                        send(AgentEvent.Done)
                        return@channelFlow
                    }

                    if (attempt >= cfg.networkRetries) {
                        // 网络类错误重试耗尽：保留已生成的部分内容，避免「凭空消失」，并明确告知用户
                        if (content.isNotEmpty() || reasoning.isNotEmpty() || toolCalls.isNotEmpty()) {
                            val partial = ChatMessage(
                                role = ChatMessage.Role.ASSISTANT,
                                content = content.toString(),
                                reasoning = reasoning.toString(),
                                toolCalls = toolCalls,
                                tokenStats = stats,
                            )
                            conversation.addMessage(partial)
                            send(AgentEvent.MessageDone(partial))
                            send(AgentEvent.State(AgentState(status = AgentState.Status.ERROR)))
                            send(AgentEvent.Error("连接中断（已自动重试 ${attempt} 次），以上为中断前已生成的内容：$err"))
                        } else {
                            send(AgentEvent.Error(err))
                            send(AgentEvent.State(AgentState(status = AgentState.Status.ERROR)))
                        }
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

                                // ⭐ 中期待办：工具调用并发（async + awaitAll）减少总时延。
                //    事件顺序仍保持稳定：先按序 ToolStart → 并发执行 → 按序 ToolEnd/ToolMessage。
                //    各工具的共享资源（PTY/终端会话池、文件）天然安全：
                //    PTY 的 withContext(Main) 串行化；文件各操作不同路径。
                                toolCalls.forEach { call ->
                    send(AgentEvent.State(AgentState(
                        status = AgentState.Status.TOOL_CALLING,
                        currentTool = call.name,
                        progressText = "执行工具: ${call.name}",
                        startedAt = System.currentTimeMillis(),
                    )))
                    send(AgentEvent.ToolStart(call))
                }
                // ⭐ channelFlow 的 receiver 是 ProducerScope（CoroutineScope），可直接 async
                val toolJobs = toolCalls.map { call ->
                    async {
                        registry.execute(call)
                    }
                }
                toolJobs.awaitAll().forEachIndexed { idx, result ->
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

    suspend fun continueFrom(conversation: Conversation): String {
        val provider = providers.active ?: throw IllegalStateException("没有可用的 Provider")

        val lastAssistantIdx = conversation.messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (lastAssistantIdx < 0) return ""
        val lastContent = conversation.messages[lastAssistantIdx].content

        val history = conversation.messages.take(lastAssistantIdx + 1)
            .filter { it.role != ChatMessage.Role.SYSTEM }
            .filter { !(it.role == ChatMessage.Role.ASSISTANT && it.content.isBlank()
                && it.toolCalls.isEmpty() && it.reasoning.isBlank()) }
            .filter { !(it.role == ChatMessage.Role.TOOL && it.content.isBlank()) }

                val memory = runCatching { memoryPromptProvider(conversation) }.getOrDefault("")

        val continueInstruction = buildSystemPrompt("", "", memory) +
            "\n\n【继续生成任务】\n" +
            "你的上一条回复被用户中断了。请直接从断点处继续输出剩余内容，\n" +
            "- 不要重复已输出的任何内容\n" +
            "- 不要加\"好的/继续/接上文\"等前缀说明\n" +
            "- 保持与上文一致的风格和格式\n" +
            "上一条已输出的结尾是：「${lastContent.takeLast(80)}」"

        val req = buildList {
            add(ChatMessage.system(continueInstruction))
            addAll(history)
            add(ChatMessage.user("请继续输出剩余内容。"))
        }
        val out = provider.chatOnce(req, temperature = 0.3f).trim()
        val prefix = lastContent.takeLast(50).trim()
        return if (out.startsWith(prefix)) out.removePrefix(prefix).trimStart() else out
    }

    private fun buildRequest(
        conversation: Conversation,
        cfg: AgentConfig,
        comp: CompState = CompState(),
    ): List<ChatMessage> {
        val history = conversation.messages.toList()
        val sysExtra = history.filter { it.role == ChatMessage.Role.SYSTEM }
            .joinToString("\n") { it.content }
                val memoryPrompt = runCatching { memoryPromptProvider(conversation) }.getOrDefault("")
        // ⭐ 压缩：跳过已总结进摘要的旧消息，并注入摘要 SYSTEM 消息
        val rest = history
            .drop(comp.count.coerceIn(0, history.size))
            .filter { it.role != ChatMessage.Role.SYSTEM }
            .filter { !(it.role == ChatMessage.Role.ASSISTANT && it.content.isBlank() && it.toolCalls.isEmpty() && it.reasoning.isBlank()) }
            .filter { !(it.role == ChatMessage.Role.TOOL && it.content.isBlank()) }
        return buildList {
            add(ChatMessage.system(buildSystemPrompt(cfg.systemPromptExtra, sysExtra, memoryPrompt)))
            if (comp.summary.isNotBlank()) {
                add(ChatMessage.system("【较早对话的自动摘要（上下文压缩生成，保留关键事实/结论/进度）】\n" + comp.summary))
            }
            addAll(rest)
        }
    }

    /**
     * ⭐ 上下文自动压缩：把 [conversation.messages] 中较旧的一段（保留尾部 TAIL 条）
     * 总结成简短摘要，更新 [comp]。失败静默跳过，不影响主流程。
     */
    private suspend fun compressHistory(
        provider: com.ai.android.provider.AIProvider,
        conversation: Conversation,
        comp: CompState,
    ) {
        val tailKeep = 6
        val history = conversation.messages
        val from = comp.count.coerceIn(0, history.size)
        val to = (history.size - tailKeep).coerceAtLeast(from)   // 新可压缩的区间 [from, to)
        if (to - from < 3) return                                   // 没有足够新内容
        val slice = history.subList(from, to).map { m ->
            when {
                m.role == ChatMessage.Role.TOOL -> "工具[${m.toolResults.firstOrNull()?.toolCallId}]: ${m.content.take(400)}"
                m.role == ChatMessage.Role.SYSTEM -> ""
                else -> "${m.role.name}: ${m.content.take(400)}" +
                    if (m.toolCalls.isNotEmpty()) " 〔工具调用: ${m.toolCalls.joinToString(",") { it.name }}〕" else ""
            }
        }.filter { it.isNotBlank() }
        if (slice.isEmpty()) return
        val prev = comp.summary
        val prompt = buildString {
            append("请把下面较早的对话记录压缩成一份不超过 500 字的客观摘要，保留：任务目标、已完成的关键步骤、关键数据/路径/结论、未完成事项。不要寒暄。\n")
            if (prev.isNotBlank()) append("\n【已有摘要（需合并进新摘要）】\n$prev\n\n")
            append("\n【待压缩记录】\n").append(slice.joinToString("\n"))
        }
        val out = runCatching { provider.chatOnce(listOf(ChatMessage.user(prompt)), temperature = 0.2f) }
            .getOrNull()?.trim()
        if (!out.isNullOrBlank()) {
            comp.summary = if (prev.isBlank()) out else "$prev\n$out"
            comp.count = to
        }
    }

    private fun buildSystemPrompt(
        extra: String,
        userExtra: String,
        memoryPrompt: String = "",
    ): String {
        val D = "${'$'}"
        val DD = "${'$'}${'$'}"
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

【输出格式】
10. 数学公式必须用 LaTeX 语法：
    - 行内公式用单个 ${D} 包裹，例如 ${D}a^2+b^2=c^2${D}
    - 独立公式用双 ${D} 包裹并独占一行，例如：${DD}a^2+b^2=c^2${DD}
    - **绝对不要输出完整的 LaTeX 文档**（不要出现 \documentclass、\begin{document}、\usepackage、
      \begin{enumerate} 等），只输出公式本身。
    - **绝对不要用 \begin{...} \end{...} 环境**，只输出纯公式表达式。
    - 不要把公式包在 ``` 代码块里。
11. 表格用标准 Markdown 语法，每行以 | 开头和结尾，表头下必须有 |---|---| 分隔行。
12. 只有代码才用 ``` 代码块包裹。

当前设备：Android 手机（无 root），工作目录 /sdcard。
        """.trimIndent()

        val sb = StringBuilder(base)

        // ⭐ 长期记忆注入
        if (memoryPrompt.isNotBlank()) {
            sb.append("\n\n").append(memoryPrompt)
        }

        if (userExtra.isNotBlank()) sb.append("\n\n【用户设定的长期要求】\n").append(userExtra)
        if (extra.isNotBlank()) sb.append("\n\n").append(extra)
        return sb.toString()
    }
}