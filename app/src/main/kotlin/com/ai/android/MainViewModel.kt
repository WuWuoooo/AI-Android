package com.ai.android

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ai.android.agent.AgentEvent
import com.ai.android.model.AgentState
import com.ai.android.model.ChatMessage
import com.ai.android.model.Conversation
import com.ai.android.model.Project
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

    private val TAG = "MainViewModel"
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

    private val _memoryList = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val memoryList: StateFlow<List<Pair<String, String>>> = _memoryList

    private val _quotedMessage = MutableStateFlow<ChatMessage?>(null)
    val quotedMessage: StateFlow<ChatMessage?> = _quotedMessage

    /** ⭐ 上一条 assistant 消息是否"未完整结束"（用于控制"继续生成"按钮） */
    private val _lastGenerationIncomplete = MutableStateFlow(false)
    val lastGenerationIncomplete: StateFlow<Boolean> = _lastGenerationIncomplete

    data class PendingQuestion(val question: String, val options: List<String>)

    private val _pendingQuestion = MutableStateFlow<PendingQuestion?>(null)
    val pendingQuestion: StateFlow<PendingQuestion?> = _pendingQuestion

    private var pendingAnswer: CompletableDeferred<String>? = null
    private var agentJob: Job? = null

    val skills get() = app.skills
    val settings get() = app.settings
    val providerManager get() = app.providerManager
    val projects get() = app.projectStore.projects

    init {
        app.toolRegistry.askUserHandler = { question, options -> askUser(question, options) }
        val bootstrap = Conversation(id = UUID.randomUUID().toString(), title = "新会话")
        _conversations.value = listOf(bootstrap)
        _current.value = bootstrap

        viewModelScope.launch {
            runCatching { app.projectStore.init(); refreshMemoryList() }
            val loaded = runCatching { loadConversations() }.getOrNull().orEmpty()
            Log.d(TAG, "loaded ${loaded.size} conversations from DB")
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
        app.appScope.launch {
            runCatching {
                val safe = conv.copy(
                    messages = conv.messages.mapTo(mutableListOf()) { m ->
                        if (m.images.sumOf { it.length } > 2_000_000) m.copy(images = emptyList())
                        else m
                    }.toMutableList()
                )
                app.database.conversationDao().upsert(
                    ConversationEntity(
                        id = safe.id, title = safe.title,
                        createdAt = safe.createdAt, updatedAt = safe.updatedAt,
                        payload = json.encodeToString(Conversation.serializer(), safe),
                        projectId = safe.projectId,
                    )
                )
            }.onFailure { Log.e(TAG, "persist failed", it) }
        }
    }

    // ==================== 会话管理 ====================

    fun newConversation() {
        val c = Conversation(id = UUID.randomUUID().toString(), title = "新会话")
        _conversations.value = _conversations.value + c
        _current.value = c
        _quotedMessage.value = null
        _lastGenerationIncomplete.value = false
        persist(c); bump()
    }

    fun switchConversation(id: String) {
        val c = _conversations.value.firstOrNull { it.id == id } ?: return
        _current.value = c
        _quotedMessage.value = null
        _lastGenerationIncomplete.value = false
        bump()
    }

    fun deleteConversation(id: String) {
        _conversations.value = _conversations.value.filterNot { it.id == id }
        if (_current.value?.id == id) {
            _current.value = _conversations.value.firstOrNull() ?: run {
                val c = Conversation(id = UUID.randomUUID().toString(), title = "新会话")
                _conversations.value = listOf(c); persist(c); c
            }
        }
        app.appScope.launch { runCatching { app.database.conversationDao().delete(id) } }
        bump()
    }

    fun renameConversation(id: String, newTitle: String) {
        val conv = _conversations.value.firstOrNull { it.id == id } ?: return
        conv.title = newTitle; conv.updatedAt = System.currentTimeMillis()
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

    // ==================== 项目 ====================

    fun createProject(name: String) {
        viewModelScope.launch { runCatching {
            app.projectStore.upsert(Project(id = UUID.randomUUID().toString().take(8), name = name))
        } }
    }

    fun renameProject(id: String, newName: String) {
        viewModelScope.launch { runCatching {
            app.projectStore.get(id)?.let { app.projectStore.upsert(it.copy(name = newName)) }
        } }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch { runCatching {
            app.projectStore.delete(id)
            _conversations.value.forEach { c ->
                if (c.projectId == id) { c.projectId = ""; persist(c) }
            }
            _conversations.value = _conversations.value.map { c ->
                if (c.projectId == id) c.copy().also { it.projectId = "" } else c
            }
            bump()
        } }
    }

    fun moveConversationToProject(convId: String, projectId: String) {
        val conv = _conversations.value.firstOrNull { it.id == convId } ?: return
        conv.projectId = projectId
        _conversations.value = _conversations.value.map { if (it.id == convId) conv else it }
        if (_current.value?.id == convId) _current.value = conv
        persist(conv); bump()
    }

    // ==================== 记忆 ====================

    fun refreshMemoryList() { _memoryList.value = app.memory.list().toList() }
    fun saveMemory(key: String, content: String) { app.memory.save(key, content); refreshMemoryList() }
    fun deleteMemory(key: String) { app.memory.delete(key); refreshMemoryList() }

    // ==================== 引用 ====================

    fun quoteMessage(msg: ChatMessage) { _quotedMessage.value = msg }
    fun clearQuote() { _quotedMessage.value = null }

    // ==================== 重试 / 继续 ====================

    fun retryLast() {
        val conv = _current.value ?: return
        if (agentJob?.isActive == true) return
        val lastUserIdx = conv.messages.indexOfLast { it.role == ChatMessage.Role.USER }
        if (lastUserIdx < 0) return
        val kept = conv.messages.take(lastUserIdx + 1).toMutableList()
        conv.messages.clear(); conv.messages.addAll(kept)
        bump(); persist(conv)
        _lastGenerationIncomplete.value = false
        agentJob = viewModelScope.launch { runCatching { runAgent(conv) } }
    }

    fun continueGeneration() {
        val conv = _current.value ?: return
        if (agentJob?.isActive == true) return
        val lastAssistantIdx = conv.messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (lastAssistantIdx < 0) return
        val lastAssistant = conv.messages[lastAssistantIdx]
        if (lastAssistant.content.isBlank()) return

        agentJob = viewModelScope.launch {
            runCatching {
                val continuation = app.agentCore.continueFrom(conv)
                val idx = conv.messages.indexOfFirst { it.id == lastAssistant.id }
                if (idx >= 0) {
                    val old = conv.messages[idx]
                    conv.messages[idx] = old.copy(content = old.content + continuation)
                }
                persist(conv); bump()
                _lastGenerationIncomplete.value = false
            }
        }
    }

    // ==================== 附件 ====================

    fun attachFile(path: String) { _attachedFiles.value = _attachedFiles.value + path }
    fun attachImage(b64: String) { _attachedImages.value = _attachedImages.value + b64 }
    fun removeAttachment(path: String) { _attachedFiles.value = _attachedFiles.value - path }
    fun removeImage(b64: String) { _attachedImages.value = _attachedImages.value - b64 }

    // ==================== 发送 ====================

    fun send(text: String) {
        val conv = _current.value ?: return
        val quoted = _quotedMessage.value
        if (text.isBlank() && _attachedImages.value.isEmpty() && _attachedFiles.value.isEmpty()) return
        if (agentJob?.isActive == true) return

        val filesSnapshot = _attachedFiles.value.toList()
        val imagesSnapshot = _attachedImages.value.toList()

        agentJob = viewModelScope.launch {
            try {
                val fileContents = withContext(Dispatchers.IO) {
                    buildString {
                        filesSnapshot.forEach { path ->
                            runCatching {
                                val f = File(path)
                                if (f.exists() && f.isFile) {
                                    appendLine("【附件文件: ${f.name}】")
                                    appendLine("路径: $path")
                                    appendLine("内容:")
                                    appendLine("```")
                                    val maxChars = app.settings.maxReadChars()
                                    val content = if (maxChars > 0) f.readText().take(maxChars) else f.readText()
                                    appendLine(content)
                                    if (maxChars > 0 && f.length() > maxChars) {
                                        appendLine("...（已截断，原 ${f.length()} 字符）")
                                    }
                                    appendLine("```")
                                    appendLine()
                                }
                            }.onFailure { Log.e(TAG, "read file failed", it) }
                        }
                    }
                }

                val fullText = buildString {
                    if (quoted != null) {
                        appendLine("【引用消息】")
                        appendLine(quoted.content.take(500))
                        appendLine()
                    }
                    if (text.isNotBlank()) append(text.trim())
                    if (fileContents.isNotBlank()) {
                        if (text.isNotBlank() || quoted != null) appendLine().appendLine()
                        append("--- 以下是用户附加的文件内容 ---\n")
                        append(fileContents)
                    }
                }

                if (conv.messages.none { it.role == ChatMessage.Role.USER }) {
                    conv.title = text.take(16).ifBlank { "文件分析" }
                    _conversations.value = _conversations.value.map { if (it.id == conv.id) conv else it }
                    _current.value = conv
                }

                val uiContent = text.trim().ifBlank {
                    if (quoted != null) "（引用）" else "（附件）"
                }
                val msg = ChatMessage.user(uiContent).copy(
                    images = imagesSnapshot,
                    files = filesSnapshot,
                    quotedContent = quoted?.content.orEmpty(),
                )
                conv.addMessage(msg)
                _attachedFiles.value = emptyList()
                _attachedImages.value = emptyList()
                _quotedMessage.value = null
                persist(conv); bump()

                // 发给 AI 时用完整内容
                val idx = conv.messages.indexOfFirst { it.id == msg.id }
                if (idx >= 0) conv.messages[idx] = msg.copy(content = fullText)
                persist(conv); bump()

                runAgent(conv)
            } catch (e: Throwable) {
                Log.e(TAG, "send failed", e)
                conv.messages.add(ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "", error = "发送失败: ${e.message}"))
                bump()
            }
        }
    }

    // ==================== Agent ====================

    private suspend fun runAgent(conv: Conversation) {
        if (app.settings.floatingEnabled()) {
            runCatching {
                FloatingService.showWork(app, AgentState(status = AgentState.Status.THINKING, progressText = "思考中…"))
            }
        }

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
        // 正常结束时置 false；中断/报错时置 true
        var incomplete = false

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
                                    runCatching {
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
                        if (app.settings.floatingEnabled()) {
                            runCatching { FloatingService.update(ev.state) }
                        }
                    }
                    is AgentEvent.Error -> {
                        dropPlaceholder()
                        incomplete = true
                        conv.messages.add(ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "", error = ev.msg))
                        persist(conv); bump()
                    }
                    AgentEvent.Done -> Unit
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "runAgent failed", e)
            incomplete = true
        } finally {
            dropPlaceholder(); persist(conv); bump()
            _agentState.value = AgentState()
            _lastGenerationIncomplete.value = incomplete
            if (app.settings.floatingEnabled()) {
                runCatching { FloatingService.finishWork(app, AgentState(status = AgentState.Status.COMPLETED)) }
            }
        }
    }

    fun stop() {
        agentJob?.cancel(); agentJob = null
        _agentState.value = AgentState()
        // ⭐ 用户主动停止 = 未完整结束 → 允许"继续生成"
        _lastGenerationIncomplete.value = true
        if (app.settings.floatingEnabled()) {
            runCatching { FloatingService.finishWork(app, AgentState(status = AgentState.Status.COMPLETED)) }
        }
    }

    override fun onCleared() { agentJob?.cancel(); super.onCleared() }

    private suspend fun askUser(question: String, options: String): String {
        val deferred = CompletableDeferred<String>()
        pendingAnswer = deferred
        _pendingQuestion.value = PendingQuestion(
            question = question,
            options = options.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() },
        )
        _agentState.value = _agentState.value.copy(status = AgentState.Status.WAITING_USER, progressText = "等待用户回答…")
        if (app.settings.floatingEnabled()) runCatching { FloatingService.update(_agentState.value) }
        val answer = deferred.await()
        _pendingQuestion.value = null
        _agentState.value = _agentState.value.copy(status = AgentState.Status.THINKING, progressText = "继续执行…")
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