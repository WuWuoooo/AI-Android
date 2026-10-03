package com.ai.android.agent

import android.content.Context
import com.ai.android.agent.tools.*
import com.ai.android.model.ToolCall
import com.ai.android.model.ToolResult
import com.ai.android.storage.MemoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 工具注册与分发中心。
 * AgentCore 拿到模型的 tool_calls 后交给这里执行。
 */
class ToolRegistry(private val context: Context) {

    private val executors = LinkedHashMap<String, ToolExecutor>()

    /** ask_user 的 UI 回调，由 MainViewModel 注入 */
    var askUserHandler: (suspend (question: String, options: String) -> String)? = null

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun register(tool: ToolExecutor) {
        executors[tool.name] = tool
    }

    fun names(): List<String> = executors.keys.toList()

    fun has(name: String): Boolean = executors.containsKey(name)

    /** 注册全部内置工具 */
    fun registerDefaultTools(memory: MemoryStore) {
        // 文件
        register(ReadFileTool())
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
        // 终端
        register(TerminalExecTool())
        register(TerminalReadTool())
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

    /** 执行一次工具调用，异常统一转成失败结果 */
    suspend fun execute(call: ToolCall): ToolResult {
        val start = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - start

        val executor = executors[call.name]
            ?: return ToolResult(call.id, call.name, "❌ 未知工具: ${call.name}（可用: ${names().joinToString()}）", false, elapsed())

        val args: JsonObject = runCatching {
            json.parseToJsonElement(call.arguments.ifBlank { "{}" }).jsonObject
        }.getOrElse { buildJsonObject { } }

        return try {
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
        val key = args.str("key")
        val content = args.str("content")
        if (key.isBlank()) throw IllegalStateException("key 不能为空")
        store.save(key, content)
        return "✅ 已保存记忆 [$key]（${content.length} 字符）"
    }
}

class ReadMemoryTool(private val store: MemoryStore) : ToolExecutor {
    override val name = "read_memory"
    override suspend fun execute(args: JsonObject): String {
        val key = args.str("key")
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

// ==================== 交互工具 ====================

class AskUserTool(private val handler: suspend (String, String) -> String) : ToolExecutor {
    override val name = "ask_user"
    override suspend fun execute(args: JsonObject): String {
        val question = args.str("question")
        if (question.isBlank()) throw IllegalStateException("question 不能为空")
        return handler(question, args.str("options"))
    }
}
