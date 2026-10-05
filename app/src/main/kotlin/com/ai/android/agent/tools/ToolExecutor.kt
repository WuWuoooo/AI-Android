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
     * 危险工具标记：需要在系统提示词中强制二次确认。
     * ToolRegistry 会拦截并用错误消息提醒模型先 ask_user。
     */
    val dangerous: Boolean get() = false

    /**
     * 执行工具。
     * @param args 模型给出的 JSON 参数
     * @return 给模型查看的结果文本
     * @throws Exception 执行失败时抛出，由 ToolRegistry 统一包装为错误信息
     */
    suspend fun execute(args: JsonObject): String
}

// ---------- JsonObject 参数读取辅助（宽松） ----------

fun JsonObject.str(key: String, def: String = ""): String =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: def

fun JsonObject.int(key: String, def: Int = 0): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: def

fun JsonObject.bool(key: String, def: Boolean = false): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: def

/** 从多个别名中取第一个非空字符串（容忍模型把 command 写成 cmd/shell） */
fun JsonObject.strAny(vararg keys: String, def: String = ""): String {
    for (k in keys) {
        val v = this[k]?.jsonPrimitive?.contentOrNull
        if (!v.isNullOrBlank()) return v
    }
    return def
}

fun JsonObject.intAny(vararg keys: String, def: Int = 0): Int {
    for (k in keys) {
        val v = this[k]?.jsonPrimitive?.intOrNull
        if (v != null) return v
    }
    return def
}

// ---------- 强校验辅助（缺失即抛出可读错误） ----------

/**
 * 取必填字符串参数。缺失时抛出**人话**错误，让模型下一轮能照着改。
 * @param hint 给模型的补充提示，例如 "例如 /sdcard/Download/note.txt"
 */
fun JsonObject.requireStr(key: String, hint: String = ""): String {
    val v = this[key]?.jsonPrimitive?.contentOrNull?.trim()
    if (v.isNullOrEmpty()) {
        val h = if (hint.isNotBlank()) " $hint" else ""
        throw IllegalArgumentException("缺少必填参数 `$key`。$h")
    }
    return v
}

/** 从多个别名中取必填字符串参数 */
fun JsonObject.requireStrAny(vararg keys: String, hint: String = ""): String {
    for (k in keys) {
        val v = this[k]?.jsonPrimitive?.contentOrNull?.trim()
        if (!v.isNullOrEmpty()) return v
    }
    throw IllegalArgumentException("缺少必填参数 `${keys.first()}`（也接受 ${keys.drop(1).joinToString("/")}）。$hint")
}

fun JsonObject.requireInt(key: String, hint: String = ""): Int {
    val v = this[key]?.jsonPrimitive?.intOrNull
        ?: throw IllegalArgumentException("缺少必填参数 `$key`（需要整数）。$hint")
    return v
}