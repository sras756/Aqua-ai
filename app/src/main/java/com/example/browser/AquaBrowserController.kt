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
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

data class BrowserLink(
    val text: String,
    val href: String
)

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

    fun detachWebView(webView: WebView? = null) {
        if (webView == null || webViewRef === webView) {
            webViewRef = null
        }
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

    private fun runOnWebView(action: (WebView) -> Unit) {
        val wv = webViewRef ?: return
        wv.post {
            if (webViewRef === wv && wv.isAttachedToWindow) {
                runCatching { action(wv) }
            }
        }
    }

    fun goBack() {
        runOnWebView { wv -> if (wv.canGoBack()) wv.goBack() }
    }

    fun goForward() {
        runOnWebView { wv -> if (wv.canGoForward()) wv.goForward() }
    }

    fun reload() {
        runOnWebView { wv -> wv.reload() }
    }

    fun findInPage(query: String) {
        val trimmed = query.trim()
        runOnWebView { wv ->
            if (trimmed.isEmpty()) {
                wv.clearMatches()
            } else {
                wv.findAllAsync(trimmed)
            }
        }
    }

    fun findNext(forward: Boolean) {
        runOnWebView { wv -> wv.findNext(forward) }
    }

    fun clearFindMatches() {
        runOnWebView { wv -> wv.clearMatches() }
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

    suspend fun extractCurrentPageLinks(): Result<List<BrowserLink>> = suspendCancellableCoroutine { cont ->
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
                    var seen = {};
                    var out = [];
                    var anchors = document.querySelectorAll('a[href]');
                    for (var i = 0; i < anchors.length && out.length < 25; i++) {
                        var href = anchors[i].href || '';
                        if ((href.indexOf('http://') === 0 || href.indexOf('https://') === 0) && !seen[href]) {
                            seen[href] = true;
                            var txt = (anchors[i].innerText || anchors[i].textContent || '').trim().replace(/\s+/g, ' ');
                            out.push({ text: txt.substring(0, 90), href: href });
                        }
                    }
                    return JSON.stringify(out);
                })();
            """.trimIndent()
            wv.evaluateJavascript(js) { rawResult ->
                try {
                    val unquoted = unquoteJsString(rawResult)
                    val arr = JSONArray(unquoted)
                    val links = (0 until arr.length()).map { idx ->
                        val obj = arr.getJSONObject(idx)
                        BrowserLink(
                            text = obj.optString("text", "").ifBlank { obj.optString("href", "") },
                            href = obj.optString("href", "")
                        )
                    }.filter { it.href.isNotBlank() }
                    if (cont.isActive) cont.resume(Result.success(links))
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
