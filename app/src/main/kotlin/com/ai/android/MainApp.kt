package com.ai.android

import android.app.Application
import com.ai.android.agent.AgentCore
import com.ai.android.agent.ToolRegistry
import com.ai.android.agent.tools.TaskStore
import com.ai.android.plugin.PluginRegistry
import com.ai.android.provider.ProviderManager
import com.ai.android.storage.AppDatabase
import com.ai.android.storage.MemoryStore
import com.ai.android.storage.ProjectStore
import com.ai.android.storage.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MainApp : Application() {

    /** 应用级协程作用域，不随 ViewModel 取消 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** ⚠️ 必须走 AppDatabase.get()，否则 migrations 不生效 */
    val database: AppDatabase by lazy { AppDatabase.get(this) }

    val providerManager: ProviderManager by lazy { ProviderManager() }
    val settings: SettingsRepository by lazy { SettingsRepository(this, providerManager) }
    val memory: MemoryStore by lazy { MemoryStore(this) }
    val toolRegistry: ToolRegistry by lazy { ToolRegistry(this, settings) }
    val projectStore: ProjectStore by lazy { ProjectStore(database) }

        val agentCore: AgentCore by lazy {
        AgentCore(
            providers = providerManager,
            registry = toolRegistry,
            config = { settings.agentConfig() },
            // ⭐ 每次请求时读取最新的长期记忆
            memoryPromptProvider = { memory.summaryBlock() },
        )
    }

    /** 插件注册表（beta5 静态挂件；beta6 将加载 JS） */
    val pluginRegistry: PluginRegistry by lazy { PluginRegistry(applicationContext).also { it.init() } }

    /** 悬浮窗挂件点击 → 暂停 Agent（由 MainViewModel 注入） */
    @Volatile var agentStopCallback: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings.loadIntoManager()
        toolRegistry.registerDefaultTools(memory)
        TaskStore.init(this)
        // beta5：初始化插件注册表（扫描已导入静态挂件）
        pluginRegistry
    }

    companion object {
        lateinit var instance: MainApp
            private set
    }
}