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
import com.ai.android.storage.ConversationEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class MainViewModel(private val app: MainApp) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations

    private val _current = MutableStateFlow<Conversation?>(null)
    val current: StateFlow<Conversation?> = _current

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private val _agentState = MutableStateFlow(AgentState())
    val agentState: StateFlow<AgentState> = _agentState

    private val _attachedFiles = MutableStateFlow<List<String>>(emptyList())
    val attachedFiles: StateFlow<List<String>> = _attachedFiles

    private val _attachedImages = MutableStateFlow<List<String>>(emptyList())
    val attachedImages: StateFlow<List<String>> = _attachedImages

    data class PendingQuestion(val question: String, val options: List<String>)

    private val _pendingQuestion = MutableStateFlow<PendingQuestion?>(null)
    val pendingQuestion: StateFlow<PendingQuestion?> = _pendingQuestion

    private var pendingAnswer: CompletableDeferred<String>? = null
    private var agentJob: Job? = null

    val skills get() = app.skills
    val settings get() = app.settings
    val providerManager get() = app.providerManager

    init {
        app.toolRegistry.askUserHandler = { question, options -> askUser(question, options) }
        val bootstrap = Conversation(id = UUID.randomUUID().toString(), title = "新会话")
        _conversations.value = listOf(bootstrap)
        _current.value = bootstrap

        viewModelScope.launch {
            val loaded = runCatching { loadConversations() }.getOrNull().orEmpty()
            if (loaded.isNotEmpty()) {
                _conversations.value = loaded
                _current.value = loaded.first()
                bump()
            }
        }
    }

    // ==================== 持久化 ====================

    private suspend fun loadConversations(): List<Conversation> {
        val rows = app.database.conversationDao().all()
        return rows.mapNotNull { row ->
            runCatching { json.decodeFromString(Conversation.serializer(), row.payload) }.getOrNull()
        }
    }

    private fun persist(conv: Conversation) {
        viewModelScope.launch {
            runCatching {
                app.database.conversationDao().upsert(
                    ConversationEntity(
                        id = conv.id, title = conv.title,
                        createdAt = conv.createdAt, updatedAt = conv.updatedAt,
                        payload = json.encodeToString(Conversation.serializer(), conv),
                    )
                )
            }
        }
    }

    // ==================== 会话管理 ====================

    fun newConversation() {
        val c = Conversation(id = UUID.randomUUID().toString(), title = "新会话")
        _conversations.value = _conversations.value + c
        _current.value = c
        persist(c); bump()
    }

    fun switchConversation(id: String) {
        val c = _conversations.value.firstOrNull { it.id == id } ?: return
        _current.value = c; bump()
    }

    fun deleteConversation(id: String) {
        _conversations.value = _conversations.value.filterNot { it.id == id }
        if (_current.value?.id == id) {
            _current.value = _conversations.value.firstOrNull() ?: run {
                val c = Conversation(id = UUID.randomUUID().toString(), title = "新会话")
                _conversations.value = listOf(c); persist(c); c
            }
        }
        viewModelScope.launch { runCatching { app.database.conversationDao().delete(id) } }
        bump()
    }

    fun renameConversation(id: String, newTitle: String) {
        val conv = _conversations.value.firstOrNull { it.id == id } ?: return
        conv.title = newTitle
        conv.updatedAt = System.currentTimeMillis()
        _conversations.value = _conversations.value.map { if (it.id == id) conv else it }
        if (_current.value?.id == id) _current.value = conv
        persist(conv); bump()
    }

    fun togglePin(id: String) {
        val conv = _conversations.value.firstOrNull { it.id == id } ?: return
        conv.pinned = !conv.pinned
        _conversations.value = _conversations.value.map { if (it.id == id) conv else it }
        if (_current.value?.id == id) _current.value = conv
        persist(conv); bump()
    }

    fun retryLast() {
        val conv = _current.value ?: return
        if (agentJob?.isActive == true) return
        val lastUserIdx = conv.messages.indexOfLast { it.role == ChatMessage.Role.USER }
        if (lastUserIdx < 0) return
        val kept = conv.messages.take(lastUserIdx + 1).toMutableList()
        conv.messages.clear(); conv.messages.addAll(kept)
        bump(); persist(conv)
        agentJob = viewModelScope.launch { runAgent(conv) }
    }

    fun continueGeneration() {
        val conv = _current.value ?: return
        if (agentJob?.isActive == true) return
        val lastAssistantIdx = conv.messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (lastAssistantIdx < 0) return
        val lastAssistant = conv.messages[lastAssistantIdx]
        if (lastAssistant.content.isBlank()) return

        agentJob = viewModelScope.launch {
            val continuation = runCatching { app.agentCore.continueFrom(conv) }
                .getOrElse {
                    conv.messages.add(ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "", error = it.message))
                    bump(); return@launch
                }
            val idx = conv.messages.indexOfFirst { it.id == lastAssistant.id }
            if (idx >= 0) {
                val old = conv.messages[idx]
                conv.messages[idx] = old.copy(content = old.content + continuation)
            }
            persist(conv); bump()
        }
    }

    // ==================== 附件 ====================

    fun attachFile(path: String) { _attachedFiles.value = _attachedFiles.value + path }
    fun attachImage(b64: String) { _attachedImages.value = _attachedImages.value + b64 }
    fun removeAttachment(path: String) { _attachedFiles.value = _attachedFiles.value - path }
    fun removeImage(b64: String) { _attachedImages.value = _attachedImages.value - b64 }

    // ==================== 发消息 ====================

    fun send(text: String) {
        val conv = _current.value ?: return
        if (text.isBlank() && _attachedImages.value.isEmpty() && _attachedFiles.value.isEmpty()) return
        if (agentJob?.isActive == true) return

        agentJob = viewModelScope.launch {
            val fileContents = withContext(Dispatchers.IO) {
                buildString {
                    _attachedFiles.value.forEach { path ->
                        val f = File(path)
                        if (f.exists() && f.isFile) {
                            appendLine("【附件文件: ${f.name}】")
                            appendLine("路径: $path")
                            appendLine("内容:")
                            appendLine("```")
                            if (f.length() > 100_000) {
                                appendLine(f.readText().take(100_000))
                                appendLine("...（已截断，原文件 ${f.length()} 字节）")
                            } else {
                                appendLine(runCatching { f.readText() }.getOrDefault("（读取失败）"))
                            }
                            appendLine("```")
                            appendLine()
                        }
                    }
                }
            }

            val fullText = buildString {
                if (text.isNotBlank()) append(text.trim())
                if (fileContents.isNotBlank()) {
                    if (text.isNotBlank()) appendLine().appendLine()
                    append("--- 以下是用户附加的文件内容 ---\n")
                    append(fileContents)
                }
            }

            if (conv.messages.none { it.role == ChatMessage.Role.USER }) {
                conv.title = text.take(16).ifBlank { "文件分析" }
                _conversations.value = _conversations.value.map { if (it.id == conv.id) conv else it }
                _current.value = conv
            }

            val msg = ChatMessage.user(fullText).copy(images = _attachedImages.value)
            conv.addMessage(msg)
            _attachedFiles.value = emptyList()
            _attachedImages.value = emptyList()
            persist(conv); bump()

            runAgent(conv)
        }
    }

    private suspend fun runAgent(conv: Conversation) {
        FloatingService.showWork(
            app,
            AgentState(status = AgentState.Status.THINKING, progressText = "思考中…"),
        )

        var placeholderId: String? = null
        fun ensurePlaceholder(): String {
            placeholderId?.let { return it }
            val np = ChatMessage(role = ChatMessage.Role.ASSISTANT, isStreaming = true)
            conv.messages.add(np); placeholderId = np.id; bump()
            return np.id
        }
        fun dropPlaceholder() {
            placeholderId?.let { pid -> conv.messages.removeAll { it.id == pid } }
            placeholderId = null
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
                        dropPlaceholder()
                        content.clear(); reasoning.clear()
                        persist(conv)
                        if (conv.title == "新会话" || conv.title.length <= 4) {
                            val firstUser = conv.messages.firstOrNull { it.role == ChatMessage.Role.USER }?.content.orEmpty()
                            if (firstUser.isNotBlank()) {
                                viewModelScope.launch {
                                    val newTitle = app.agentCore.summarizeTitle(firstUser, ev.message.content)
                                    conv.title = newTitle
                                    _conversations.value = _conversations.value.map {
                                        if (it.id == conv.id) conv.copy(title = newTitle) else it
                                    }
                                    _current.value = conv
                                    persist(conv); bump()
                                }
                            }
                        }
                        bump()
                    }
                    is AgentEvent.ToolStart, is AgentEvent.ToolEnd, is AgentEvent.ToolMessage -> {
                        persist(conv); bump()
                    }
                    is AgentEvent.Stats -> {
                        val lastId = conv.messages.lastOrNull { it.role == ChatMessage.Role.ASSISTANT }?.id ?: ""
                        updateMessage(conv, lastId) { it.copy(tokenStats = ev.stats) }
                        bump()
                    }
                    is AgentEvent.State -> {
                        _agentState.value = ev.state
                        FloatingService.update(ev.state)
                    }
                    is AgentEvent.Error -> {
                        dropPlaceholder()
                        conv.messages.add(ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "", error = ev.msg))
                        persist(conv); bump()
                    }
                    AgentEvent.Done -> Unit
                }
            }
        } finally {
            dropPlaceholder(); persist(conv); bump()
            val endState = if (_agentState.value.status == AgentState.Status.ERROR)
                _agentState.value.copy(status = AgentState.Status.ERROR)
            else AgentState(status = AgentState.Status.COMPLETED)
            _agentState.value = AgentState()
            FloatingService.finishWork(app, endState)
        }
    }

    fun stop() {
        agentJob?.cancel(); agentJob = null
        _agentState.value = AgentState()
        FloatingService.finishWork(app, AgentState(status = AgentState.Status.COMPLETED))
    }

    override fun onCleared() { agentJob?.cancel(); super.onCleared() }

    // ==================== ask_user ====================

    private suspend fun askUser(question: String, options: String): String {
        val deferred = CompletableDeferred<String>()
        pendingAnswer = deferred
        _pendingQuestion.value = PendingQuestion(
            question = question,
            options = options.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() },
        )
        _agentState.value = _agentState.value.copy(
            status = AgentState.Status.WAITING_USER, progressText = "等待用户回答…",
        )
        FloatingService.update(_agentState.value)

        val answer = deferred.await()
        _pendingQuestion.value = null
        _agentState.value = _agentState.value.copy(
            status = AgentState.Status.THINKING, progressText = "继续执行…",
        )
        return if (answer.isBlank()) "（用户未回答）" else answer
    }

    fun answerQuestion(text: String) {
        pendingAnswer?.complete(text); pendingAnswer = null; _pendingQuestion.value = null
    }

    private fun updateMessage(conv: Conversation, id: String, transform: (ChatMessage) -> ChatMessage) {
        if (id.isBlank()) return
        val idx = conv.messages.indexOfFirst { it.id == id }
        if (idx >= 0) conv.messages[idx] = transform(conv.messages[idx])
    }

    private fun bump() { _version.value++ }

    companion object {
        val Factory = viewModelFactory { initializer { MainViewModel(MainApp.instance) } }
    }
}