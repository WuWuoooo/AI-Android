package com.ai.android.provider

import kotlinx.serialization.Serializable

/**
 * 单个 Provider 配置。
 *
 * - capabilities：该 Provider 支持的能力（CHAT / VISION / IMAGE / TTS / ASR / SEARCH / REALTIME）
 * - extraBodyJson：自定义请求体 JSON，会 merge 进每次请求的 body
 *   例：{"top_p":0.9,"max_tokens":4096}
 * - extraHeaders：自定义请求头，每行 "Key: Value"
 */
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
}