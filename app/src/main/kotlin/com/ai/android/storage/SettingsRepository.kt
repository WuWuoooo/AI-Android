package com.ai.android.storage

import android.content.Context
import android.content.SharedPreferences
import com.ai.android.agent.AgentConfig
import com.ai.android.provider.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class SettingsRepository(
    context: Context,
    private val providers: ProviderManager,
) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ser = ListSerializer(ProviderConfig.serializer())

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    private fun bump() { _revision.value++ }

    fun loadIntoManager() {
        val list = loadConfigs()
        list.forEach { providers.upsert(it) }
        val active = prefs.getString(KEY_ACTIVE, "").orEmpty()
        if (active.isNotBlank() && list.any { it.id == active }) providers.activate(active)
        else if (list.isNotEmpty()) providers.activate(list.first().id)
        bindMultimodal()
        bindSearch()
    }

    fun loadConfigs(): List<ProviderConfig> {
        val raw = prefs.getString(KEY_CONFIGS, null) ?: return DEFAULT_CONFIGS
        return runCatching { json.decodeFromString(ser, raw) }.getOrElse { DEFAULT_CONFIGS }
    }

    fun saveConfigs(list: List<ProviderConfig>) {
        prefs.edit().putString(KEY_CONFIGS, json.encodeToString(ser, list)).apply()
        list.forEach { providers.upsert(it) }
        bindMultimodal(); bindSearch(); bump()
    }

    fun upsertConfig(cfg: ProviderConfig) {
        val list = loadConfigs().toMutableList()
        val idx = list.indexOfFirst { it.id == cfg.id }
        if (idx >= 0) list[idx] = cfg else list.add(cfg)
        saveConfigs(list)
    }

    fun deleteConfig(id: String) {
        val list = loadConfigs().filterNot { it.id == id }
        providers.remove(id)
        prefs.edit().putString(KEY_CONFIGS, json.encodeToString(ser, list)).apply()
        if (activeId() == id) setActive(list.firstOrNull()?.id.orEmpty())
        bindMultimodal(); bindSearch(); bump()
    }

    fun activeId(): String = prefs.getString(KEY_ACTIVE, "").orEmpty()

    fun setActive(id: String) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
        providers.activate(id)
        bindMultimodal(); bindSearch(); bump()
    }

        // ==================== 悬浮窗 ====================

    fun floatingEnabled(): Boolean = prefs.getBoolean(KEY_FLOATING, true)
    fun setFloatingEnabled(v: Boolean) { prefs.edit().putBoolean(KEY_FLOATING, v).apply(); bump() }

    // ==================== AI 操控（后台操作 + 镜像） ====================

    /** 是否允许 AI 后台操控（默认开） */
    fun aiControlEnabled(): Boolean = prefs.getBoolean(KEY_AI_CONTROL, true)

    fun setAiControlEnabled(v: Boolean) { prefs.edit().putBoolean(KEY_AI_CONTROL, v).apply(); bump() }

    /** 操控时是否显示镜像小窗（默认开，需悬浮窗权限） */
    fun mirrorEnabled(): Boolean = prefs.getBoolean(KEY_MIRROR, true)

    fun setMirrorEnabled(v: Boolean) { prefs.edit().putBoolean(KEY_MIRROR, v).apply(); bump() }

            /** 操控前是否需要确认（默认关：默认不逐个询问，会话内只在首次操控时确认一次；用户可在设置页打开以恢复每次询问） */
    fun confirmBeforeControl(): Boolean = prefs.getBoolean(KEY_CONFIRM_CONTROL, false)

    fun setConfirmBeforeControl(v: Boolean) { prefs.edit().putBoolean(KEY_CONFIRM_CONTROL, v).apply(); bump() }

    /** ⭐ 是否在第一轮对话结束后由 AI 自动总结对话标题（默认开；关闭后标题保持为用户首条截断） */
    fun autoTitleSummary(): Boolean = prefs.getBoolean(KEY_AUTO_TITLE, true)

    fun setAutoTitleSummary(v: Boolean) { prefs.edit().putBoolean(KEY_AUTO_TITLE, v).apply(); bump() }

    // ==================== 文件读取上限 ====================

    /** 返回 AI read_file 的最大字符数；0 表示不限制 */
    fun maxReadChars(): Int = prefs.getInt(KEY_MAX_READ, 100_000)

    fun setMaxReadChars(v: Int) {
        prefs.edit().putInt(KEY_MAX_READ, v.coerceAtLeast(0)).apply(); bump()
    }

        // ==================== 终端环境 ====================

        /**
     * ⭐ v1.0.0-Stable：是否启用完整 PTY 终端（com.termux:terminal-view + libtermux.so）。
     * **默认 true**：终端屏 + AI 的 terminal_exec / terminal_read 均走真 PTY
     * （Ctrl-C/D/Z 真信号、jobs/fg/bg、vim/top、ANSI 彩色、宽字符）。
     * 关闭后回退到稳定管道版（ProcessBuilder + bash -c）。
     */
    fun terminalUsePty(): Boolean = prefs.getBoolean(KEY_TERM_PTY, true)
    fun setTerminalUsePty(v: Boolean) { prefs.edit().putBoolean(KEY_TERM_PTY, v).apply(); bump() }

    /** 用户自定义 PATH 前缀（如 Termux $PREFIX/bin），空格分隔多个 */
    fun terminalPathPrefix(): String = prefs.getString(KEY_TERM_PATH, "").orEmpty()
    fun setTerminalPathPrefix(v: String) { prefs.edit().putString(KEY_TERM_PATH, v).apply(); bump() }

    /** 用户自定义 HOME 目录（默认 /sdcard） */
    fun terminalHome(): String = prefs.getString(KEY_TERM_HOME, "/sdcard").orEmpty()
    fun setTerminalHome(v: String) { prefs.edit().putString(KEY_TERM_HOME, v).apply(); bump() }

    /** 终端启动时额外执行的初始化命令（多行） */
    fun terminalInitScript(): String = prefs.getString(KEY_TERM_INIT, "").orEmpty()
    fun setTerminalInitScript(v: String) { prefs.edit().putString(KEY_TERM_INIT, v).apply(); bump() }

    // ==================== 多模态绑定 ====================

    fun multimodalBinding(cap: String): String = prefs.getString("mm_bind_$cap", "").orEmpty()

    fun setMultimodalBinding(cap: String, providerId: String) {
        prefs.edit().putString("mm_bind_$cap", providerId).apply()
        bindMultimodal(); bindSearch(); bump()
    }

    private fun bindMultimodal() {
        val configs = loadConfigs()

        fun pick(cap: String): ProviderConfig? {
            val boundId = multimodalBinding(cap)
            if (boundId.isNotBlank()) {
                configs.firstOrNull { it.id == boundId }?.let { return it }
            }
            val active = configs.firstOrNull { it.id == activeId() }
            if (active != null && cap in active.capabilities) return active
            return configs.firstOrNull { cap in it.capabilities }
        }

                pick("IMAGE")?.let { cfg ->
            // ⭐ 第五轮 Bug 3：智谱 GLM-Image 走专用实现（响应是 URL，需下载转 b64；端点归一化修 404）
            val isZhipu = cfg.id == "zhipu" || cfg.baseUrl.contains("bigmodel", ignoreCase = true)
            val isMimo = cfg.baseUrl.contains("xiaomimimo", ignoreCase = true)
            val p: MultimodalProvider = when {
                isZhipu -> ZhipuImageMultimodal(cfg.label, cfg.baseUrl, cfg.apiKey,
                    model = cfg.model.ifBlank { "glm-image" })
                cfg.id == "qwen" -> QwenMultimodal(cfg.label, cfg.baseUrl, cfg.apiKey, cfg.model)
                isMimo -> OpenAIMultimodal(cfg.label, cfg.baseUrl, cfg.apiKey, cfg.model)
                else -> OpenAIMultimodal(cfg.label, cfg.baseUrl, cfg.apiKey, cfg.model)
            }
            providers.multimodal.setImage(p)
        } ?: providers.multimodal.setImage(null)

        pick("VISION")?.let { cfg ->
            providers.multimodal.setVision(
                when {
                    cfg.protocol == AIProvider.Protocol.ANTHROPIC ->
                        AnthropicVision(cfg.label, cfg.baseUrl, cfg.apiKey, cfg.model)
                    cfg.id == "qwen" -> QwenMultimodal(cfg.label, cfg.baseUrl, cfg.apiKey, cfg.model)
                    else -> OpenAIMultimodal(cfg.label, cfg.baseUrl, cfg.apiKey, cfg.model)
                }
            )
        } ?: providers.multimodal.setVision(null)

                        // ⭐ 第五轮 Bug 2（加强）：TTS 绑定。
        //  ① 优先走常规 pick("TTS")（用户勾了 TTS 能力或显式绑定）；
        //  ② 若没选到，但存在 baseUrl 含 xiaomimimo 的 Provider（MiMo），自动绑 TTS（不依赖用户是否勾了 TTS 能力），
        //     避免主人配了 MiMo 却没在能力里勾 TTS → 朗读走不到 MiMo。
        val ttsCfg = pick("TTS")
            ?: configs.firstOrNull { it.baseUrl.contains("xiaomimimo", ignoreCase = true) && it.apiKey.isNotBlank() }
        ttsCfg?.let { cfg ->
            val isMimo = cfg.baseUrl.contains("xiaomimimo", ignoreCase = true) ||
                cfg.label.contains("MiMo", ignoreCase = true)
            val p: MultimodalProvider = when {
                isMimo -> MimoMultimodal(cfg.label, cfg.baseUrl, cfg.apiKey,
                    model = cfg.model.ifBlank { "mimo-v2.5-tts" })
                cfg.id == "qwen" -> QwenMultimodal(cfg.label, cfg.baseUrl, cfg.apiKey, cfg.model)
                else -> OpenAIMultimodal(cfg.label, cfg.baseUrl, cfg.apiKey, cfg.model)
            }
            providers.multimodal.setTts(p)
        } ?: providers.multimodal.setTts(null)

        pick("ASR")?.let { cfg ->
            val p = if (cfg.id == "qwen") QwenMultimodal(cfg.label, cfg.baseUrl, cfg.apiKey, cfg.model)
            else OpenAIMultimodal(cfg.label, cfg.baseUrl, cfg.apiKey, cfg.model)
            providers.multimodal.setAsr(p)
        } ?: providers.multimodal.setAsr(null)
    }

    private fun bindSearch() {
        val configs = loadConfigs()
        val boundId = multimodalBinding("SEARCH")
        val cfg = when {
            boundId.isNotBlank() -> configs.firstOrNull { it.id == boundId }
            else -> configs.firstOrNull { "SEARCH" in it.capabilities }
        }
        if (cfg == null || cfg.apiKey.isBlank()) { SearchManager.set(DuckDuckGoSearch()); return }
        val url = cfg.baseUrl.lowercase()
        val label = cfg.label.lowercase()
        val model = cfg.model.lowercase()
        val p: SearchProvider = when {
            url.contains("tavily") -> TavilySearch(cfg.apiKey)
            url.contains("serper") || model.contains("serper") -> SerperSearch(cfg.apiKey)
            url.contains("brave") -> BraveSearch(cfg.apiKey)
            url.contains("bigmodel") || url.contains("zhipu") || url.contains("glm")
                || label.contains("智谱") || label.contains("zhipu")
                || model.contains("zhipu") -> ZhipuSearch(cfg.apiKey)
            else -> DuckDuckGoSearch()
        }
        SearchManager.set(p)
    }

    // ==================== Agent 参数 ====================

                                fun agentConfig(): AgentConfig = AgentConfig(
        maxRounds = prefs.getInt(KEY_MAX_ROUNDS, 0),
        temperature = prefs.getFloat(KEY_TEMP, 0.3f).coerceIn(0f, 2f),
        reasoningEffort = prefs.getString(KEY_REASONING, "").orEmpty(),
        systemPromptExtra = prefs.getString(KEY_PROMPT_EXTRA, "").orEmpty(),
        networkRetries = prefs.getInt(KEY_STREAM_RETRIES, 2).coerceIn(0, 10),
        contextCompressRounds = prefs.getInt(KEY_CTX_COMPRESS, 0).coerceIn(0, 100),
    )

    fun setMaxRounds(v: Int) { prefs.edit().putInt(KEY_MAX_ROUNDS, v.coerceIn(0, 100)).apply(); bump() }
    fun setTemperature(v: Float) { prefs.edit().putFloat(KEY_TEMP, v.coerceIn(0f, 2f)).apply(); bump() }
    fun setReasoningEffort(v: String) { prefs.edit().putString(KEY_REASONING, v).apply(); bump() }
    fun setSystemPromptExtra(v: String) { prefs.edit().putString(KEY_PROMPT_EXTRA, v).apply(); bump() }

        /** 流式 / HTTP 请求失败后的自动重试次数（0 = 禁用，默认 2） */
    fun streamRetries(): Int = prefs.getInt(KEY_STREAM_RETRIES, 2).coerceIn(0, 10)
    fun setStreamRetries(v: Int) { prefs.edit().putInt(KEY_STREAM_RETRIES, v.coerceIn(0, 10)).apply(); bump() }

        /** 上下文自动压缩触发轮次（0 = 禁用，默认 0；超过 N 轮后 LLM 总结旧轮次压缩上下文） */
    fun contextCompressRounds(): Int = prefs.getInt(KEY_CTX_COMPRESS, 0).coerceIn(0, 100)
    fun setContextCompressRounds(v: Int) { prefs.edit().putInt(KEY_CTX_COMPRESS, v.coerceIn(0, 100)).apply(); bump() }

    // ==================== ⭐ v1.1.0 #4：多语言 ====================

    /** 当前界面语言（"zh" / "en" / "follow"），默认跟随系统（zh） */
    fun appLanguage(): String = prefs.getString(KEY_APP_LANGUAGE, com.ai.android.i18n.I18nManager.ZH)
        ?: com.ai.android.i18n.I18nManager.ZH

    /** 切换界面语言：持久化 + 同步 I18nManager 热切换 */
    fun setAppLanguage(v: String) {
        prefs.edit().putString(KEY_APP_LANGUAGE, v).apply()
        runCatching { com.ai.android.i18n.I18nManager.setLanguage(v) }
        bump()
    }

    // ==================== ⭐ v1.1.0 #2：Shizuku ====================

        /** 是否启用 Shizuku 提权执行（shell user uid=2000）；未授权/未装则自动回退 app 沙箱 */
    fun shizukuEnabled(): Boolean = prefs.getBoolean(KEY_SHIZUKU, false)

        /** 记录 Shizuku 授权状态（设置页授权成功后调用） */
    fun setShizukuEnabled(v: Boolean) {
        if (v != shizukuEnabled()) {
            prefs.edit().putBoolean(KEY_SHIZUKU, v).apply()
            bump()
        }
    }

    // ==================== ⭐ v1.2.0-next #2：主题风格预设 ====================

        /** 当前主题风格预设："native" / "minimal" / "brutal"，默认 "minimal"（现代极简易主） */
        fun themeStyle(): String = prefs.getString(KEY_THEME_STYLE, "minimal") ?: "minimal"
    fun setThemeStyle(v: String) {
        prefs.edit().putString(KEY_THEME_STYLE, v).apply()
        bump()
    }

    // ==================== ⭐ v1.2.0-next 草稿持久化（#6 崩溃保留输入框内容）====================

    /** 输入框未发送的草稿（按对话 id 隔离；崩溃 / 进程被杀后重开可恢复） */
    fun inputDraft(convId: String): String = prefs.getString("input_draft_$convId", "").orEmpty()

    fun setInputDraft(convId: String, text: String) {
        val e = prefs.edit()
        if (text.isEmpty()) e.remove("input_draft_$convId") else e.putString("input_draft_$convId", text)
        e.apply()
    }

    companion object {
                private const val KEY_CONFIGS = "provider_configs"
        private const val KEY_ACTIVE = "active_provider_id"
        private const val KEY_MAX_ROUNDS = "agent_max_rounds"
        private const val KEY_TEMP = "agent_temperature"
        private const val KEY_REASONING = "agent_reasoning_effort"
                                private const val KEY_PROMPT_EXTRA = "agent_prompt_extra"
        private const val KEY_STREAM_RETRIES = "agent_stream_retries"
        private const val KEY_CTX_COMPRESS = "agent_ctx_compress_rounds"
        private const val KEY_FLOATING = "floating_enabled"
        private const val KEY_MAX_READ = "max_read_chars"
                private const val KEY_TERM_PATH = "term_path_prefix"
        private const val KEY_TERM_HOME = "term_home"
        private const val KEY_TERM_INIT = "term_init_script"
        private const val KEY_TERM_PTY = "term_use_pty"
        private const val KEY_AI_CONTROL = "ai_control_enabled"
        private const val KEY_MIRROR = "ai_mirror_enabled"
                        private const val KEY_CONFIRM_CONTROL = "ai_confirm_before_control"
        private const val KEY_AUTO_TITLE = "ai_auto_title_summary"
        // ⭐ v1.1.0
        private const val KEY_APP_LANGUAGE = "app_language"
                private const val KEY_SHIZUKU = "shizuku_enabled"
        private const val KEY_THEME_STYLE = "theme_style"

        val DEFAULT_CONFIGS = listOf(
            ProviderConfig(
                id = "openai", label = "OpenAI",
                baseUrl = "https://api.openai.com/v1", model = "gpt-5.6-sol",
                capabilities = listOf("CHAT", "VISION", "IMAGE", "TTS", "ASR", "REALTIME"),
            ),
            ProviderConfig(
                id = "anthropic", label = "Anthropic",
                protocol = AIProvider.Protocol.ANTHROPIC,
                baseUrl = "https://api.anthropic.com", model = "claude-opus-5",
                capabilities = listOf("CHAT", "VISION"),
            ),
            ProviderConfig(
                id = "deepseek", label = "DeepSeek",
                baseUrl = "https://api.deepseek.com/v1", model = "deepseek-v4-pro",
                capabilities = listOf("CHAT", "VISION"),
            ),
            ProviderConfig(
                id = "kimi", label = "Kimi",
                baseUrl = "https://api.moonshot.cn/v1", model = "kimi-k3",
                capabilities = listOf("CHAT", "VISION"),
            ),
            ProviderConfig(
                id = "qwen", label = "通义千问",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                model = "qwen3.8-max",
                capabilities = listOf("CHAT", "VISION", "IMAGE", "TTS", "ASR"),
            ),
            ProviderConfig(
                id = "zhipu", label = "智谱",
                baseUrl = "https://open.bigmodel.cn/api/paas/v4/web_search",
                model = "glm-web-search",
                capabilities = listOf("SEARCH"),
            ),
            ProviderConfig(
                id = "tavily", label = "Tavily 搜索",
                baseUrl = "https://api.tavily.com", model = "tavily-search",
                capabilities = listOf("SEARCH"),
            ),
        )
    }
}