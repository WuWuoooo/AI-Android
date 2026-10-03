package com.ai.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ai.android.agent.AgentEvent
import com.ai.android.model.AgentState
import com.ai.android.model.ChatMessage
import com.ai.android.model.Conversation
import com.ai.android.service.FloatingService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 主 ViewModel：持有会话列表、Agent 状态，驱动 AgentCore 运行。
 */
class MainViewModel(private val app: MainApp) : ViewModel() {

    // ---------- 会话 ----------

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations

    private val _current = MutableStateFlow<Conversation?>(null)
    val current: StateFlow<Conversation?> = _current

    /** 每次消息内容变动 +1，用于驱动 Compose 刷新（消息本体在 MutableList 中） */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    // ---------- Agent 状态 ----------

    private val _agentState = MutableStateFlow(AgentState())
    val agentState: StateFlow<AgentState> = _agentState

    /** ask_user 待回答问题 */
    data class PendingQuestion(val question: String, val options: List<String>)

    private val _pendingQuestion = MutableStateFlow<PendingQuestion?>(null)
    val pendingQuestion: StateFlow<PendingQuestion?> = _pendingQuestion

    private var pendingAnswer: CompletableDeferred<String>? = null
    private var agentJob: Job? = null

    val skills get() = app.skills
    val settings get() = app.settings
    val providerManager get() = app.providerManager

    init {
        // 注入 ask_user 处理器
        app.toolRegistry.askUserHandler = { question, options ->
            askUser(question, options)
        }
        newConversation()
    }

    // ==================== 会话管理 ====================

    fun newConversation() {
        val c = Conversation(id = UUID.randomUUID().toString(), title = "新会话")
        _conversations.value = _conversations.value + c
        _current.value = c
        bump()
    }

    fun switchConversation(id: String) {
        val c = _conversations.value.firstOrNull { it.id == id } ?: return
        _current.value = c
        bump()
    }

    fun deleteConversation(id: String) {
        _conversations.value = _conversations.value.filterNot { it.id == id }
        if (_current.value?.id == id) {
            _current.value = _conversations.value.firstOrNull() ?: run {
                val c = Conversation(id = UUID.randomUUID().toString(), title = "新会话")
                _conversations.value = listOf(c)
                c
            }
        }
        bump()
    }

    private fun renameIfNeeded(conv: Conversation, text: String) {
        if (conv.messages.none { it.role == ChatMessage.Role.USER }) {
            val updated = conv.copy(title = text.take(16))
            _conversations.value = _conversations.value.map { if (it.id == conv.id) updated else it }
            _current.value = updated
        }
    }

    // ==================== 发消息 ====================

    fun send(text: String) {
        val conv = _current.value ?: return
        if (text.isBlank()) return
        if (agentJob?.isActive == true) return

        renameIfNeeded(conv, text)
        conv.addMessage(ChatMessage.user(text.trim()))
        bump()

        agentJob = viewModelScope.launch {
            runAgent(conv)
        }
    }

    private suspend fun runAgent(conv: Conversation) {
        FloatingService.showWork(app, AgentState(status = AgentState.Status.THINKING, progressText = "思考中…"))

        var placeholderId: String? = null

        fun ensurePlaceholder(): String {
            val pid = placeholderId
            if (pid != null && conv.messages.any { it.id == pid }) return pid
            val np = ChatMessage(role = ChatMessage.Role.ASSISTANT, isStreaming = true)
            conv.messages.add(np)
            placeholderId = np.id
            bump()
            return np.id
        }

        val content = StringBuilder()
        val reasoning = StringBuilder()

        try {
            app.agentCore.run(conv).collect { ev ->
                when (ev) {
                    is AgentEvent.ReasoningDelta -> {
                        reasoning.append(ev.text)
                        updateMessage(conv, ensurePlaceholder()) { it.copy(reasoning = reasoning.toString()) }
                        bump()
                    }

                    is AgentEvent.ContentDelta -> {
                        content.append(ev.text)
                        updateMessage(conv, ensurePlaceholder()) { it.copy(content = content.toString()) }
                        bump()
                    }

                    is AgentEvent.MessageDone -> {
                        // 最终消息已由 AgentCore 写入会话，移除流式占位并准备下一轮
                        placeholderId?.let { pid -> conv.messages.removeAll { it.id == pid } }
                        placeholderId = null
                        content.clear()
                        reasoning.clear()
                        bump()
                    }

                    is AgentEvent.ToolStart, is AgentEvent.ToolEnd, is AgentEvent.ToolMessage -> bump()

                    is AgentEvent.Stats -> {
                        updateMessage(conv, conv.messages.lastOrNull { it.role == ChatMessage.Role.ASSISTANT }?.id ?: "") {
                            it.copy(tokenStats = ev.stats)
                        }
                        bump()
                    }

                    is AgentEvent.State -> {
                        _agentState.value = ev.state
                        FloatingService.update(ev.state)
                    }

                    is AgentEvent.Error -> {
                        placeholderId?.let { pid -> conv.messages.removeAll { it.id == pid } }
                        placeholderId = null
                        conv.messages.add(
                            ChatMessage(
                                role = ChatMessage.Role.ASSISTANT,
                                content = "",
                                error = ev.msg,
                            )
                        )
                        bump()
                    }

                    AgentEvent.Done -> Unit
                }
            }
        } finally {
            placeholderId?.let { pid -> conv.messages.removeAll { it.id == pid } }
            bump()
            val endState = when (_agentState.value.status) {
                AgentState.Status.ERROR -> _agentState.value.copy(status = AgentState.Status.ERROR)
                else -> AgentState(status = AgentState.Status.COMPLETED)
            }
            _agentState.value = AgentState()
            FloatingService.finishWork(app, endState)
        }
    }

    fun stop() {
        agentJob?.cancel()
        agentJob = null
        _agentState.value = AgentState()
        FloatingService.finishWork(app, AgentState(status = AgentState.Status.COMPLETED))
    }

    override fun onCleared() {
        agentJob?.cancel()
        super.onCleared()
    }

    // ==================== ask_user ====================

    private suspend fun askUser(question: String, options: String): String {
        val deferred = CompletableDeferred<String>()
        pendingAnswer = deferred
        _pendingQuestion.value = PendingQuestion(
            question = question,
            options = options.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() },
        )
        _agentState.value = _agentState.value.copy(
            status = AgentState.Status.WAITING_USER,
            progressText = "等待用户回答…",
        )
        FloatingService.update(_agentState.value)

        val answer = deferred.await()
        _pendingQuestion.value = null
        _agentState.value = _agentState.value.copy(
            status = AgentState.Status.THINKING,
            progressText = "继续执行…",
        )
        return if (answer.isBlank()) "（用户未回答）" else answer
    }

    fun answerQuestion(text: String) {
        pendingAnswer?.complete(text)
        pendingAnswer = null
        _pendingQuestion.value = null
    }

    // ==================== 工具 ====================

    private fun updateMessage(conv: Conversation, id: String, transform: (ChatMessage) -> ChatMessage) {
        if (id.isBlank()) return
        val idx = conv.messages.indexOfFirst { it.id == id }
        if (idx >= 0) conv.messages[idx] = transform(conv.messages[idx])
    }

    private fun bump() {
        _version.value++
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { MainViewModel(MainApp.instance) }
        }
    }
}
