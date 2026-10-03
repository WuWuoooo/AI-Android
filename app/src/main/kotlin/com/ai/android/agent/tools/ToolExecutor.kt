package com.ai.android.agent.tools

import kotlinx.serialization.json.*

/**
 * 工具执行器接口。
 * 每个工具实现一个，由 ToolRegistry 注册与分发。
 */
interface ToolExecutor {

    /** 工具名，必须与 ProviderManager.buildTools() 中定义的 name 一致 */
    val name: String

    /**
     * 执行工具。
     * @param args 模型给出的 JSON 参数
     * @return 给模型查看的结果文本
     * @throws Exception 执行失败时抛出，由 ToolRegistry 统一包装为错误信息
     */
    suspend fun execute(args: JsonObject): String
}

// ---------- JsonObject 参数读取辅助 ----------

fun JsonObject.str(key: String, def: String = ""): String =
    this[key]?.jsonPrimitive?.contentOrNull ?: def

fun JsonObject.int(key: String, def: Int = 0): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: def

fun JsonObject.bool(key: String, def: Boolean = false): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: def
