package com.ai.android.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** 单个 Provider 配置（可持久化到 SharedPreferences） */
@Serializable
data class ProviderConfig(
    val id: String = "default",
    val label: String = "OpenAI",
    val protocol: AIProvider.Protocol = AIProvider.Protocol.OPENAI,
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
)

/**
 * 管理多个 Provider 实例，并构建全局工具定义（OpenAI JSON Schema 兼容）。
 */
class ProviderManager {

    private val map = LinkedHashMap<String, AIProvider>()

    var activeId: String = ""
        private set

    val active: AIProvider?
        get() = map[activeId] ?: map.values.firstOrNull()

    init {
        // 预置常用 Provider 占位（启动后由 SettingsRepository 覆盖）
        upsert(ProviderConfig(id = "openai", label = "OpenAI", baseUrl = "https://api.openai.com/v1", model = "gpt-4o-mini"))
        upsert(ProviderConfig(id = "deepseek", label = "DeepSeek", baseUrl = "https://api.deepseek.com/v1", model = "deepseek-chat"))
        upsert(ProviderConfig(id = "anthropic", label = "Anthropic", protocol = AIProvider.Protocol.ANTHROPIC, baseUrl = "https://api.anthropic.com", model = "claude-sonnet-4-20250514"))
    }

    fun upsert(config: ProviderConfig): AIProvider {
        val p = create(config)
        map[config.id] = p
        if (activeId.isEmpty()) activeId = config.id
        return p
    }

    fun activate(id: String): Boolean {
        if (!map.containsKey(id)) return false
        activeId = id
        return true
    }

    fun remove(id: String) {
        map.remove(id)
        if (activeId == id) activeId = map.keys.firstOrNull().orEmpty()
    }

    fun all(): List<AIProvider> = map.values.toList()

    fun create(config: ProviderConfig): AIProvider = when (config.protocol) {
        AIProvider.Protocol.ANTHROPIC -> AnthropicProvider(config.label, config.baseUrl, config.apiKey, config.model)
        else -> OpenAIProvider(config.label, config.baseUrl, config.apiKey, config.model)
    }

    /** 全局工具定义（供 AgentCore 每次请求时传给模型） */
    fun buildTools(): List<JsonObject> = TOOLS

    companion object {

        private val TOOLS: List<JsonObject> by lazy { buildToolList() }

        private fun fn(
            name: String,
            desc: String,
            required: List<String> = emptyList(),
            props: JsonObjectBuilder.() -> Unit = {},
        ): JsonObject = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                put("description", desc)
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject(props))
                    put("required", buildJsonArray { required.forEach { add(it) } })
                })
            })
        }

        private fun JsonObjectBuilder.s(n: String, d: String) =
            put(n, buildJsonObject { put("type", "string"); put("description", d) })

        private fun JsonObjectBuilder.i(n: String, d: String) =
            put(n, buildJsonObject { put("type", "integer"); put("description", d) })

        private fun JsonObjectBuilder.b(n: String, d: String) =
            put(n, buildJsonObject { put("type", "boolean"); put("description", d) })

        private fun JsonObjectBuilder.enumOf(n: String, d: String, values: List<String>) =
            put(n, buildJsonObject {
                put("type", "string"); put("description", d)
                put("enum", buildJsonArray { values.forEach { add(it) } })
            })

        private fun buildToolList(): List<JsonObject> = listOf(
            // ---------- 文件 ----------
            fn("read_file", "读取文件全文（大文件会被截断）", listOf("path")) { s("path", "文件绝对路径") },
            fn("write_file", "创建新文件或覆写已有文件", listOf("path", "content")) {
                s("path", "文件绝对路径"); s("content", "完整文件内容")
            },
            fn("edit_file", "精确替换文件中的一段内容（改局部用这个，不要全文重写）", listOf("path", "old_string", "new_string")) {
                s("path", "文件路径"); s("old_string", "要被替换的原文（必须逐字符一致）"); s("new_string", "替换后的内容"); b("replace_all", "是否替换所有匹配，默认 false")
            },
            fn("list_files", "列出目录下一层内容", listOf("path")) { s("path", "目录路径") },
            fn("list_dir_tree", "把目录列成树形结构", listOf("path")) {
                s("path", "根目录路径"); i("max_depth", "最大深度，默认 3"); i("max_entries", "最多条目数，默认 300")
            },
            fn("search_files", "按文件名或内容搜索文件", listOf("path", "keyword")) {
                s("path", "搜索起始目录"); s("keyword", "关键词，不区分大小写"); b("in_content", "true=搜索文件内容，false=只匹配文件名")
            },
            fn("create_dir", "创建目录（含中间目录）", listOf("path")) { s("path", "目录路径") },
            fn("delete_file", "删除文件或目录", listOf("path")) {
                s("path", "路径"); b("recursive", "删除目录时是否连同内容一起删")
            },
            fn("move_file", "移动文件或目录", listOf("src", "dst")) {
                s("src", "源路径"); s("dst", "目标路径"); b("overwrite", "目标存在时是否覆盖")
            },
            fn("copy_file", "复制文件或目录", listOf("src", "dst")) {
                s("src", "源路径"); s("dst", "目标路径"); b("overwrite", "目标存在时是否覆盖")
            },
            fn("rename_file", "重命名文件或目录（只改名字）", listOf("path", "new_name")) {
                s("path", "原完整路径"); s("new_name", "新文件名（不带路径分隔符）")
            },
            // ---------- 系统 ----------
            fn("run_shell_command", "在手机执行 shell 命令（无 root，普通应用权限）", listOf("command")) {
                s("command", "sh 语法命令"); i("timeout_sec", "超时秒数，默认 15，最大 60")
            },
            fn("run_js", "在 WebView V8 引擎执行 JavaScript", listOf("code")) {
                s("code", "JS 代码，用 __send(结果) 返回"); i("timeout_sec", "超时秒数，默认 15，最大 60")
            },
            fn("terminal_exec", "在内置常驻终端执行命令（支持长任务/装库/跑 Python）", listOf("command")) {
                s("command", "sh 语法命令"); i("timeout_sec", "等待超时秒数，默认 30，最大 120")
            },
            fn("terminal_read", "读取内置终端最近输出", emptyList()) { i("lines", "读取行数，默认 40，最大 500") },
            fn("get_current_time", "获取当前日期时间（含星期）"),
            // ---------- 网络 ----------
            fn("web_search", "联网搜索，返回标题/链接/摘要", listOf("query")) { s("query", "搜索关键词") },
            fn("fetch_url", "抓取网页并返回纯文本", listOf("url")) { s("url", "完整网址，http/https 开头") },
            // ---------- 设备 ----------
            fn("accessibility_control", "通过无障碍服务操控手机界面（点击/输入/滑动/截图等）", listOf("action")) {
                enumOf("action", "操作类型", listOf("screen", "screenshot", "tap", "long_press", "swipe", "click_text", "input", "back", "home", "scroll", "launch_app", "sleep", "recents", "notifications", "stop_projection"))
                i("x", "横坐标"); i("y", "纵坐标")
                i("x1", "滑动起点横坐标"); i("y1", "滑动起点纵坐标"); i("x2", "滑动终点横坐标"); i("y2", "滑动终点纵坐标")
                i("duration_ms", "滑动时长 ms")
                s("text", "click_text=控件文字 / input=输入文本")
                enumOf("direction", "scroll 方向", listOf("up", "down"))
                s("package", "launch_app 的包名")
                i("ms", "sleep 等待毫秒数")
                b("from_vision", "坐标是否来自截图分析")
            },
            // ---------- 定时任务 ----------
            fn("create_scheduled_task", "创建定时任务（到点自动用 Agent 执行 prompt）", listOf("name", "prompt", "type")) {
                s("name", "任务名"); s("prompt", "要执行的指令")
                enumOf("type", "任务类型", listOf("ONCE", "INTERVAL", "DAILY", "WEEKLY"))
                i("interval_minutes", "INTERVAL 类型：间隔分钟数"); s("time_of_day", "DAILY/WEEKLY：HH:mm"); s("days_of_week", "WEEKLY：如 1,2,3,4,5")
            },
            fn("list_scheduled_tasks", "列出全部定时任务"),
            fn("cancel_scheduled_task", "取消定时任务", listOf("id")) { s("id", "任务 ID") },
            // ---------- 记忆 ----------
            fn("save_memory", "写入长期记忆（跨会话保存）", listOf("key", "content")) {
                s("key", "记忆标识（英文）"); s("content", "要记住的内容")
            },
            fn("read_memory", "读取指定长期记忆", listOf("key")) { s("key", "记忆标识") },
            fn("list_memory", "列出所有长期记忆"),
            // ---------- 交互 ----------
            fn("ask_user", "向用户提问并等待回答", listOf("question")) {
                s("question", "要问的问题"); s("options", "可选：逗号分隔的选项")
            },
        )
    }
}
