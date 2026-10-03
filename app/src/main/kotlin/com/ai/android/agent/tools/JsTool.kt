package com.ai.android.agent.tools

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.resume

/**
 * 在 WebView V8 引擎执行 JavaScript。
 * 代码里调用 __send(结果) 把结果回传给 Kotlin。
 */
class JsTool(private val context: Context) : ToolExecutor {

    override val name = "run_js"

    override suspend fun execute(args: JsonObject): String {
        val code = args.str("code")
        if (code.isBlank()) throw IllegalStateException("code 不能为空")
        val timeoutSec = args.int("timeout_sec", 15).coerceIn(1, 60)

        return withContext(Dispatchers.Main) {
            val r = withTimeoutOrNull(timeoutSec * 1000L) { evalJs(code) }
            r ?: "⏱ 执行超时（${timeoutSec}s）：代码没有调用 __send(结果)"
        }
    }

    private suspend fun evalJs(code: String): String = suspendCancellableCoroutine { cont ->
        var done = false
        val webView = WebView(context)

        fun finish(text: String) {
            if (done) return
            done = true
            runCatching { webView.destroy() }
            if (cont.isActive) cont.resume(text)
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        webView.addJavascriptInterface(JsBridge { data ->
            Handler(Looper.getMainLooper()).post { finish(data) }
        }, "__bridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                runCatching { view?.evaluateJavascript(buildScript(code), null) }
                    .onFailure { finish("❌ 执行失败: ${it.message}") }
            }
        }

        cont.invokeOnCancellation {
            Handler(Looper.getMainLooper()).post { runCatching { webView.destroy() } }
        }

        runCatching { webView.loadDataWithBaseURL("file:///", HTML, "text/html", "utf-8", null) }
            .onFailure { finish("❌ WebView 初始化失败: ${it.message}") }
    }

    private fun buildScript(code: String): String =
        "(function(){ try { $code } catch (e) { __send('❌ JS 错误: ' + (e && e.message ? e.message : String(e))); } })();"

    /** JS → Kotlin 桥（命名类防止混淆剔除） */
    private class JsBridge(private val onResult: (String) -> Unit) {
        @JavascriptInterface
        fun send(data: String) {
            onResult(data)
        }
    }

    companion object {
        private const val HTML = """
<html><head><meta charset="utf-8"></head><body>
<script>
function __send(v) {
  try {
    window.__bridge.send(typeof v === 'string' ? v : JSON.stringify(v));
  } catch (e) {
    window.__bridge.send(String(v));
  }
}
</script>
ready
</body></html>
"""
    }
}
