package com.ai.android.storage

import android.content.Context
import android.content.SharedPreferences
import com.ai.android.agent.AgentConfig
import com.ai.android.provider.ProviderConfig
import com.ai.android.provider.ProviderManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 设置持久化 + ProviderManager 集成。
 * 保存 Provider 配置列表、活跃 Provider、Agent 运行参数。
 */
class SettingsRepository(context: Context, private val providers: ProviderManager) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ser = ListSerializer(ProviderConfig.serializer())

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    private fun bump() {
        _revision.value++
    }

    /** 启动时调用：把保存的 Provider 灌入 ProviderManager */
    fun loadIntoManager() {
        val list = loadConfigs()
        list.forEach { providers.upsert(it) }
        val active = prefs.getString(KEY_ACTIVE, "").orEmpty()
        if (active.isNotBlank() && list.any { it.id == active }) {
            providers.activate(active)
        } else if (list.isNotEmpty()) {
            providers.activate(list.first().id)
        }
    }

    // ---------- Provider 配置 ----------

    fun loadConfigs(): List<ProviderConfig> {
        val raw = prefs.getString(KEY_CONFIGS, null) ?: return DEFAULT_CONFIGS
        return runCatching { json.decodeFromString(ser, raw) }.getOrElse { DEFAULT_CONFIGS }
    }

    fun saveConfigs(list: List<ProviderConfig>) {
        prefs.edit().putString(KEY_CONFIGS, json.encodeToString(ser, list)).apply()
        list.forEach { providers.upsert(it) }
        bump()
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
        bump()
    }

    fun activeId(): String = prefs.getString(KEY_ACTIVE, "").orEmpty()

    fun setActive(id: String) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
        providers.activate(id)
        bump()
    }

    fun moveConfig(from: Int, to: Int) {
        val list = loadConfigs().toMutableList()
        if (from !in list.indices || to !in list.indices) return
        val item = list.removeAt(from)
        list.add(to, item)
        prefs.edit().putString(KEY_CONFIGS, json.encodeToString(ser, list)).apply()
        bump()
    }

    // ---------- Agent 参数 ----------

    fun agentConfig(): AgentConfig = AgentConfig(
        maxRounds = prefs.getInt(KEY_MAX_ROUNDS, 30).coerceIn(1, 100),
        temperature = prefs.getFloat(KEY_TEMP, 0.3f).coerceIn(0f, 2f),
        reasoningEffort = prefs.getString(KEY_REASONING, "").orEmpty(),
        systemPromptExtra = prefs.getString(KEY_PROMPT_EXTRA, "").orEmpty(),
    )

    fun setMaxRounds(v: Int) {
        prefs.edit().putInt(KEY_MAX_ROUNDS, v.coerceIn(1, 100)).apply(); bump()
    }

    fun setTemperature(v: Float) {
        prefs.edit().putFloat(KEY_TEMP, v.coerceIn(0f, 2f)).apply(); bump()
    }

    fun setReasoningEffort(v: String) {
        prefs.edit().putString(KEY_REASONING, v).apply(); bump()
    }

    fun setSystemPromptExtra(v: String) {
        prefs.edit().putString(KEY_PROMPT_EXTRA, v).apply(); bump()
    }

    companion object {
        private const val KEY_CONFIGS = "provider_configs"
        private const val KEY_ACTIVE = "active_provider_id"
        private const val KEY_MAX_ROUNDS = "agent_max_rounds"
        private const val KEY_TEMP = "agent_temperature"
        private const val KEY_REASONING = "agent_reasoning_effort"
        private const val KEY_PROMPT_EXTRA = "agent_prompt_extra"

        val DEFAULT_CONFIGS = listOf(
            ProviderConfig(id = "openai", label = "OpenAI", baseUrl = "https://api.openai.com/v1", model = "gpt-4o-mini"),
            ProviderConfig(id = "deepseek", label = "DeepSeek", baseUrl = "https://api.deepseek.com/v1", model = "deepseek-chat"),
            ProviderConfig(id = "anthropic", label = "Anthropic", protocol = com.ai.android.provider.AIProvider.Protocol.ANTHROPIC, baseUrl = "https://api.anthropic.com", model = "claude-sonnet-4-20250514"),
        )
    }
}
