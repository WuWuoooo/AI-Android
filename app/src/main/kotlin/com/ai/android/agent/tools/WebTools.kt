package com.ai.android.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

private const val UA =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

/** 联网搜索（DuckDuckGo HTML 版，无需 API key） */
class WebSearchTool : ToolExecutor {

    override val name = "web_search"

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val query = args.str("query")
        if (query.isBlank()) throw IllegalStateException("query 不能为空")
        val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        val html = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("搜索请求失败: HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
        val results = parseResults(html)
        if (results.isEmpty()) "未找到「$query」的结果（可能被限流，请稍后重试）"
        else "搜索「$query」结果:\n\n" + results.joinToString("\n\n")
    }

    private fun parseResults(html: String): List<String> {
        val linkRe = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippetRe = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val links = linkRe.findAll(html).toList()
        val snippets = snippetRe.findAll(html).toList()
        return links.take(8).mapIndexed { i, m ->
            val raw = m.groupValues[1]
            val title = stripTags(m.groupValues[2])
            val href = cleanUrl(raw)
            val snippet = snippets.getOrNull(i)?.let { stripTags(it.groupValues[1]).take(300) } ?: ""
            "${i + 1}. $title\n   $href\n   $snippet"
        }.filter { it.isNotBlank() }
    }

    private fun cleanUrl(raw: String): String {
        val u = if (raw.startsWith("//")) "https:$raw" else raw
        return runCatching {
            val idx = u.indexOf("uddg=")
            if (idx >= 0) URLDecoder.decode(u.substring(idx + 5).substringBefore("&"), "UTF-8") else u
        }.getOrDefault(u)
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("(?s)<[^>]+>"), "").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#x27;", "'").replace("&nbsp;", " ").trim()
}

/** 抓取网页并转为纯文本 */
class FetchUrlTool : ToolExecutor {

    override val name = "fetch_url"

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val url = args.str("url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw IllegalStateException("url 必须以 http:// 或 https:// 开头")
        }
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("请求失败: HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("返回内容为空")
            val bytes = body.bytes()
            if (bytes.size > 1_000_000) throw IllegalStateException("页面过大（${bytes.size} 字节），已中止")
            val text = bytes.toString(Charsets.UTF_8)
            val ct = resp.header("Content-Type").orEmpty()
            val out = if (ct.contains("html", ignoreCase = true)) htmlToText(text) else text
            out.take(50_000) + if (out.length > 50_000) "\n\n...(内容过长已截断)" else ""
        }
    }

    private fun htmlToText(html: String): String {
        var t = html
        t = t.replace(Regex("(?is)<script.*?</script>"), " ")
        t = t.replace(Regex("(?is)<style.*?</style>"), " ")
        t = t.replace(Regex("(?is)<head.*?</head>"), " ")
        t = t.replace(Regex("(?is)<!--.*?-->"), " ")
        t = t.replace(Regex("(?i)<br\\s*/?>"), "\n")
        t = t.replace(Regex("(?i)</(p|div|h[1-6]|li|tr|section|article)>"), "\n")
        t = t.replace(Regex("(?s)<[^>]+>"), "")
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        t = t.replace(Regex("[ \\t]+"), " ")
        t = t.replace(Regex("\\n[ \\t]+"), "\n")
        t = t.replace(Regex("\\n{3,}"), "\n\n")
        return t.trim()
    }
}
