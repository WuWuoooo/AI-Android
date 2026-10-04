package com.ai.android.agent

import android.content.Context
import com.ai.android.agent.tools.*
import com.ai.android.model.ToolCall
import com.ai.android.model.ToolResult
import com.ai.android.storage.MemoryStore
import com.ai.android.storage.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class ToolRegistry(
    private val context: Context,
    private val settings: SettingsRepository,
) {

    private val executors = LinkedHashMap<String, ToolExecutor>()

        var askUserHandler: (suspend (question: String, options: String) -> String)? = null

    /** ⭐ 终端会话绑定：返回"当前对话 id"，让 AI 终端工具与该对话的终端 UI 共享同一常驻会话 */
    var convIdProvider: (() -> String)? = null

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** ⭐ AI 操控手机守卫（前后台检查 / 确认 / 镜像开关） */
    val aiControlGuard = AiControlGuard(context, settings)

    /** Agent 结束时关闭镜像（由 MainViewModel 调用） */
    fun stopAiMirror() { aiControlGuard.stopAll() }

    fun register(tool: ToolExecutor) { executors[tool.name] = tool }
    fun names(): List<String> = executors.keys.toList()
    fun has(name: String): Boolean = executors.containsKey(name)

    fun registerDefaultTools(memory: MemoryStore) {
        // 文件
        register(ReadFileTool(settings))
        register(WriteFileTool())
        register(EditFileTool())
        register(ListFilesTool())
        register(ListDirTreeTool())
        register(SearchFilesTool())
        register(CreateDirTool())
        register(DeleteFileTool())
        register(MoveFileTool())
        register(CopyFileTool())
        register(RenameFileTool())
        // 系统
        register(ShellTool())
        register(JsTool(context))
        register(GetCurrentTimeTool())
        // 网络
        register(WebSearchTool())
        register(FetchUrlTool())
                // 终端（⭐ 按对话隔离：与终端 UI 共享同一常驻会话，历史保留除非 clear）
        register(TerminalExecTool(context, settings) { convIdProvider?.invoke().orEmpty() })
        register(TerminalReadTool({ convIdProvider?.invoke().orEmpty() }, context, settings))
        // 无障碍
        register(AccessibilityTool())
        // 定时任务
        register(SchedulerTool(context))
        register(ListTasksTool(context))
        register(CancelTaskTool(context))
        // 记忆
        register(SaveMemoryTool(memory))
        register(ReadMemoryTool(memory))
        register(ListMemoryTool(memory))
        // 交互
        register(AskUserTool { q, o -> askUserHandler?.invoke(q, o) ?: "（当前没有可用的用户界面，无法提问）" })
    }

    suspend fun execute(call: ToolCall): ToolResult {
        val start = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - start

        val executor = executors[call.name]
            ?: return ToolResult(call.id, call.name,
                "❌ 未知工具: ${call.name}（可用: ${names().joinToString()}）", false, elapsed())

        val args: JsonObject = try {
            if (call.arguments.isBlank()) JsonObject(emptyMap())
            else json.parseToJsonElement(call.arguments).jsonObject
        } catch (e: Exception) {
            return ToolResult(call.id, call.name,
                "❌ 参数 JSON 解析失败：${e.message}\n收到的原始参数：${call.arguments.take(500)}\n" +
                    "请检查是否符合工具描述中的参数示例。", false, elapsed())
        }

                        return try {
            // ⭐ AI 操控手机：前置守卫（确认 / 启动镜像，幂等）
            if (call.name == "accessibility_control") {
                val block = aiControlGuard.preCheck(args) { q, o ->
                    askUserHandler?.invoke(q, o) ?: "（用户无界面，默认放行）"
                }
                if (block != null) {
                    return ToolResult(call.id, call.name, "⚠️ $block", false, elapsed())
                }
            }

            val output = executor.execute(args)
            ToolResult(call.id, call.name, output.ifBlank { "（执行成功，无输出）" }, true, elapsed())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            ToolResult(call.id, call.name, "❌ 执行失败: $msg", false, elapsed())
        }
    }
}

// ==================== 记忆工具 ====================

class SaveMemoryTool(private val store: MemoryStore) : ToolExecutor {
    override val name = "save_memory"
    override suspend fun execute(args: JsonObject): String {
        val key = args.requireStr("key", "记忆标识，英文短词，例如 user_name")
        val content = args.str("content")
        store.save(key, content)
        return "✅ 已保存记忆 [$key]（${content.length} 字符）"
    }
}

class ReadMemoryTool(private val store: MemoryStore) : ToolExecutor {
    override val name = "read_memory"
    override suspend fun execute(args: JsonObject): String {
        val key = args.requireStr("key", "记忆标识，例如 user_name")
        return store.read(key) ?: "没有找到记忆 [$key]"
    }
}

class ListMemoryTool(private val store: MemoryStore) : ToolExecutor {
    override val name = "list_memory"
    override suspend fun execute(args: JsonObject): String {
        val all = store.list()
        if (all.isEmpty()) return "当前没有保存任何长期记忆"
        return all.entries.joinToString("\n\n") { (k, v) ->
            val brief = if (v.length > 200) v.take(200) + "..." else v
            "[$k]\n$brief"
        }
    }
}

class AskUserTool(private val handler: suspend (String, String) -> String) : ToolExecutor {
    override val name = "ask_user"
    override suspend fun execute(args: JsonObject): String {
        val question = args.requireStr("question", "要向用户确认的问题")
        return handler(question, args.str("options"))
    }
}