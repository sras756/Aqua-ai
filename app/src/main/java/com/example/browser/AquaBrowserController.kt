package com.example.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebStorage
import android.webkit.WebView
import com.example.core.browser.PromptInjectionFirewall
import com.example.core.browser.PromptInjectionScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import kotlin.coroutines.resume

data class BrowserLink(
    val text: String,
    val href: String
)

data class BrowserTabSession(
    val tabId: String = "tab_${UUID.randomUUID().toString().replace("-", "").take(10)}",
    val title: String = "New Tab",
    val url: String = "https://duckduckgo.com",
    val isIncognito: Boolean = false,
    val updatedAtMs: Long = System.currentTimeMillis()
)

data class BrowserPageState(
    val currentUrl: String = "https://duckduckgo.com",
    val currentTitle: String = "Aqua Browser",
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isIncognito: Boolean = false,
    val lastExtractedText: String = "",
    val lastSecurityWarning: String? = null,
    val lastError: String? = null
)

class AquaBrowserController(private val context: Context) {

    private var webViewRef: WebView? = null

    private val _pageState = MutableStateFlow(BrowserPageState())
    val pageState: StateFlow<BrowserPageState> = _pageState.asStateFlow()

    private val defaultTab = BrowserTabSession(tabId = "tab_main", title = "Aqua Start", url = "https://duckduckgo.com")
    private val _tabSessions = MutableStateFlow(listOf(defaultTab))
    val tabSessions: StateFlow<List<BrowserTabSession>> = _tabSessions.asStateFlow()

    private val _activeTabId = MutableStateFlow(defaultTab.tabId)
    val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    private val _pendingUrl = MutableStateFlow<String?>(null)
    val pendingUrl: StateFlow<String?> = _pendingUrl.asStateFlow()

    private val _hasActiveSession = MutableStateFlow(false)
    val hasActiveSession: StateFlow<Boolean> = _hasActiveSession.asStateFlow()

    fun attachWebView(webView: WebView) {
        webViewRef = webView
    }

    fun detachWebView(webView: WebView? = null) {
        if (webView == null || webViewRef === webView) {
            webViewRef = null
        }
    }

    fun activateSession() {
        _hasActiveSession.value = true
    }

    fun updatePageState(transform: (BrowserPageState) -> BrowserPageState) {
        val next = transform(_pageState.value)
        _pageState.value = next
        val activeId = _activeTabId.value
        _tabSessions.value = _tabSessions.value.map { t ->
            if (t.tabId == activeId) {
                t.copy(
                    title = next.currentTitle.ifBlank { t.title },
                    url = next.currentUrl.ifBlank { t.url },
                    isIncognito = next.isIncognito,
                    updatedAtMs = System.currentTimeMillis()
                )
            } else {
                t
            }
        }
    }

    fun isCurrentTabIncognito(): Boolean =
        _tabSessions.value.firstOrNull { it.tabId == _activeTabId.value }?.isIncognito == true || _pageState.value.isIncognito

    fun openNewTab(urlOrQuery: String = "https://duckduckgo.com", isIncognito: Boolean = false): BrowserTabSession {
        val resolved = normalizeUrlOrSearch(urlOrQuery)
        val tab = BrowserTabSession(
            title = if (isIncognito) "Incognito Tab" else resolved.removePrefix("https://").take(28),
            url = resolved,
            isIncognito = isIncognito
        )
        _tabSessions.value = _tabSessions.value + tab
        _activeTabId.value = tab.tabId
        _pageState.value = _pageState.value.copy(
            currentUrl = resolved,
            currentTitle = tab.title,
            isIncognito = isIncognito,
            lastError = null
        )
        navigateTo(resolved)
        return tab
    }

    fun switchTab(tabId: String) {
        val target = _tabSessions.value.firstOrNull { it.tabId == tabId } ?: return
        _activeTabId.value = target.tabId
        _pageState.value = _pageState.value.copy(
            currentUrl = target.url,
            currentTitle = target.title,
            isIncognito = target.isIncognito
        )
        navigateTo(target.url)
    }

    fun closeTab(tabId: String) {
        val closing = _tabSessions.value.firstOrNull { it.tabId == tabId }
        val remaining = _tabSessions.value.filterNot { it.tabId == tabId }
        if (closing?.isIncognito == true) {
            wipeIncognitoWebStorage()
        }
        if (remaining.isEmpty()) {
            val fresh = BrowserTabSession(tabId = "tab_main", title = "Aqua Start", url = "https://duckduckgo.com")
            _tabSessions.value = listOf(fresh)
            _activeTabId.value = fresh.tabId
            _pageState.value = BrowserPageState()
            _hasActiveSession.value = false
        } else {
            _tabSessions.value = remaining
            if (_activeTabId.value == tabId) {
                switchTab(remaining.last().tabId)
            }
        }
    }

    fun wipeIncognitoWebStorage() {
        runCatching { CookieManager.getInstance().removeSessionCookies(null) }
        runCatching { CookieManager.getInstance().flush() }
        runCatching { WebStorage.getInstance().deleteAllData() }
        runOnWebView { wv -> wv.clearCache(true); wv.clearHistory() }
    }

    fun normalizeUrlOrSearch(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return "https://duckduckgo.com"
        val lower = trimmed.lowercase()
        if (lower.startsWith("file://") || lower.startsWith("content://") || lower.startsWith("javascript:")) {
            return "https://duckduckgo.com"
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) return trimmed
        if (trimmed.contains('.') && !trimmed.contains(' ')) return "https://$trimmed"
        return "https://duckduckgo.com/?q=${Uri.encode(trimmed)}"
    }

    fun navigateTo(urlOrQuery: String): String {
        val resolved = normalizeUrlOrSearch(urlOrQuery)
        _hasActiveSession.value = true
        _pendingUrl.value = resolved
        updatePageState {
            it.copy(
                currentUrl = resolved,
                lastError = null
            )
        }
        val wv = webViewRef
        if (wv != null && wv.isAttachedToWindow) {
            wv.post {
                if (webViewRef === wv && wv.isAttachedToWindow) {
                    runCatching {
                        _pendingUrl.value = null
                        wv.loadUrl(resolved)
                    }
                }
            }
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

    suspend fun extractCurrentPageText(): Result<String> {
        val wv = webViewRef
        if (wv != null && wv.isAttachedToWindow) {
            val fromWebView = extractTextFromWebView(wv)
            if (fromWebView.isSuccess) return fromWebView
        }
        return fetchUrlAsReader(_pageState.value.currentUrl).map { it.first }
    }

    /**
     * Extracts page text and wraps it in PromptInjectionFirewall untrusted-data boundaries for Gemini.
     */
    suspend fun extractSafelyWrappedPageText(): Result<PromptInjectionScanResult> {
        return extractCurrentPageText().map { raw ->
            val scan = PromptInjectionFirewall.inspectAndWrapWebContent(
                url = _pageState.value.currentUrl,
                title = _pageState.value.currentTitle,
                rawText = raw
            )
            if (scan.threatDetected) {
                updatePageState {
                    it.copy(lastSecurityWarning = "Prompt-injection attempt neutralized: ${scan.blockedThreats.joinToString(", ")}")
                }
            }
            scan
        }
    }

    private suspend fun extractTextFromWebView(wv: WebView): Result<String> = suspendCancellableCoroutine { cont ->
        wv.post {
            if (webViewRef !== wv || !wv.isAttachedToWindow) {
                if (cont.isActive) cont.resume(Result.failure(IllegalStateException("WebView detached.")))
                return@post
            }
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

    suspend fun extractCurrentPageLinks(): Result<List<BrowserLink>> {
        val wv = webViewRef
        if (wv != null && wv.isAttachedToWindow) {
            val fromWebView = extractLinksFromWebView(wv)
            if (fromWebView.isSuccess) return fromWebView
        }
        return fetchUrlAsReader(_pageState.value.currentUrl).map { it.second }
    }

    private suspend fun extractLinksFromWebView(wv: WebView): Result<List<BrowserLink>> = suspendCancellableCoroutine { cont ->
        wv.post {
            if (webViewRef !== wv || !wv.isAttachedToWindow) {
                if (cont.isActive) cont.resume(Result.failure(IllegalStateException("WebView detached.")))
                return@post
            }
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
     * Fetches and extracts real webpage content & links directly over HTTP(S) without spawning a Chromium renderer.
     */
    suspend fun fetchUrlAsReader(urlString: String): Result<Pair<String, List<BrowserLink>>> = withContext(Dispatchers.IO) {
        runCatching {
            val normalized = normalizeUrlOrSearch(urlString)
            require(normalized.startsWith("https://") || normalized.startsWith("http://")) {
                "Only HTTP/HTTPS URLs are supported."
            }
            val conn = (URL(normalized).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8")
            }
            val finalUrl = conn.url.toString()
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val html = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val buf = CharArray(524_288)
                val read = reader.read(buf)
                if (read > 0) String(buf, 0, read) else ""
            }.orEmpty()
            conn.disconnect()
            if (code >= 400 && html.isBlank()) {
                throw IllegalStateException("HTTP $code while loading $finalUrl")
            }
            val (title, bodyText, links) = parseHtmlForReader(html, finalUrl)
            val formatted = "Title: ${title.ifBlank { finalUrl }}\nURL: $finalUrl\n\n${bodyText.ifBlank { "(No readable text extracted from this page.)" }}"
            _pageState.value = _pageState.value.copy(
                currentUrl = finalUrl,
                currentTitle = title.ifBlank { _pageState.value.currentTitle },
                lastExtractedText = bodyText,
                isLoading = false
            )
            formatted to links
        }
    }

    /**
     * Fills standard non-CAPTCHA, non-security form inputs on the active page after explicit user approval.
     */
    suspend fun fillStandardFormField(selectorOrName: String, value: String): Result<String> =
        suspendCancellableCoroutine { cont ->
            val wv = webViewRef
            if (wv == null || !wv.isAttachedToWindow) {
                cont.resume(Result.failure(IllegalStateException("Aqua Browser WebView is not active.")))
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

    fun enqueueDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?): Result<String> {
        val risk = PromptInjectionFirewall.evaluateDownloadRisk(url, contentDisposition, mimeType)
        if (risk.isHighRisk) {
            updatePageState { it.copy(lastSecurityWarning = risk.warningMessage) }
            return Result.failure(SecurityException(risk.warningMessage))
        }
        return runCatching {
            val fileName = risk.fileName
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
    }

    private fun unquoteJsString(raw: String?): String {
        if (raw == null || raw == "null") return "{}"
        return try {
            JSONObject("{\"v\":$raw}").getString("v")
        } catch (_: Exception) {
            raw.trim('"')
        }
    }

    companion object {
        private val SCRIPT_STYLE_REGEX = Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>")
        private val TITLE_REGEX = Regex("(?is)<title[^>]*>(.*?)</title>")
        private val ANCHOR_REGEX = Regex("(?is)<a\\s+[^>]*href\\s*=\\s*[\"']([^\"'#]+)[\"'][^>]*>(.*?)</a>")
        private val TAG_REGEX = Regex("(?s)<[^>]+>")
        private val WHITESPACE_REGEX = Regex("[ \\t\\x0B\\f\\r]+")
        private val NEWLINE_REGEX = Regex("\\n{3,}")

        fun parseHtmlForReader(html: String, baseUrl: String): Triple<String, String, List<BrowserLink>> {
            val titleRaw = TITLE_REGEX.find(html)?.groupValues?.getOrNull(1).orEmpty()
            val title = decodeHtmlEntities(TAG_REGEX.replace(titleRaw, " ")).trim().take(180)

            val cleanedHtml = SCRIPT_STYLE_REGEX.replace(html, "\n")
            val baseUri = runCatching { URI(baseUrl) }.getOrNull()
            val seenHrefs = linkedSetOf<String>()
            val links = mutableListOf<BrowserLink>()

            for (match in ANCHOR_REGEX.findAll(cleanedHtml)) {
                if (links.size >= 25) break
                val rawHref = decodeHtmlEntities(match.groupValues[1]).trim()
                if (rawHref.startsWith("javascript:", ignoreCase = true) || rawHref.startsWith("mailto:", ignoreCase = true)) {
                    continue
                }
                val resolvedHref = runCatching {
                    if (baseUri != null) baseUri.resolve(rawHref).toString() else rawHref
                }.getOrNull().orEmpty()
                if ((resolvedHref.startsWith("http://") || resolvedHref.startsWith("https://")) && seenHrefs.add(resolvedHref)) {
                    val label = decodeHtmlEntities(TAG_REGEX.replace(match.groupValues[2], " "))
                        .replace(WHITESPACE_REGEX, " ")
                        .trim()
                        .take(90)
                        .ifBlank { resolvedHref }
                    links.add(BrowserLink(text = label, href = resolvedHref))
                }
            }

            val withBreaks = cleanedHtml.replace(Regex("(?i)</?(p|div|li|br|h[1-6]|tr)[^>]*>"), "\n")
            val plainText = decodeHtmlEntities(TAG_REGEX.replace(withBreaks, " "))
                .lines()
                .map { it.replace(WHITESPACE_REGEX, " ").trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
                .replace(NEWLINE_REGEX, "\n\n")
                .take(12_000)

            return Triple(title, plainText, links)
        }

        private fun decodeHtmlEntities(input: String): String {
            return input
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ")
        }
    }
}
