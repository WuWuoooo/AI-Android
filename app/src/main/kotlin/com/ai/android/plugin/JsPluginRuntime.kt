package com.ai.android.plugin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.ai.android.MainApp
import com.ai.android.service.FloatingService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * ⭐ v1.2.0 #7.1（原 beta6）：JS 动态悬浮窗运行时。
 *
 * 为含 "JS" 能力（或 entryPoint 以 .js 结尾）的插件，用 **隐藏 WebView（V8）** 加载
 * 插件 JS，暴露宿主 API（`window.host.*`），让插件**动态生成悬浮挂件 / 订阅 Agent 事件 /
 * 读 Token / 控面板 / 直接给 Agent 发消息**。
 *
 * 宿主 API（经 addJavascriptInterface("host") 暴露）：
 *  - host.sendToAgent(text)      直接给 Agent 发指令（跨界面，经 AgentBridge）
 *  - host.stopAgent()            暂停 Agent
 *  - host.getTokenText()         当前悬浮窗 Token 文本
 *  - host.getAgentState()        当前 Agent 状态（IDLE/THINKING/...）
 *  - host.renderWidget(jsonStr)  渲染一个动态挂件进悬浮球旁（json: {"text":"…","color":"#2563EB"}）
 *
 * 事件宿主 → JS：宿主在关键节点调 [pushEventAll]（JSON 事件），JS 在
 *   `window.__pluginEvent(type, payload)` 里订阅（OnTokenUpdate / OnAgentState / OnToolCall）。
 *
 * 说明：WebView 用 applicationContext 创建、仅执行 JS 不显示（隐藏实例），
 *   不挂到悬浮 overlay 上（overlay 里嵌可见 WebView 需 window token，成本高），
 *   插件渲染结果由宿主经 [onRenderWidget] 用原生 View 呈现到悬浮球区。
 */
class JsPluginRuntime(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tag = "JsPluginRuntime"

    /** 各插件的隐藏 WebView（仅用于 JS 执行） */
    private val webViews = LinkedHashMap<String, WebView>()

    private val json = Json { ignoreUnknownKeys = true }

        /** 插件请求渲染动态挂件 → 宿主用原生 View 呈现（FloatingService 注册） */
    var onRenderWidget: ((pluginId: String, text: String, colorHex: String?) -> Unit)? = null

    /** ⭐ v1.2.0-next #1：JS 插件动态创建/更新悬浮球本身（不只挂件） */
    var onRenderJsBall: ((pluginId: String, jsonStr: String) -> Unit)? = null
    /** ⭐ v1.2.0-next #1：JS 插件收到球点击事件（宿主在球 onClick 时回调） */
    var onJsBallClick: ((pluginId: String, x: Float, y: Float) -> Unit)? = null
    /** ⭐ v1.2.0-next #1：JS 插件收到球拖拽结束事件 */
    var onJsBallDragEnd: ((pluginId: String, x: Float, y: Float) -> Unit)? = null

    /** 插件直接给 Agent 发消息 → 宿主经 AgentBridge 复用 MainViewModel.send */
    var onSendToAgent: ((text: String) -> Unit)? = null

    /** 清空并重建：扫描启用且含 JS 能力的插件，加载其 entryPoint。 */
    fun refresh() {
        mainHandler.post {
            webViews.values.forEach { runCatching { it.destroy() } }
            webViews.clear()
            runCatching {
                val enabled = PluginManager.list(context).filter { PluginManager.isEnabled(context, it.id) }
                enabled.filter { it.capabilities.contains("JS") || it.entryPoint.endsWith(".js", ignoreCase = true) }
                    .forEach { load(it) }
            }.onFailure { Log.e(tag, "refresh failed", it) }
            Log.d(tag, "JS plugin refresh: ${webViews.size} runtime(s)")
        }
    }

    private fun load(m: PluginManifest) {
        val jsFile = File(PluginManager.pluginsDir(context), "${m.id}/${m.entryPoint}")
        if (!jsFile.exists()) {
            Log.w(tag, "JS 插件 ${m.id} entryPoint 不存在: ${m.entryPoint}")
            return
        }
        val js = runCatching { jsFile.readText() }.getOrNull() ?: return
        runCatching {
            val wv = WebView(context.applicationContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.addJavascriptInterface(JsHost(m.id), "host")
            val html = buildString {
                append("<html><head><meta charset=\"utf-8\"></head><body><script>")
                append(js)
                // 引导：插件暴露 window.__plugin = { boot(host), ... }，宿主调用 boot(host)
                append("\n;try{ if (window.__plugin && window.__plugin.boot) window.__plugin.boot(host); } catch(e){}")
                append("</script></body></html>")
            }
            wv.loadDataWithBaseURL("file:///", html, "text/html", "utf-8", null)
            webViews[m.id] = wv
            Log.d(tag, "loaded JS plugin ${m.id}")
        }.onFailure { Log.e(tag, "load ${m.id} failed", it) }
    }

    /** 宿主 → 某插件的事件分发（[eventJson] 形如 `{"type":"OnTokenUpdate","payload":{...}}`） */
    fun pushEvent(pluginId: String, eventJson: String) {
        webViews[pluginId]?.let { wv ->
            mainHandler.post {
                runCatching {
                    wv.evaluateJavascript(
                        "window.__pluginEvent && window.__pluginEvent($eventJson);", null
                    )
                }
            }
        }
    }

        /** 宿主 → 全部 JS 插件的事件分发（在 Agent 关键节点调用） */
    fun pushEventAll(eventJson: String) {
        webViews.keys.toList().forEach { pushEvent(it, eventJson) }
    }

    /** ⭐ v1.2.0-next #1：宿主 → 指定 JS 插件的球事件分发（球点击/拖拽时调用） */
    fun pushBallEvent(pluginId: String, eventType: String, payload: String) {
        pushEvent(pluginId, """{"type":"OnBallEvent","event":"$eventType","payload":$payload}""")
    }

    /** 销毁全部 WebView（设置页删除插件 / 进程退出调用） */
    fun destroy() {
        mainHandler.post {
            webViews.values.forEach { runCatching { it.destroy() } }
            webViews.clear()
        }
    }

    /** 当前已加载的 JS 插件 id 集合 */
    fun activePluginIds(): Set<String> = webViews.keys.toSet()

    /** JS → 宿主 桥（命名类防混淆剔除） */
    inner class JsHost(val pluginId: String) {

        @JavascriptInterface
        fun sendToAgent(text: String) {
            onSendToAgent?.invoke(text)
        }

        @JavascriptInterface
        fun stopAgent() {
            MainApp.instance.agentStopCallback?.invoke()
        }

        @JavascriptInterface
        fun getTokenText(): String = FloatingService.tokenTextSnapshot()

        @JavascriptInterface
        fun getAgentState(): String = FloatingService.stateSnapshot()

                /** 渲染动态挂件：jsonStr = {"text":"…","color":"#2563EB"(可选)} */
        @JavascriptInterface
        fun renderWidget(jsonStr: String) {
            runCatching {
                val obj = json.parseToJsonElement(jsonStr).jsonObject
                val text = obj["text"]?.jsonPrimitive?.content ?: "AI"
                val color = obj["color"]?.jsonPrimitive?.content
                onRenderWidget?.invoke(pluginId, text, color)
            }.onFailure { Log.w(tag, "renderWidget bad json: $jsonStr", it) }
        }

        // ==================== ⭐ v1.2.0-next #1：JS 插件动态创建/更新悬浮球本身 ====================

        /**
         * JS 插件动态创建/更新悬浮球本身（不只挂件）。
         * jsonStr 示例：{"text":"🔥","color":"#FF5722","size":52,"shape":"circle"}
         * - text: 球上显示的文字/emoji（1-3字）
         * - color: 球背景色 #RRGGBB
         * - size: 球直径 dp（24-96，默认46）
         * - shape: circle / rounded / square / capsule
         */
        @JavascriptInterface
        fun createBall(jsonStr: String) {
            onRenderJsBall?.invoke(pluginId, jsonStr)
        }

        /** 更新已有的 JS 悬浮球（参数同 createBall） */
        @JavascriptInterface
        fun updateBall(jsonStr: String) {
            onRenderJsBall?.invoke(pluginId, jsonStr)
        }

        /** 移除 JS 插件自定义球（恢复默认 "AI" 球） */
        @JavascriptInterface
        fun removeBall() {
            onRenderJsBall?.invoke(pluginId, "{\"remove\":true}")
        }

        /** 注册球点击回调：宿主在球 onClick 时调 pushBallEvent 推给 JS */
        @JavascriptInterface
        fun onBallEvent(jsonStr: String) {
            // 宿主 → JS 的事件经 pushEvent 分发，此处留作占位（实际走 pushEvent）
            Log.d(tag, "onBallEvent $pluginId: $jsonStr")
        }
    }
}
