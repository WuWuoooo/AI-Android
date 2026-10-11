package com.ai.android

import android.app.Application
import com.ai.android.agent.AgentCore
import com.ai.android.agent.ToolRegistry
import com.ai.android.agent.tools.TaskStore
import com.ai.android.i18n.I18nManager
import com.ai.android.plugin.JsPluginRuntime
import com.ai.android.plugin.PluginRegistry
import com.ai.android.service.FloatingService
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
            // ⭐ 第五轮 #4：每次请求注入「全局长期记忆 + 该对话所属项目的独立记忆」
            memoryPromptProvider = { conv ->
                val parts = mutableListOf<String>()
                val global = memory.summaryBlock()
                if (global.isNotBlank()) parts.add(global)
                if (!conv.projectId.isBlank()) {
                    val proj = memory.projectSummaryBlock(conv.projectId)
                    if (proj.isNotBlank()) parts.add(proj)
                }
                parts.joinToString("\n")
            },
        )
    }

        /** 插件注册表（beta5 静态挂件；beta6 将加载 JS） */
    val pluginRegistry: PluginRegistry by lazy { PluginRegistry(applicationContext).also { it.init() } }

    /** ⭐ v1.2.0 #7.1：JS 动态悬浮窗运行时（隐藏 WebView 沙箱加载含 "JS" 能力的插件） */
    val jsPluginRuntime: JsPluginRuntime by lazy {
        JsPluginRuntime(applicationContext).also { rt ->
            // JS 插件请求渲染动态挂件 → 宿主用原生 View 呈现到悬浮球区
            rt.onRenderWidget = { pluginId, text, colorHex ->
                FloatingService.jsRenderWidgetHandler?.invoke(pluginId, text, colorHex)
            }
            // JS 插件直接给 Agent 发消息 / 暂停（跨界面，经 AgentBridge）
            rt.onSendToAgent = { text ->
                runCatching {
                    com.ai.android.AgentBridge.postMessage(text)
                }
            }
        }
    }

    /** 悬浮窗挂件点击 → 暂停 Agent（由 MainViewModel 注入） */
    @Volatile var agentStopCallback: (() -> Unit)? = null

                override fun onCreate() {
        super.onCreate()
        instance = this
        // ⭐ v1.1.0 #4：初始化多语言（载入持久化语言 + 自定义语言包）
        I18nManager.init(this)
        settings.loadIntoManager()
        toolRegistry.registerDefaultTools(memory)
        TaskStore.init(this)
                // beta5：初始化插件注册表（扫描已导入静态挂件）
        pluginRegistry

        // ⭐ v1.2.0 #7.1：JS 动态悬浮窗运行时
        //    扫描启用且含 "JS" 能力的插件，加载其 entryPoint（.js）到隐藏 WebView
        jsPluginRuntime

                // JS 插件渲染的动态挂件 → 用原生 View 呈现到悬浮球区（复用 renderPluginWidgets）
        FloatingService.jsRenderWidgetHandler = { pluginId, text, colorHex ->
            // 简化：经 FloatingService 重建球旁挂件区，把 JS 请求的挂件作为 TextView 加入
            runCatching {
                FloatingService.pushJsWidget(applicationContext, text, colorHex)
            }
        }

        // ⭐ v1.2.0-next #1：JS 插件动态创建/更新悬浮球本身
        jsPluginRuntime.onRenderJsBall = { pluginId, jsonStr ->
            runCatching {
                FloatingService.renderJsBall(applicationContext, pluginId, jsonStr)
            }
        }

        // ⭐ v1.2.0-hotfix #2：Shizuku 初始化（对照官方 demo Application 静态块做法）
        //    Sui.init + addBinderReceivedListenerSticky（非 sticky 在 Activity.onCreate 注册太晚，
        //    binder 已经经 ShizukuProvider 推入，非 sticky 不会补发 → 永远收不到）
        com.ai.android.service.ShizukuManager.initForApp(this)
    }

    companion object {
        lateinit var instance: MainApp
            private set
    }
}