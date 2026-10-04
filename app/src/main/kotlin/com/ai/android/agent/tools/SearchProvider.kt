package com.ai.android.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

interface SearchProvider {
    val name: String
    suspend fun search(query: String, maxResults: Int = 8): List<SearchResult>

    data class SearchResult(val title: String, val url: String, val snippet: String)
}

private val searchHttp: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

private val JSON = "application/json; charset=utf-8".toMediaType()

private val sJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Tavily（AI 搜索专用，推荐） */
class TavilySearch(private val apiKey: String) : SearchProvider {
    override val name = "Tavily"
    override suspend fun search(query: String, maxResults: Int): List<SearchProvider.SearchResult> =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("api_key", apiKey)
                put("query", query)
                put("max_results", maxResults)
                put("search_depth", "basic")
            }
            val req = Request.Builder()
                .url("https://api.tavily.com/search")
                .post(body.toString().toRequestBody(JSON))
                .build()
            searchHttp.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("Tavily HTTP ${resp.code}")
                sJson.parseToJsonElement(text).jsonObject["results"]?.jsonArray
                    ?.mapNotNull { el ->
                        val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                        SearchProvider.SearchResult(
                            o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            o["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            o["content"]?.jsonPrimitive?.contentOrNull.orEmpty().take(400),
                        )
                    }.orEmpty()
            }
        }
}

/** Serper.dev（Google 代理） */
class SerperSearch(private val apiKey: String) : SearchProvider {
    override val name = "Serper"
    override suspend fun search(query: String, maxResults: Int): List<SearchProvider.SearchResult> =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("q", query); put("num", maxResults)
            }
            val req = Request.Builder()
                .url("https://google.serper.dev/search")
                .header("X-API-KEY", apiKey)
                .post(body.toString().toRequestBody(JSON))
                .build()
            searchHttp.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("Serper HTTP ${resp.code}")
                sJson.parseToJsonElement(text).jsonObject["organic"]?.jsonArray
                    ?.mapNotNull { el ->
                        val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                        SearchProvider.SearchResult(
                            o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            o["link"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            o["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty().take(400),
                        )
                    }.orEmpty()
            }
        }
}

/** Brave Search */
class BraveSearch(private val apiKey: String) : SearchProvider {
    override val name = "Brave"
    override suspend fun search(query: String, maxResults: Int): List<SearchProvider.SearchResult> =
        withContext(Dispatchers.IO) {
            val url = "https://api.search.brave.com/res/v1/web/search?q=" +
                URLEncoder.encode(query, "UTF-8") + "&count=$maxResults"
            val req = Request.Builder()
                .url(url)
                .header("X-Subscription-Token", apiKey)
                .header("Accept", "application/json")
                .build()
            searchHttp.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("Brave HTTP ${resp.code}")
                sJson.parseToJsonElement(text).jsonObject["web"]?.jsonObject
                    ?.get("results")?.jsonArray?.mapNotNull { el ->
                        val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                        SearchProvider.SearchResult(
                            o["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            o["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            o["description"]?.jsonPrimitive?.contentOrNull.orEmpty().take(400),
                        )
                    }.orEmpty()
            }
        }
}

/** DuckDuckGo HTML（免费 fallback） */
class DuckDuckGoSearch : SearchProvider {
    override val name = "DuckDuckGo"
    override suspend fun search(query: String, maxResults: Int): List<SearchProvider.SearchResult> =
        withContext(Dispatchers.IO) {
            val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .build()
            val html = searchHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("DuckDuckGo HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
            val linkRe = Regex(
                """<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
                RegexOption.DOT_MATCHES_ALL,
            )
            val snippetRe = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            val links = linkRe.findAll(html).toList()
            val snippets = snippetRe.findAll(html).toList()
            links.take(maxResults).mapIndexed { i, m ->
                val title = stripTags(m.groupValues[2])
                val href = cleanUrl(m.groupValues[1])
                val snippet = snippets.getOrNull(i)?.let { stripTags(it.groupValues[1]).take(300) } ?: ""
                SearchProvider.SearchResult(title, href, snippet)
            }.filter { it.url.isNotBlank() }
        }

    private fun cleanUrl(raw: String): String {
        val u = if (raw.startsWith("//")) "https:$raw" else raw
        return runCatching {
            val idx = u.indexOf("uddg=")
            if (idx >= 0) URLDecoder.decode(u.substring(idx + 5).substringBefore("&"), "UTF-8") else u
        }.getOrDefault(u)
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("(?s)<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#x27;", "'").replace("&nbsp;", " ")
            .trim()
}

/** 搜索管理器（全局单例，由 SettingsRepository 绑定） */
object SearchManager {
    @Volatile private var provider: SearchProvider = DuckDuckGoSearch()

    fun set(p: SearchProvider?) { provider = p ?: DuckDuckGoSearch() }
    fun get(): SearchProvider = provider
    fun currentName(): String = provider.name
}