package com.example.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

data class BrowserPageState(
    val currentUrl: String = "https://duckduckgo.com",
    val currentTitle: String = "Aqua Browser",
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val lastExtractedText: String = "",
    val lastError: String? = null
)

class AquaBrowserController(private val context: Context) {

    private var webViewRef: WebView? = null

    private val _pageState = MutableStateFlow(BrowserPageState())
    val pageState: StateFlow<BrowserPageState> = _pageState.asStateFlow()

    private val _pendingUrl = MutableStateFlow<String?>(null)
    val pendingUrl: StateFlow<String?> = _pendingUrl.asStateFlow()

    fun attachWebView(webView: WebView) {
        webViewRef = webView
    }

    fun detachWebView() {
        webViewRef = null
    }

    fun updatePageState(transform: (BrowserPageState) -> BrowserPageState) {
        _pageState.value = transform(_pageState.value)
    }

    fun normalizeUrlOrSearch(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return "https://duckduckgo.com"
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.contains('.') && !trimmed.contains(' ')) return "https://$trimmed"
        return "https://duckduckgo.com/?q=${Uri.encode(trimmed)}"
    }

    fun navigateTo(urlOrQuery: String): String {
        val resolved = normalizeUrlOrSearch(urlOrQuery)
        _pendingUrl.value = resolved
        val wv = webViewRef
        if (wv != null) {
            wv.post { wv.loadUrl(resolved) }
        }
        return resolved
    }

    fun consumePendingUrl(): String? {
        val u = _pendingUrl.value
        _pendingUrl.value = null
        return u
    }

    fun goBack() {
        webViewRef?.post { if (webViewRef?.canGoBack() == true) webViewRef?.goBack() }
    }

    fun goForward() {
        webViewRef?.post { if (webViewRef?.canGoForward() == true) webViewRef?.goForward() }
    }

    fun reload() {
        webViewRef?.post { webViewRef?.reload() }
    }

    suspend fun extractCurrentPageText(): Result<String> = suspendCancellableCoroutine { cont ->
        val wv = webViewRef
        if (wv == null) {
            cont.resume(
                Result.failure(
                    IllegalStateException("Aqua Browser tab is not currently open. Navigate to a webpage first.")
                )
            )
            return@suspendCancellableCoroutine
        }
        wv.post {
            val js = """
                (function() {
                    var title = document.title || '';
                    var url = window.location.href || '';
                    var text = document.body ? (document.body.innerText || '') : '';
                    return JSON.stringify({
                        title: title,
                        url: url,
                        text: text.substring(0, 12000)
                    });
                })();
            """.trimIndent()
            wv.evaluateJavascript(js) { rawResult ->
                try {
                    val unquoted = unquoteJsString(rawResult)
                    val obj = JSONObject(unquoted)
                    val title = obj.optString("title", "")
                    val url = obj.optString("url", "")
                    val text = obj.optString("text", "")
                    val formatted = "Title: $title\nURL: $url\n\n$text"
                    _pageState.value = _pageState.value.copy(
                        currentTitle = title.ifBlank { _pageState.value.currentTitle },
                        currentUrl = url.ifBlank { _pageState.value.currentUrl },
                        lastExtractedText = text
                    )
                    if (cont.isActive) cont.resume(Result.success(formatted))
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(Result.failure(e))
                }
            }
        }
    }

    /**
     * Fills standard non-CAPTCHA, non-security form inputs on the active page after explicit user approval.
     */
    suspend fun fillStandardFormField(selectorOrName: String, value: String): Result<String> =
        suspendCancellableCoroutine { cont ->
            val wv = webViewRef
            if (wv == null) {
                cont.resume(Result.failure(IllegalStateException("Aqua Browser is not active.")))
                return@suspendCancellableCoroutine
            }
            val safeSel = JSONObject.quote(selectorOrName)
            val safeVal = JSONObject.quote(value)
            wv.post {
                val js = """
                    (function() {
                        var q = $safeSel;
                        var v = $safeVal;
                        var el = document.querySelector(q) || document.querySelector('[name="' + q.replace(/"/g, '') + '"]') || document.getElementById(q);
                        if (!el) return 'NOT_FOUND';
                        if (el.type && el.type.toLowerCase() === 'password') return 'BLOCKED_SENSITIVE';
                        el.focus();
                        el.value = v;
                        el.dispatchEvent(new Event('input', { bubbles: true }));
                        el.dispatchEvent(new Event('change', { bubbles: true }));
                        return 'FILLED';
                    })();
                """.trimIndent()
                wv.evaluateJavascript(js) { raw ->
                    val status = unquoteJsString(raw)
                    val result = when (status) {
                        "FILLED" -> Result.success("Filled field '$selectorOrName' on active page.")
                        "BLOCKED_SENSITIVE" -> Result.failure(
                            SecurityException("Refused to auto-fill password or protected authentication fields.")
                        )
                        else -> Result.failure(
                            IllegalStateException("Form input '$selectorOrName' not found on current page.")
                        )
                    }
                    if (cont.isActive) cont.resume(result)
                }
            }
        }

    fun enqueueDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?): Result<String> =
        runCatching {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription("Downloaded via Aqua Browser")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            "Started download for $fileName."
        }

    private fun unquoteJsString(raw: String?): String {
        if (raw == null || raw == "null") return "{}"
        return try {
            JSONObject("{\"v\":$raw}").getString("v")
        } catch (_: Exception) {
            raw.trim('"')
        }
    }
}
