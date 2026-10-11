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
        /**
     * ⭐ v1.2.0 #4：该模型的**上下文窗口（token 上限）**——能塞进一次请求的最大 token 数
     * （是「限制」，不是已用）。默认 0 = 未设置，由 [autoDetectContextWindow] 按模型名自动推断
     * （如 512K 模型 → 524288）；用户手填则优先用户值（填 0 = 走自动推断）。
     */
    val contextWindow: Int = 0,
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

        /**
         * ⭐ v1.2.0 问题3修复：按模型名自动推断上下文窗口（token）。
         * 用户不改 contextWindow 时（保持默认 32768），用模型名匹配更准确的值。
         * 512K 模型 → 524288；128K → 131072；32K → 32768 等。
         */
        fun autoDetectContextWindow(model: String, current: Int = 0): Int {
            if (current > 0) return current   // 用户已手动设过，优先用户值
            val m = model.lowercase()
            return when {
                // 512K 系列
                m.contains("512") && (m.contains("k") || m.contains("max") || m.contains("sol")) -> 524_288
                m.contains("524288") || m.contains("524_288") -> 524_288
                // 200K 系列
                m.contains("200k") -> 204_800
                // 128K 系列
                m.contains("128") && m.contains("k") -> 131_072
                m.contains("131072") -> 131_072
                // 100K 系列
                m.contains("100k") -> 100_000
                // 默认 32K
                else -> 32_768
            }
        }

                /** ⭐ 常见参数预设 chip：一键添加 */
        val PRESET_PARAMS = listOf(
            ParamRow("temperature", "0.3", "number"),
            ParamRow("top_p", "1.0", "number"),
            ParamRow("max_tokens", "4096", "number"),
            ParamRow("stream", "true", "bool"),
            ParamRow("stop", "", "string"),
        )

        // ==================== ⭐ 第五轮 #6：能力互斥分组（语音类 不能与 正常 AI 对话类 同选）====================
        /** 语音/实时组：TTS / ASR / REALTIME（语音合成、识别、实时通话） */
        val VOICE_CAPS = setOf("TTS", "ASR", "REALTIME")
        /** 对话/文本组：CHAT / VISION / IMAGE / SEARCH（正常 AI 对话、识图、生图、搜索） */
        val CHAT_CAPS = setOf("CHAT", "VISION", "IMAGE", "SEARCH")

        /** 勾能力时做互斥：选语音组就清对话组，选对话组就清语音组（同组内可共存） */
        fun resolveCaps(selected: Set<String>): Set<String> {
            val pickedVoice = selected.any { it in VOICE_CAPS }
            val pickedChat = selected.any { it in CHAT_CAPS }
            return when {
                // 两组都被勾了（来自历史数据/旧配置）→ 保留最近勾选的组，清另一组
                pickedVoice && pickedChat -> selected.filter { it in CHAT_CAPS }.toSet()
                else -> selected
            }
        }

        /**
         * ⭐ 第五轮 #6：按 Provider 类型返回**专属默认请求参数**（填入后主人可改可删）。
         * 依据 baseUrl / model / capabilities 判断类型：
         *  - 智谱 GLM-Image（baseUrl 含 bigmodel 或 model 含 glm-image）→ 文生图专属 size
         *  - MiMo TTS（baseUrl 含 xiaomimimo）→ TTS 走 audio 参数，请求体默认空（不塞无效字段）
         *  - 通义 Qwen → 对话 + 常用对话参数
         *  - 其余 OpenAI 兼容 → 通用对话默认（temperature/top_p/max_tokens/stream）
         */
        fun defaultParamsFor(cfg: ProviderConfig): List<ParamRow> {
            val url = cfg.baseUrl.lowercase()
            val m = cfg.model.lowercase()
            val caps = cfg.capabilities
            return when {
                url.contains("bigmodel") || m.contains("glm-image") -> listOf(
                    ParamRow("size", "1280x1280", "string"),
                    ParamRow("n", "1", "number"),
                )
                url.contains("xiaomimimo") -> listOf(
                    // MiMo TTS 音色/格式走 audio 对象，请求体无标准必填字段；
                    // 给 voice 便于主人按需覆盖（默认冰糖中文女声）
                    ParamRow("voice", "冰糖", "string"),
                )
                caps.contains("CHAT") || caps.contains("VISION") -> listOf(
                    ParamRow("temperature", "0.3", "number"),
                    ParamRow("top_p", "1.0", "number"),
                    ParamRow("max_tokens", "4096", "number"),
                    ParamRow("stream", "true", "bool"),
                )
                else -> emptyList()
            }
        }

        /**
         * ⭐ 逐条参数 → 请求体 JSON 字符串（保存表单时调用）。
         * 键冲突以用户填写的为准；类型按 [ParamRow.type] 解析，解析失败退回原始字符串。
         */
                                fun rowsToJson(rows: List<ParamRow>): String {
            val valid = rows.filter { r ->
                r.key.isNotBlank() && !(r.type == "string" && r.value.isBlank())
            }
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
