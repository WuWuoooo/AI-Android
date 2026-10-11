package com.ai.android.provider

import kotlinx.serialization.json.*

/**
 * 管理多个 Provider 实例，并构建全局工具定义。
 *
 * v1.0.0-beta2-fix2：
 *  - ProviderConfig 已移至 ProviderConfig.kt，本文件不再重复声明
 *  - create() 把 extraBodyJson / extraHeaders 传给 Provider 构造函数
 */
class ProviderManager {

    private val map = LinkedHashMap<String, AIProvider>()

    var activeId: String = ""
        private set

    val active: AIProvider?
        get() = map[activeId] ?: map.values.firstOrNull()

    /** 多模态能力管理（由 SettingsRepository.bindMultimodal 绑定） */
    val multimodal = MultimodalManager()

    init {
        // 占位预设（启动时由 SettingsRepository.loadIntoManager() 覆盖）
        upsert(ProviderConfig(
            id = "openai", label = "OpenAI",
            baseUrl = "https://api.openai.com/v1", model = "gpt-5.6-sol",
            capabilities = listOf("CHAT", "VISION", "IMAGE", "TTS", "ASR", "REALTIME"),
        ))
        upsert(ProviderConfig(
            id = "anthropic", label = "Anthropic",
            protocol = AIProvider.Protocol.ANTHROPIC,
            baseUrl = "https://api.anthropic.com", model = "claude-opus-5",
            capabilities = listOf("CHAT", "VISION"),
        ))
        upsert(ProviderConfig(
            id = "deepseek", label = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1", model = "deepseek-v4-pro",
            capabilities = listOf("CHAT", "VISION"),
        ))
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

    /**
     * 创建 Provider 实例。
     * 把 extraBodyJson / extraHeaders 透传给具体 Provider，
     * 让用户自定义请求体/请求头能生效。
     */
    fun create(config: ProviderConfig): AIProvider = when (config.protocol) {
        AIProvider.Protocol.ANTHROPIC -> AnthropicProvider(
            name = config.label,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            model = config.model,
            extraBodyJson = config.extraBodyJson,
            extraHeaders = config.headerMap(),
        )
        else -> OpenAIProvider(
            name = config.label,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            model = config.model,
            extraBodyJson = config.extraBodyJson,
            extraHeaders = config.headerMap(),
        )
    }

    /** 全局工具定义（供 AgentCore 每次请求时传给模型） */
    fun buildTools(): List<JsonObject> = TOOLS

    companion object {

        private val TOOLS: List<JsonObject> by lazy { buildToolList() }

        private fun fn(
            name: String,
            desc: String,
            example: String = "",
            required: List<String> = emptyList(),
            props: JsonObjectBuilder.() -> Unit = {},
        ): JsonObject = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                val fullDesc = buildString {
                    append(desc.trim())
                    if (example.isNotBlank()) {
                        append("\n参数示例：")
                        append(example)
                    }
                }
                put("description", fullDesc)
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject(props))
                    put("required", buildJsonArray { required.forEach { add(it) } })
                })
            })
        }

        private fun JsonObjectBuilder.s(n: String, d: String, required: Boolean = false) =
            put(n, buildJsonObject {
                put("type", "string")
                put("description", (if (required) "[必填] " else "[可选] ") + d)
            })

        private fun JsonObjectBuilder.i(n: String, d: String, required: Boolean = false) =
            put(n, buildJsonObject {
                put("type", "integer")
                put("description", (if (required) "[必填] " else "[可选] ") + d)
            })

        private fun JsonObjectBuilder.b(n: String, d: String) =
            put(n, buildJsonObject {
                put("type", "boolean")
                put("description", "[可选] " + d)
            })

        private fun JsonObjectBuilder.enumOf(n: String, d: String, values: List<String>) =
            put(n, buildJsonObject {
                put("type", "string")
                put("description", "[必填] " + d)
                put("enum", buildJsonArray { values.forEach { add(it) } })
            })

        private fun buildToolList(): List<JsonObject> = listOf(
            // ---------- 文件 ----------
                        fn(
                "read_file",
                "读取文件全文（大文件截断到 100KB）。改文件前务必先用本工具确认内容。" +
                    "若文件是二进制（.dex/.so/图片/AXML 等），会返回 hex 预览并建议用 terminal_exec 跑 xxd/strings；" +
                    "传 binary=true 可让工具直接返回前 4KB 的 hex dump。",
                """{"path":"/sdcard/Download/note.txt","binary":false}""",
                listOf("path"),
            ) {
                s("path", "文件的绝对路径，例：/sdcard/Download/note.txt", required = true)
                b("binary", "true=对二进制文件返回 hex dump（前4KB），默认 false")
            },
            fn(
                "write_file",
                "创建新文件或**整文件覆写**。只想改一段内容请用 edit_file，不要用本工具。",
                """{"path":"/sdcard/test.txt","content":"hello world"}""",
                listOf("path", "content"),
            ) {
                s("path", "文件绝对路径", required = true)
                s("content", "完整文件内容", required = true)
            },
            fn(
                "edit_file",
                "精确替换文件中一段内容。old_string 必须与文件逐字符一致（建议先 read_file 再复制）。",
                """{"path":"/sdcard/a.txt","old_string":"foo","new_string":"bar"}""",
                listOf("path", "old_string", "new_string"),
            ) {
                s("path", "文件路径", required = true)
                s("old_string", "要被替换的原文，逐字符一致", required = true)
                s("new_string", "替换后的内容", required = true)
                b("replace_all", "是否替换所有匹配，默认 false")
            },
            fn("list_files", "列出目录下一层内容。", """{"path":"/sdcard/Download"}""", listOf("path")) {
                s("path", "目录路径", required = true)
            },
            fn(
                "list_dir_tree",
                "把目录列成缩进树形结构，适合快速概览。",
                """{"path":"/sdcard/AiAndroid","max_depth":3}""",
                listOf("path"),
            ) {
                s("path", "根目录路径", required = true)
                i("max_depth", "最大深度，默认 3，范围 1-8")
                i("max_entries", "最多条目数，默认 300")
            },
            fn(
                "search_files",
                "按文件名或内容搜索文件。",
                """{"path":"/sdcard/Download","keyword":"invoice","in_content":true}""",
                listOf("path", "keyword"),
            ) {
                s("path", "搜索起始目录", required = true)
                s("keyword", "关键词，不区分大小写", required = true)
                b("in_content", "true=同时搜内容，false=只匹配文件名")
            },
            fn("create_dir", "创建目录（含中间目录）。", """{"path":"/sdcard/Download/Pictures"}""", listOf("path")) {
                s("path", "目录路径", required = true)
            },
            fn(
                "delete_file",
                "删除文件或目录。删除目录必须 recursive=true。⚠️ 危险操作，先 ask_user。",
                """{"path":"/sdcard/tmp.txt"}""",
                listOf("path"),
            ) {
                s("path", "路径", required = true)
                b("recursive", "删目录时是否连带内容一起删，默认 false")
            },
            fn("move_file", "移动文件或目录。", """{"src":"/sdcard/a.txt","dst":"/sdcard/Download/a.txt"}""", listOf("src", "dst")) {
                s("src", "源路径", required = true)
                s("dst", "目标路径", required = true)
                b("overwrite", "目标存在时是否覆盖，默认 false")
            },
            fn("copy_file", "复制文件或目录。", """{"src":"/sdcard/a.txt","dst":"/sdcard/b.txt"}""", listOf("src", "dst")) {
                s("src", "源路径", required = true)
                s("dst", "目标路径", required = true)
                b("overwrite", "目标存在时是否覆盖，默认 false")
            },
            fn("rename_file", "重命名（只改名字，不移动位置）。", """{"path":"/sdcard/a.txt","new_name":"b.txt"}""", listOf("path", "new_name")) {
                s("path", "原完整路径", required = true)
                s("new_name", "新文件名（不含路径分隔符）", required = true)
            },

            // ---------- 系统 ----------
                                    fn(
                "run_shell_command",
                "执行**一次性** shell 命令并等待结果（超时最长 60s）。适合快速、短小、无副作用的命令。" +
                    "长任务/装包(pip/apt/pkg)/跑 Python 脚本 超过 60s 请用 terminal_exec（上限 120s，且后台继续运行，不杀进程）。" +
                    "注意：Shizuku(uid 2000) 与 Termux(u0_a348) 是**不同 uid 沙箱**，涉及 Termux 内部目录" +
                    "（/data/data/com.termux）或杀 Termux 进程必须走 terminal_exec，本工具无法跨 uid 操作。",
                """{"command":"ls /sdcard/Download","timeout_sec":15}""",
                listOf("command"),
            ) {
                s("command", "sh 语法命令", required = true)
                i("timeout_sec", "超时秒数，默认 15，最大 60（超过请改用 terminal_exec）")
            },
            fn(
                "run_js",
                "在 WebView V8 引擎执行 JavaScript。用 `__send(结果)` 把结果传回；不调用则超时。",
                """{"code":"__send(1+2)"}""",
                listOf("code"),
            ) {
                s("code", "JS 代码，用 __send(结果) 返回", required = true)
                i("timeout_sec", "超时秒数，默认 15，最大 60")
            },
                        fn(
                "terminal_exec",
                "在**常驻终端**执行命令（跨调用保持工作目录/环境）。适合：装包(pkg/apt)、跑 Python、长时间任务。" +
                    "如果超时，命令仍在后台跑，用 terminal_read 读取后续输出。" +
                    "传 background=true 则命令立即后台化，返回 task id，不阻塞后续工具调用（适合 for sleep / 长轮询等），" +
                    "之后用 terminal_read_bg 按 task 读输出，或用 terminal_wait 非阻塞等待结束。",
                """{"command":"pkg install python -y","timeout_sec":60,"background":false}""",
                listOf("command"),
            ) {
                s("command", "sh 语法命令", required = true)
                i("timeout_sec", "等待超时秒数，默认 60，最大 120（background=true 时忽略）")
                b("background", "true=立即后台化并返回 task id（不阻塞）；默认 false 阻塞等待")
            },
            fn("terminal_read", "读取内置终端最近 N 行输出。", """{"lines":50,"since_last":true}""") {
                i("lines", "读取行数，默认 40，最大 500")
                b("since_last", "true=只返回上次读取后新增的输出（避免重复读旧内容）")
            },
            // ⭐ v1.2.0-next #5：后台终端工具
            fn(
                "terminal_read_bg",
                "按 task id 读取 terminal_exec background=true 启动的后台命令输出（不阻塞主终端队列）。",
                """{"task":"abc123","lines":100}""",
                listOf("task"),
            ) {
                s("task", "后台任务 id（terminal_exec background=true 返回的 task）", required = true)
                i("lines", "读取日志最近行数，默认 100，最大 500")
            },
            fn(
                "terminal_wait",
                "非阻塞等待后台任务结束或日志匹配到指定模式（独立线程轮询，不占终端队列）。" +
                    "适合替代 sleep/长轮询：terminal_exec background=true 后，用本工具等到某模式出现或进程结束。",
                """{"task":"abc123","pattern":"done","timeout_sec":300}""",
                listOf("task"),
            ) {
                s("task", "后台任务 id（terminal_exec background=true 返回的 task）", required = true)
                s("pattern", "可选：日志里出现此字符串时立即返回；不填则等到进程结束")
                i("timeout_sec", "最长等待秒数，默认 300，最大 600")
            },
            fn(
                "terminal_list_bg",
                "列出当前对话所有后台任务及其存活状态（PTY 重连后可据此汇报哪些任务还活着）。",
                "{}",
            ) {},
            fn("get_current_time", "获取当前日期时间（含星期）。无需参数。", "{}"),

            // ---------- 网络 ----------
            fn("web_search", "联网搜索，返回标题/链接/摘要。", """{"query":"今天上海天气"}""", listOf("query")) {
                s("query", "搜索关键词", required = true)
            },
            fn("fetch_url", "抓取网页转纯文本。", """{"url":"https://example.com/article"}""", listOf("url")) {
                s("url", "完整网址，必须 http:// 或 https:// 开头", required = true)
            },

            // ---------- 设备 ----------
            fn(
                "accessibility_control",
                "通过无障碍服务操控手机界面。先 screen 看控件再点。敏感操作（发送/支付）先 ask_user。",
                """{"action":"tap","x":540,"y":1200}""",
                listOf("action"),
            ) {
                enumOf(
                    "action", "操作类型",
                    listOf("screen", "screenshot", "tap", "long_press", "swipe", "click_text", "input",
                        "back", "home", "scroll", "launch_app", "sleep", "recents", "notifications", "stop_projection")
                )
                i("x", "tap/long_press 的横坐标")
                i("y", "tap/long_press 的纵坐标")
                i("x1", "swipe 起点横坐标")
                i("y1", "swipe 起点纵坐标")
                i("x2", "swipe 终点横坐标")
                i("y2", "swipe 终点纵坐标")
                i("duration_ms", "swipe 时长 ms，默认 400")
                s("text", "click_text=控件文字 / input=输入文本")
                enumOf("direction", "scroll 方向", listOf("up", "down"))
                s("package", "launch_app 的包名，如 com.tencent.mm")
                i("ms", "sleep 等待毫秒数，最长 5000")
            },

            // ---------- 定时任务 ----------
            fn(
                "create_scheduled_task",
                "创建定时任务，到点自动用 Agent 执行 prompt。prompt 要写成独立指令（不依赖当前对话）。",
                """{"name":"早安提醒","prompt":"告诉我今天天气","type":"DAILY","time_of_day":"08:00"}""",
                listOf("name", "prompt", "type"),
            ) {
                s("name", "任务名", required = true)
                s("prompt", "要执行的独立指令", required = true)
                enumOf("type", "任务类型", listOf("ONCE", "INTERVAL", "DAILY", "WEEKLY"))
                i("interval_minutes", "INTERVAL 类型：间隔分钟数")
                s("time_of_day", "DAILY/WEEKLY：HH:mm 格式，例 08:00")
                s("days_of_week", "WEEKLY：1=周一…7=周日，逗号分隔，例 1,3,5")
            },
            fn("list_scheduled_tasks", "列出全部定时任务。", "{}"),
            fn("cancel_scheduled_task", "取消定时任务。", """{"id":"a1b2c3d4"}""", listOf("id")) {
                s("id", "任务 ID（来自 list_scheduled_tasks）", required = true)
            },

            // ---------- 记忆 ----------
            fn("save_memory", "写入跨会话长期记忆。", """{"key":"user_name","content":"小明"}""", listOf("key", "content")) {
                s("key", "记忆标识（英文短词）", required = true)
                s("content", "要记住的内容", required = true)
            },
            fn("read_memory", "读取指定长期记忆。", """{"key":"user_name"}""", listOf("key")) {
                s("key", "记忆标识", required = true)
            },
                        fn("list_memory", "列出所有长期记忆。", "{}"),
            fn(
                "delete_memory",
                "删除指定 key 的长期记忆。⚠️ 不可恢复，删除前建议先 read_memory 确认。",
                """{"key":"user_name"}""",
                listOf("key"),
            ) {
                s("key", "要删除的记忆标识（与 save_memory/read_memory 的 key 一致）", required = true)
            },

                        // ---------- 交互 ----------
            fn(
                "ask_user",
                "向用户提问并等待回答。**危险操作前必须先调用本工具确认**。",
                """{"question":"确认删除 /sdcard/Download 下的所有文件？","options":"确认,取消"}""",
                listOf("question"),
            ) {
                s("question", "要问的问题", required = true)
                s("options", "可选：逗号分隔的选项，例：确认,取消")
            },

            // ---------- 多模态（第五轮 Bug 3）----------
            fn(
                "generate_image",
                "用文生图 Provider 生成图片（需已配置 IMAGE 能力）。" +
                    "返回图片文件路径 + base64 长度。prompt 描述越详细效果越好。",
                """{"prompt":"一只在草地上奔跑的橘猫","size":"1024x1024"}""",
                listOf("prompt"),
            ) {
                s("prompt", "图片描述（中英文均可，越具体越好）", required = true)
                s("size", "尺寸，默认 1024x1024，可选 512x512 / 1024x1024 / 1792x1024")
            },
            fn(
                "generate_speech",
                "用 TTS Provider 将文字合成为语音文件（需已配置 TTS 能力）。" +
                    "返回 wav 文件路径。voice 可选：Chloe/冰糖/茉莉/苏打/白桦/Mia/Milo/Dean 等。",
                """{"text":"你好，我是 AI 助手","voice":"Chloe"}""",
                listOf("text"),
            ) {
                                s("text", "要朗读的正文（最多 4096 字符）", required = true)
                s("voice", "音色，默认 冰糖（中文女声）；可选 茉莉/苏打/白桦/Mia/Chloe/Milo/Dean 等")
            },
                                    fn(
                "analyze_image",
                "用识图（VISION）Provider 分析图片（需已配置 VISION 能力）。" +
                    "传 image_base64 或 image_path（二选一）+ 问题，返回文字描述。",
                """{"image_path":"/sdcard/Pictures/cat.jpg","question":"这是什么猫？"}""",
                emptyList(),
            ) {
                s("image_path", "图片文件绝对路径（与 image_base64 二选一）")
                s("image_base64", "图片 base64 字符串（与 image_path 二选一）")
                s("question", "针对图片的问题，默认：请描述这张图片的内容")
            },

            // ---------- 项目记忆（第五轮 #4）----------
            fn(
                "project_memory",
                "管理**当前对话所属项目**的独立项目记忆（与全局长期记忆分开存）。" +
                    "action=list 列全部 / save 写一条(需 key+content) / read 读一条(需 key) / delete 删一条(需 key)。" +
                    "仅当当前对话已归入某个项目时可用；项目记忆会自动注入到该项目的对话上下文里。",
                """{"action":"save","key":"goal","content":"本项目目标是…"}""",
                listOf("action"),
            ) {
                enumOf("action", "动作", listOf("list", "save", "read", "delete"))
                s("key", "记忆标识（save/read/delete 必填），英文短词，例如 goal")
                s("content", "要保存的内容（save 必填）")
            },
        )
    }
}