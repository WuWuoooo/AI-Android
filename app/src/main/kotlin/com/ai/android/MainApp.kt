package com.ai.android

import android.app.Application
import androidx.room.Room
import com.ai.android.agent.AgentCore
import com.ai.android.agent.ToolRegistry
import com.ai.android.agent.tools.TaskStore
import com.ai.android.provider.ProviderManager
import com.ai.android.skills.SkillManager
import com.ai.android.storage.AppDatabase
import com.ai.android.storage.MemoryStore
import com.ai.android.storage.SettingsRepository

/**
 * 手动依赖注入容器（不使用 Hilt/Koin）。
 */
class MainApp : Application() {

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "ai_android.db").build()
    }

    val providerManager: ProviderManager by lazy { ProviderManager() }

    val settings: SettingsRepository by lazy { SettingsRepository(this, providerManager) }

    val memory: MemoryStore by lazy { MemoryStore(this) }

    val skills: SkillManager by lazy { SkillManager() }

    val toolRegistry: ToolRegistry by lazy { ToolRegistry(this) }

    val agentCore: AgentCore by lazy {
        AgentCore(
            providers = providerManager,
            registry = toolRegistry,
            config = { settings.agentConfig() },
            skillPromptProvider = { userText -> skills.promptFor(userText) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 初始化依赖
        settings.loadIntoManager()
        toolRegistry.registerDefaultTools(memory)
        skills.loadAll()
        TaskStore.init(this)
    }

    companion object {
        lateinit var instance: MainApp
            private set
    }
}
