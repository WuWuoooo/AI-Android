package com.ai.android.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 单个 Provider 配置。
 *
 * - capabilities：该 Provider 支持的能力（CHAT / VISION / IMAGE / TTS / ASR / SEARCH / REALTIME）
 * - extraBodyJson：自定义请求体 JSON，会 merge 进每次请求的 body
 *   例：{"top_p":0.9,"max_tokens":4096}
 * - extraParams：⭐ v1.1.0 新手化的「逐条请求参数」（键/值/类型）。
 *   表单模式编辑这些行，保存时自动序列化进 [extraBodyJson]；
 *   高级模式仍可直接编辑 [extraBodyJson]，两者双向同步不丢数据。
 * - extraHeaders：自定义请求头，每行 "Key: Value"
 */
@Serializable
data class ParamRow(
    val key: String = "",
    val value: String = "",
    /** 取值："string" / "number" / "bool" / "json" */
    val type: String = "string",
)

@Serializable
data class ProviderConfig(
    val id: String = "default",
    val label: String = "OpenAI",
    val protocol: AIProvider.Protocol = AIProvider.Protocol.OPENAI,
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-5.6-sol",
    val capabilities: List<String> = listOf("CHAT"),
    val extraBodyJson: String = "",
    /** ⭐ v1.1.0：结构化请求参数（表单模式编辑；保存时序列化进 [extraBodyJson]） */
    val extraParams: List<ParamRow> = emptyList(),
    val extraHeaders: String = "",
) {
    fun headerMap(): Map<String, String> =
        extraHeaders.lines()
            .mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null
                else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            .toMap()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** ⭐ 常见参数预设 chip：一键添加 */
        val PRESET_PARAMS = listOf(
            ParamRow("temperature", "0.3", "number"),
            ParamRow("top_p", "1.0", "number"),
            ParamRow("max_tokens", "4096", "number"),
            ParamRow("stream", "true", "bool"),
            ParamRow("stop", "", "string"),
        )

        /**
         * ⭐ 逐条参数 → 请求体 JSON 字符串（保存表单时调用）。
         * 键冲突以用户填写的为准；类型按 [ParamRow.type] 解析，解析失败退回原始字符串。
         */
                fun rowsToJson(rows: List<ParamRow>): String {
            val valid = rows.filter { it.key.isNotBlank() }
            if (valid.isEmpty()) return ""
            val obj = buildJsonObject {
                                valid.forEach { r ->
                    val k = r.key.trim()
                    val v = r.value.trim()
                    put(k, when (r.type) {
                        "number" -> {
                            // 优先整数表示（4096 → "4096"，不变成 4096.0）；否则小数；再否则退回字符串
                            val long = v.toLongOrNull()
                            if (long != null) JsonPrimitive(long)
                            else {
                                val dbl = v.toDoubleOrNull()
                                if (dbl != null) JsonPrimitive(dbl) else JsonPrimitive(v)
                            }
                        }
                        "bool" -> when (v) {
                            "true" -> JsonPrimitive(true)
                            "false" -> JsonPrimitive(false)
                            else -> JsonPrimitive(v)
                        }
                        "json" -> runCatching { json.parseToJsonElement(v) }.getOrNull() ?: JsonPrimitive(v)
                        else -> JsonPrimitive(v)
                    })
                }
            }
            return obj.toString()
        }

        /** ⭐ 请求体 JSON → 逐条参数（编辑高级 JSON 后回写表单；解析失败返回空，避免覆盖已有行）。 */
        fun jsonToRows(jsonText: String): List<ParamRow> {
            if (jsonText.isBlank()) return emptyList()
            val el = runCatching { json.parseToJsonElement(jsonText) }.getOrNull()
            val obj = el as? JsonObject ?: return emptyList()
                                    return obj.map { (k, v) ->
                when (v) {
                    is JsonObject, is JsonArray -> ParamRow(k, v.toString(), "json")
                    is JsonPrimitive -> when {
                        // JsonPrimitive 只有 isString / content 两个公开成员；
                        // boolean / number 走扩展属性需要额外 import，直接用 content 判断更稳
                        v.isString -> ParamRow(k, v.content, "string")
                        v.content.equals("true", true) -> ParamRow(k, "true", "bool")
                        v.content.equals("false", true) -> ParamRow(k, "false", "bool")
                        v.content.toDoubleOrNull() != null -> ParamRow(k, v.content, "number")
                        else -> ParamRow(k, v.content, "string")
                    }
                    else -> ParamRow(k, v.toString(), "json")
                }
            }
        }
    }
}
