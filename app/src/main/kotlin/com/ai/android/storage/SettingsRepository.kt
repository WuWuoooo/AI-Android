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

    fun moveConfig(from: Int, to: Int) {
        val list = loadConfigs().toMutableList()
        if (from !in list.indices || to !in list.indices) return
        val item = list.removeAt(from)
        list.add(to, item)
        prefs.edit().putString(KEY_CONFIGS, json.encodeToString(ser, list)).apply()
        bump()
    }

    // ==================== 多模态能力绑定 ====================

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
            providers.multimodal.setImage(
                if (cfg.id == "qwen") QwenMultimodal(cfg.label, cfg.baseUrl, cfg.apiKey, cfg.model)
                else OpenAIMultimodal(cfg.label, cfg.baseUrl, cfg.apiKey, cfg.model)
            )
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

        pick("TTS")?.let { cfg ->
            val p = if (cfg.id == "qwen") QwenMultimodal(cfg.label, cfg.baseUrl, cfg.apiKey, cfg.model)
            else OpenAIMultimodal(cfg.label, cfg.baseUrl, cfg.apiKey, cfg.model)
            providers.multimodal.setTts(p)
        } ?: providers.multimodal.setTts(null)

        pick("ASR")?.let { cfg ->
            val p = if (cfg.id == "qwen") QwenMultimodal(cfg.label, cfg.baseUrl, cfg.apiKey, cfg.model)
            else OpenAIMultimodal(cfg.label, cfg.baseUrl, cfg.apiKey, cfg.model)
            providers.multimodal.setAsr(p)
        } ?: providers.multimodal.setAsr(null)
    }

    // ==================== 联网搜索绑定 ====================

    private fun bindSearch() {
        val configs = loadConfigs()
        val boundId = multimodalBinding("SEARCH")
        val cfg = when {
            boundId.isNotBlank() -> configs.firstOrNull { it.id == boundId }
            else -> configs.firstOrNull { "SEARCH" in it.capabilities }
        }
        if (cfg == null || cfg.apiKey.isBlank()) {
            SearchManager.set(DuckDuckGoSearch())
            return
        }
        val p: SearchProvider = when {
            cfg.baseUrl.contains("tavily", true) -> TavilySearch(cfg.apiKey)
            cfg.baseUrl.contains("serper", true) || cfg.model.contains("serper", true) -> SerperSearch(cfg.apiKey)
            cfg.baseUrl.contains("brave", true) -> BraveSearch(cfg.apiKey)
            else -> DuckDuckGoSearch()
        }
        SearchManager.set(p)
    }

    // ==================== Agent 参数 ====================

    fun agentConfig(): AgentConfig = AgentConfig(
        maxRounds = prefs.getInt(KEY_MAX_ROUNDS, 30).coerceIn(1, 100),
        temperature = prefs.getFloat(KEY_TEMP, 0.3f).coerceIn(0f, 2f),
        reasoningEffort = prefs.getString(KEY_REASONING, "").orEmpty(),
        systemPromptExtra = prefs.getString(KEY_PROMPT_EXTRA, "").orEmpty(),
    )

    fun setMaxRounds(v: Int) { prefs.edit().putInt(KEY_MAX_ROUNDS, v.coerceIn(1, 100)).apply(); bump() }
    fun setTemperature(v: Float) { prefs.edit().putFloat(KEY_TEMP, v.coerceIn(0f, 2f)).apply(); bump() }
    fun setReasoningEffort(v: String) { prefs.edit().putString(KEY_REASONING, v).apply(); bump() }
    fun setSystemPromptExtra(v: String) { prefs.edit().putString(KEY_PROMPT_EXTRA, v).apply(); bump() }

    companion object {
        private const val KEY_CONFIGS = "provider_configs"
        private const val KEY_ACTIVE = "active_provider_id"
        private const val KEY_MAX_ROUNDS = "agent_max_rounds"
        private const val KEY_TEMP = "agent_temperature"
        private const val KEY_REASONING = "agent_reasoning_effort"
        private const val KEY_PROMPT_EXTRA = "agent_prompt_extra"

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
                id = "tavily", label = "Tavily 搜索",
                baseUrl = "https://api.tavily.com",
                model = "tavily-search",
                capabilities = listOf("SEARCH"),
            ),
            ProviderConfig(
                id = "serper", label = "Serper 搜索",
                baseUrl = "https://google.serper.dev",
                model = "serper-search",
                capabilities = listOf("SEARCH"),
            ),
            ProviderConfig(
                id = "brave", label = "Brave 搜索",
                baseUrl = "https://api.search.brave.com",
                model = "brave-search",
                capabilities = listOf("SEARCH"),
            ),
        )
    }
}