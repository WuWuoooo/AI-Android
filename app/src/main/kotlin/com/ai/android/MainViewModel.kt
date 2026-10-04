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
import com.ai.android.model.TokenEntry
import com.ai.android.model.TokenExportReport
import com.ai.android.model.TokenTotals
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainViewModel(private val app: MainApp) : ViewModel() {

    private val TAG = "MainViewModel"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** ⭐ 单条对话 payload 的字符上限；超过则剥离图片，避免读取时超过 CursorWindow 限制 */
    private val maxPayloadChars = 1_000_000

    /** ⭐ 分块读取 payload 的块大小（字符），保守取 40 万，避免超过 ~2MB 的 CursorWindow */
    private val payloadChunkSize = 400_000

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

    /** ⭐ 加载历史对话失败时的提示（null = 正常） */
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError

    data class PendingQuestion(val question: String, val options: List<String>)

    private val _pendingQuestion = MutableStateFlow<PendingQuestion?>(null)
    val pendingQuestion: StateFlow<PendingQuestion?> = _pendingQuestion

        private var pendingAnswer: CompletableDeferred<String>? = null
    private var agentJob: Job? = null

    /** ⭐ 已被 AI 自动总结过标题的对话 id（避免同一对话每轮都重复总结；首轮结束时并发总结一次） */
    private val titleSummarized = java.util.Collections.synchronizedSet(
        java.util.HashSet<String>()
    )

    val settings get() = app.settings
    val providerManager get() = app.providerManager
    val projects get() = app.projectStore.projects

                init {
        app.toolRegistry.askUserHandler = { question, options -> askUser(question, options) }
        // ⭐ 终端会话按对话绑定：AI 的 terminal_exec / terminal_read 使用当前对话的常驻会话
        app.toolRegistry.convIdProvider = { current.value?.id.orEmpty() }
        app.agentStopCallback = { stop() }
        val bootstrap = Conversation(id = UUID.randomUUID().toString(), title = "新会话")
        _conversations.value = listOf(bootstrap)
        _current.value = bootstrap

        viewModelScope.launch {
            runCatching { app.projectStore.init(); refreshMemoryList() }
            try {
                val loaded = loadConversations()
                Log.d(TAG, "loaded ${loaded.size} conversations from DB")
                if (loaded.isNotEmpty()) {
                    _conversations.value = loaded
                    _current.value = loaded.first()
                    bump()
                }
            } catch (e: Throwable) {
                // ⭐ 不再静默吞错：记录 + 提示，方便定位
                Log.e(TAG, "loadConversations 失败", e)
                _loadError.value = "加载历史对话失败：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    // ==================== 持久化 ====================

    /**
     * ⭐ 加载全部对话。
     *
     * 关键：先只读元信息（不含 payload），再按 id 分块读 payload。
     * 直接 `SELECT *` 时，任何一条 payload 超过 CursorWindow 上限（约 2MB）
     * 都会让整个查询抛异常，导致全部对话都加载不出来。
     */
    private suspend fun loadConversations(): List<Conversation> {
        val metas = app.database.conversationDao().allMeta()
        val result = ArrayList<Conversation>(metas.size)
        var failed = 0
        var lastErr: String? = null
        for (meta in metas) {
            val r = runCatching { loadOneConversation(meta.id) }
            val conv = r.getOrNull()
            if (conv != null) {
                result.add(conv)
            } else {
                failed++
                lastErr = r.exceptionOrNull()?.message
                Log.e(TAG, "对话 ${meta.id} 加载失败: $lastErr")
            }
        }
        if (failed > 0) {
            _loadError.value = "有 $failed/${metas.size} 个对话加载失败（最后一条：$lastErr）"
        }
        return result
    }

    /**
     * ⭐ 按 id 读取单条对话。
     * payload 较小时一次性读取；超过阈值时用 substr 分块拼接，避开 CursorWindow 限制。
     */
    private suspend fun loadOneConversation(id: String): Conversation? {
        val dao = app.database.conversationDao()
        val len = dao.payloadLength(id) ?: return null
        val payload: String = if (len <= payloadChunkSize) {
            dao.payload(id) ?: return null
        } else {
            val sb = StringBuilder(len)
            var start = 1
            while (start <= len) {
                val part = dao.payloadChunk(id, start, payloadChunkSize) ?: break
                sb.append(part)
                if (part.length < payloadChunkSize) break
                start += payloadChunkSize
            }
            sb.toString()
        }
        return json.decodeFromString(Conversation.serializer(), payload)
    }

    private fun persist(conv: Conversation) {
        app.appScope.launch {
            runCatching {
                // ⭐ 限制 payload 大小：超过上限则从旧到新剥离 images，
                //    避免存出无法读取的超大对话
                val safe = shrinkForStorage(conv)
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

    /**
     * ⭐ 控制单条对话 payload 的大小。
     * 先剥离单条消息里过大的 images；若整体仍超上限，则从旧到新继续剥离，
     * 直到序列化后不超过 maxPayloadChars。
     */
    private fun shrinkForStorage(conv: Conversation): Conversation {
        // 单条消息 images 过大 → 直接清空
        var msgs = conv.messages.map { m ->
            if (m.images.sumOf { it.length } > 500_000) m.copy(images = emptyList()) else m
        }.toMutableList()

        var encoded = json.encodeToString(Conversation.serializer(), conv.copy(messages = msgs))
        if (encoded.length <= maxPayloadChars) return conv.copy(messages = msgs)

        // 仍超限：从旧到新剥离 images
        for (i in msgs.indices) {
            if (msgs[i].images.isEmpty()) continue
            msgs[i] = msgs[i].copy(images = emptyList())
            encoded = json.encodeToString(Conversation.serializer(), conv.copy(messages = msgs))
            if (encoded.length <= maxPayloadChars) break
        }
        return conv.copy(messages = msgs)
    }

    // ==================== 会话管理 ====================

        fun newConversation() {
        val c = Conversation(id = UUID.randomUUID().toString(), title = "新会话")
        _conversations.value = _conversations.value + c
        _current.value = c
        _quotedMessage.value = null
        _lastGenerationIncomplete.value = false
        clearDrafts()   // ⭐ 切换对话：清除临时编辑草稿
        persist(c); bump()
    }

    fun switchConversation(id: String) {
        val c = _conversations.value.firstOrNull { it.id == id } ?: return
        _current.value = c
        _quotedMessage.value = null
        _lastGenerationIncomplete.value = false
        clearDrafts()   // ⭐ 切换对话：清除临时编辑草稿
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

    // ==================== 对话导出 / 导入 ====================

    /** 把某个对话序列化为 JSON 文本（裸 Conversation，导入时直接解析）。 */
    fun exportConversationJson(convId: String): String? {
        val conv = _conversations.value.firstOrNull { it.id == convId } ?: return null
        return json.encodeToString(Conversation.serializer(), conv)
    }

    /** 解析导入文本为 Conversation（兼容裸 Conversation JSON 与 {data:{...}} 包裹格式）。 */
    fun parseConversationFromImport(text: String): Conversation? {
        val trimmed = text.trim()
        // 1) 直接按 Conversation 解析（导出即此格式）
        runCatching { json.decodeFromString(Conversation.serializer(), trimmed) }
            .onSuccess { return it }
        // 2) 兼容带 data 包裹的格式
        return runCatching {
            val root = json.parseToJsonElement(trimmed)
            val element = if (root is kotlinx.serialization.json.JsonObject &&
                root["data"] != null) root["data"]!! else root
            json.decodeFromJsonElement(Conversation.serializer(), element)
        }.getOrNull()
    }

    /**
     * 导入对话：重新生成 id（避免与现有冲突），并入会话列表并持久化。
     * @return 导入后的新对话
     */
    fun importConversationJson(text: String): Conversation? {
        val conv = parseConversationFromImport(text) ?: return null
        val newId = UUID.randomUUID().toString()
        val imported = conv.copy(
            id = newId,
            title = conv.title.ifBlank { "导入的对话" },
            messages = conv.messages.map { m -> m.copy(id = UUID.randomUUID().toString()) }.toMutableList(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        _conversations.value = _conversations.value + imported
        _current.value = imported
        _quotedMessage.value = null
        _lastGenerationIncomplete.value = false
        viewModelScope.launch {
            runCatching {
                app.database.conversationDao().upsert(
                    ConversationEntity(
                        id = imported.id, title = imported.title,
                        createdAt = imported.createdAt, updatedAt = imported.updatedAt,
                        payload = json.encodeToString(Conversation.serializer(), imported),
                        projectId = imported.projectId,
                    )
                )
            }.onFailure { Log.e(TAG, "import persist failed", it) }
        }
        bump()
        return imported
    }

        /** 生成建议文件名，例如：对话-<标题>-20261002-1234.json */
    fun suggestedExportFileName(conv: Conversation): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val safe = conv.title.replace(Regex("[\\/:*?\"<>\\\\]"), "_").trim().ifBlank { "对话" }
        return "对话-$safe-$stamp.json"
    }

    // ==================== Token 统计 ====================

    /** 当前对话的 Token 累计（所有非 null 的 TokenStats 求和） */
    fun tokenTotalsForCurrent(): TokenTotals {
        val c = _current.value ?: return TokenTotals()
        return c.totalTokens()
    }

    /** 某对话的 Token 累计 */
    fun tokenTotalsFor(convId: String): TokenTotals {
        val c = _conversations.value.firstOrNull { it.id == convId } ?: return TokenTotals()
        return c.totalTokens()
    }

    /** 生成 Token 导出 JSON（当前对话） */
    fun exportTokenJson(convId: String): String? {
        val conv = _conversations.value.firstOrNull { it.id == convId } ?: return null
        val totals = conv.totalTokens()
        val entries = conv.messagesWithStats().map { m ->
            TokenEntry(
                messageId = m.id,
                role = m.role.name,
                stats = m.tokenStats!!,
            )
        }
                val report = TokenExportReport(
            conversationId = conv.id,
            title = conv.title,
            totals = totals,
            exportedAt = System.currentTimeMillis(),
            perMessage = entries,
        )
        return json.encodeToString(TokenExportReport.serializer(), report)
    }

            /** 清除某对话所有消息的 tokenStats（不影响对话内容） */
    fun clearTokenStats(convId: String) {
        val conv = _conversations.value.firstOrNull { it.id == convId } ?: return
        // 就地改（Conversation.messages 是 val，不能重新赋值）
        for (i in conv.messages.indices) {
            val m = conv.messages[i]
            if (m.tokenStats != null) conv.messages[i] = m.copy(tokenStats = null)
        }
        _conversations.value = _conversations.value.map { if (it.id == conv.id) conv else it }
        if (_current.value?.id == conv.id) _current.value = conv
        persist(conv)
        bump()
        runCatching { FloatingService.setTokenText(app, "") }
    }

    // ==================== 记忆 ====================

    fun refreshMemoryList() { _memoryList.value = app.memory.list().toList() }
    fun saveMemory(key: String, content: String) { app.memory.save(key, content); refreshMemoryList() }
    fun deleteMemory(key: String) { app.memory.delete(key); refreshMemoryList() }

        // ==================== 引用 ====================

    fun quoteMessage(msg: ChatMessage) { _quotedMessage.value = msg }
    fun clearQuote() { _quotedMessage.value = null }

    // ==================== ⭐ 消息编辑 / 删除（用户长按菜单） ====================

    /** ⭐ 临时编辑草稿（内存，切换对话 / 退出 App 即清除，不落盘）。
     *  keyed by messageId。满足"修改内容保存直到退出软件或切换对话窗口"。 */
    private val draftEdits = mutableMapOf<String, String>()

    /** 取某消息的编辑草稿（无则返回 null，UI 回退显示原文） */
    fun draftEdit(msgId: String): String? = draftEdits[msgId]

    /** 保存用户消息的编辑内容（仅改展示，不重新生成、不落盘） */
    fun editUserMessage(msgId: String, newContent: String) {
        draftEdits[msgId] = newContent
        bump()
    }

    /** 删除单条消息（AI / 用户皆可） */
    fun deleteMessage(msgId: String) {
        val conv = _current.value ?: return
        conv.messages.removeAll { it.id == msgId }
        draftEdits.remove(msgId)
        persist(conv); bump()
    }

    /** ⭐ 多选删除：批量删除选中消息（AI / 用户皆可） */
    fun deleteMessages(ids: Set<String>) {
        if (ids.isEmpty()) return
        val conv = _current.value ?: return
        conv.messages.removeAll { it.id in ids }
        ids.forEach { draftEdits.remove(it) }
        persist(conv); bump()
    }

    /** 切换对话 / 新建对话时清除临时编辑草稿（满足"切换对话窗口即清除"） */
    private fun clearDrafts() { draftEdits.clear() }

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
        // ⭐ 新一次 Agent 任务 → 复位 AI 操控"会话内已确认"标记（若设置开启逐次确认，则本次任务内只问一次）
        runCatching { app.toolRegistry.aiControlGuard.beginSession() }

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
                        if (app.settings.floatingEnabled()) {
                            runCatching { FloatingService.pushStreaming(app, "reasoning", ev.text) }
                        }
                        bump()
                    }
                    is AgentEvent.ContentDelta -> {
                        content.append(ev.text)
                        updateMessage(conv, ensurePlaceholder()) { it.copy(content = content.toString()) }
                        if (app.settings.floatingEnabled()) {
                            runCatching { FloatingService.pushStreaming(app, "content", ev.text) }
                        }
                        bump()
                    }
                                        is AgentEvent.MessageDone -> {
                        dropPlaceholder()
                        content.clear(); reasoning.clear()
                        persist(conv)
                        // ⭐ 首轮对话结束后并发触发一次标题总结（不阻塞主流程）：
                        //   - 仅当该对话 USER 消息恰好 1 条（即第一轮）
                        //   - 尚未被自动总结过（去重）
                        //   - 设置开关 autoTitleSummary 开启
                        // 旧实现用 title 长度判定，因 send() 已把 title 设为用户首条前 16 字，
                        // 导致"有时触发有时不触发"。现改为按"首轮结束"稳定判定。
                        if (app.settings.autoTitleSummary() &&
                            !titleSummarized.contains(conv.id) &&
                            conv.messages.count { it.role == ChatMessage.Role.USER } == 1
                        ) {
                            val firstUser = conv.messages.firstOrNull { it.role == ChatMessage.Role.USER }?.content.orEmpty()
                            val aiText = ev.message.content
                            if (firstUser.isNotBlank()) {
                                titleSummarized.add(conv.id)
                                // 并发（多线程）总结：独立协程，不阻塞 Agent 主循环
                                viewModelScope.launch {
                                    runCatching {
                                        val newTitle = app.agentCore.summarizeTitle(firstUser, aiText)
                                        conv.title = newTitle
                                        conv.updatedAt = System.currentTimeMillis()
                                        _conversations.value = _conversations.value.map {
                                            if (it.id == conv.id) conv.copy(title = newTitle) else it
                                        }
                                        if (_current.value?.id == conv.id) _current.value = conv
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
                        // ⭐ 悬浮窗面板显示当前对话累计 Token
                        val total = conv.totalTokens()
                        runCatching {
                            FloatingService.setTokenText(
                                app,
                                "Token: 输入 ${total.promptTokens} / 输出 ${total.completionTokens} / 缓存 ${total.cacheHitTokens} / 共 ${total.totalTokens}",
                            )
                        }
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
            // ⭐ Agent 结束 → 无条件关闭 AI 操控镜像（无论悬浮球是否开启）
            runCatching { app.toolRegistry.stopAiMirror() }
        }
    }

    fun stop() {
        agentJob?.cancel(); agentJob = null
        _agentState.value = AgentState()
        // ⭐ 用户主动停止 = 未完整结束 → 允许"继续生成"
        _lastGenerationIncomplete.value = true
        // ⭐ 停止 AI 操控镜像
        runCatching { app.toolRegistry.stopAiMirror() }
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