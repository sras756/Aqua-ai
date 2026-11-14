package com.example.network

import android.net.Uri
import com.example.browser.AquaBrowserController
import com.example.browser.BrowserLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

data class WebSearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    val sourceEngine: String
)

data class WebSearchResult(
    val query: String,
    val instantAnswer: String,
    val hits: List<WebSearchHit>
)

data class WebFetchResult(
    val finalUrl: String,
    val statusCode: Int,
    val contentType: String,
    val title: String,
    val textContent: String,
    val links: List<BrowserLink>
)

data class WebHttpResult(
    val method: String,
    val finalUrl: String,
    val statusCode: Int,
    val contentType: String,
    val body: String
)

class AquaWebClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {

    suspend fun searchWeb(query: String, maxResults: Int = 8): Result<WebSearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanQuery = query.trim()
            require(cleanQuery.isNotEmpty()) { "Search query cannot be empty." }
            val limit = maxResults.coerceIn(1, 15)
            val encoded = Uri.encode(cleanQuery)

            val hits = mutableListOf<WebSearchHit>()
            val seenUrls = linkedSetOf<String>()
            var instantAnswer = ""

            // 1. Live DuckDuckGo HTML organic search
            runCatching {
                val ddgReq = Request.Builder()
                    .url("https://html.duckduckgo.com/html/?q=$encoded")
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .build()
                httpClient.newCall(ddgReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val html = readLimitedBody(resp.body?.string().orEmpty(), 262_144)
                        parseDuckDuckGoHtml(html).forEach { hit ->
                            if (hits.size < limit && seenUrls.add(hit.url)) {
                                hits.add(hit)
                            }
                        }
                    }
                }
            }

            // 2. Live DuckDuckGo Instant Answer API (abstracts & official topic summaries)
            runCatching {
                val iaReq = Request.Builder()
                    .url("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
                    .header("User-Agent", API_USER_AGENT)
                    .build()
                httpClient.newCall(iaReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val obj = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
                        val abstractText = obj.optString("AbstractText", "").trim()
                        val abstractUrl = obj.optString("AbstractURL", "").trim()
                        val heading = obj.optString("Heading", "").trim()
                        val answer = obj.optString("Answer", "").trim()
                        instantAnswer = listOf(answer, abstractText).firstOrNull { it.isNotBlank() }.orEmpty()
                        if (abstractUrl.startsWith("http") && seenUrls.add(abstractUrl)) {
                            hits.add(
                                WebSearchHit(
                                    title = heading.ifBlank { abstractUrl },
                                    url = abstractUrl,
                                    snippet = abstractText.ifBlank { "Official reference" }.take(280),
                                    sourceEngine = "DuckDuckGo Instant"
                                )
                            )
                        }
                        val related = obj.optJSONArray("RelatedTopics")
                        if (related != null) {
                            for (i in 0 until related.length()) {
                                if (hits.size >= limit) break
                                val item = related.optJSONObject(i) ?: continue
                                val url = item.optString("FirstURL", "").trim()
                                val txt = item.optString("Text", "").trim()
                                if (url.startsWith("http") && txt.isNotBlank() && seenUrls.add(url)) {
                                    hits.add(
                                        WebSearchHit(
                                            title = txt.substringBefore(" - ").take(90),
                                            url = url,
                                            snippet = txt.take(260),
                                            sourceEngine = "DuckDuckGo"
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Live Wikipedia Search API fallback/enrichment when fewer hits were returned
            if (hits.size < 4) {
                runCatching {
                    val wikiReq = Request.Builder()
                        .url("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&utf8=&format=json&srlimit=5")
                        .header("User-Agent", API_USER_AGENT)
                        .build()
                    httpClient.newCall(wikiReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val root = JSONObject(resp.body?.string().orEmpty().ifBlank { "{}" })
                            val arr = root.optJSONObject("query")?.optJSONArray("search")
                            if (arr != null) {
                                for (i in 0 until arr.length()) {
                                    if (hits.size >= limit) break
                                    val item = arr.optJSONObject(i) ?: continue
                                    val title = item.optString("title", "").trim()
                                    if (title.isBlank()) continue
                                    val wikiUrl = "https://en.wikipedia.org/wiki/${Uri.encode(title.replace(' ', '_'))}"
                                    val snippet = item.optString("snippet", "")
                                        .replace(Regex("<[^>]+>"), " ")
                                        .replace("&quot;", "\"")
                                        .replace("&amp;", "&")
                                        .replace(Regex("\\s+"), " ")
                                        .trim()
                                    if (seenUrls.add(wikiUrl)) {
                                        hits.add(
                                            WebSearchHit(
                                                title = "$title — Wikipedia",
                                                url = wikiUrl,
                                                snippet = snippet.take(260),
                                                sourceEngine = "Wikipedia"
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (hits.isEmpty() && instantAnswer.isBlank()) {
                throw IllegalStateException("No live search results returned for '$cleanQuery'. Check network connectivity or try opening a direct URL.")
            }

            WebSearchResult(
                query = cleanQuery,
                instantAnswer = instantAnswer,
                hits = hits.take(limit)
            )
        }
    }

    suspend fun fetchWebpage(urlInput: String, maxChars: Int = 14_000): Result<WebFetchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedUrl = validatePublicHttpUrl(urlInput)
            val req = Request.Builder()
                .url(normalizedUrl)
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain;q=0.9,*/*;q=0.8")
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val contentType = resp.header("Content-Type").orEmpty().ifBlank { "text/plain" }
                val rawBody = readLimitedBody(resp.body?.string().orEmpty(), 524_288)
                if (!resp.isSuccessful && rawBody.isBlank()) {
                    throw IllegalStateException("HTTP ${resp.code} from $finalUrl")
                }

                val isHtml = contentType.contains("html", ignoreCase = true) ||
                    rawBody.trimStart().startsWith("<!DOCTYPE", ignoreCase = true) ||
                    rawBody.trimStart().startsWith("<html", ignoreCase = true)

                if (isHtml) {
                    val (title, text, links) = AquaBrowserController.parseHtmlForReader(rawBody, finalUrl)
                    WebFetchResult(
                        finalUrl = finalUrl,
                        statusCode = resp.code,
                        contentType = contentType,
                        title = title.ifBlank { finalUrl },
                        textContent = text.take(maxChars).ifBlank { "(No readable text extracted from HTML.)" },
                        links = links
                    )
                } else {
                    WebFetchResult(
                        finalUrl = finalUrl,
                        statusCode = resp.code,
                        contentType = contentType,
                        title = finalUrl,
                        textContent = rawBody.take(maxChars),
                        links = emptyList()
                    )
                }
            }
        }
    }

    suspend fun httpRequest(
        method: String,
        urlInput: String,
        headersJson: String? = null,
        body: String? = null
    ): Result<WebHttpResult> = withContext(Dispatchers.IO) {
        runCatching {
            val upperMethod = method.trim().uppercase().ifBlank { "GET" }
            require(upperMethod in ALLOWED_HTTP_METHODS) {
                "Unsupported HTTP method '$upperMethod'. Allowed: ${ALLOWED_HTTP_METHODS.joinToString()}"
            }
            val normalizedUrl = validatePublicHttpUrl(urlInput)

            val builder = Request.Builder()
                .url(normalizedUrl)
                .header("User-Agent", API_USER_AGENT)

            if (!headersJson.isNullOrBlank()) {
                val obj = runCatching { JSONObject(headersJson) }.getOrElse {
                    throw IllegalArgumentException("headersJson must be a valid JSON object.")
                }
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optString(k, "")
                    if (k.isNotBlank() && v.isNotBlank()) {
                        builder.header(k, v)
                    }
                }
            }

            val reqBody = when (upperMethod) {
                "GET", "HEAD" -> null
                else -> (body ?: "").toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            }
            builder.method(upperMethod, reqBody)

            httpClient.newCall(builder.build()).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val contentType = resp.header("Content-Type").orEmpty()
                val respBody = readLimitedBody(resp.body?.string().orEmpty(), 262_144)
                WebHttpResult(
                    method = upperMethod,
                    finalUrl = finalUrl,
                    statusCode = resp.code,
                    contentType = contentType,
                    body = respBody.take(12_000)
                )
            }
        }
    }

    private fun readLimitedBody(raw: String, maxBytesApprox: Int): String {
        return if (raw.length <= maxBytesApprox) raw else raw.substring(0, maxBytesApprox)
    }

    companion object {
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36"
        private const val API_USER_AGENT = "AquaAI-Android/1.0 (https://example.com)"
        private val ALLOWED_HTTP_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")

        private val DDG_LINK_REGEX = Regex(
            "(?is)<a[^>]*class=[\"'][^\"']*result__a[^\"']*[\"'][^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>"
        )
        private val DDG_SNIPPET_REGEX = Regex(
            "(?is)<(?:a|div)[^>]*class=[\"'][^\"']*result__snippet[^\"']*[\"'][^>]*>(.*?)</(?:a|div)>"
        )
        private val HTML_TAG_REGEX = Regex("(?s)<[^>]+>")

        fun validatePublicHttpUrl(input: String): String {
            val trimmed = input.trim()
            require(trimmed.isNotEmpty()) { "URL cannot be empty." }
            val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "https://$trimmed"
            }
            val uri = URI(withScheme)
            val scheme = uri.scheme?.lowercase().orEmpty()
            require(scheme == "http" || scheme == "https") {
                "Only http:// and https:// URLs are permitted."
            }
            val host = uri.host?.lowercase().orEmpty()
            require(host.isNotBlank()) { "URL must contain a valid hostname." }
            require(isPublicInternetHost(host)) {
                "Refused access to local/private network host '$host'."
            }
            return withScheme
        }

        fun isPublicInternetHost(host: String): Boolean {
            val clean = host.trim().lowercase().trim('[', ']')
            if (clean.isEmpty()) return false
            if (clean == "localhost" || clean.endsWith(".local") || clean.endsWith(".internal")) return false
            if (clean == "::1" || clean == "0.0.0.0" || clean == "127.0.0.1" || clean == "169.254.169.254") {
                return false
            }
            if (clean.startsWith("127.") || clean.startsWith("10.") || clean.startsWith("192.168.") || clean.startsWith("169.254.")) {
                return false
            }
            val match172 = Regex("^172\\.(\\d{1,3})\\.").find(clean)
            if (match172 != null) {
                val secondOctet = match172.groupValues[1].toIntOrNull() ?: 0
                if (secondOctet in 16..31) return false
            }
            val resolved = runCatching { InetAddress.getAllByName(clean) }.getOrNull()
            if (resolved != null && resolved.any {
                    it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress
                }
            ) {
                return false
            }
            return true
        }

        fun parseDuckDuckGoHtml(html: String): List<WebSearchHit> {
            val links = DDG_LINK_REGEX.findAll(html).toList()
            if (links.isEmpty()) return emptyList()
            val snippets = DDG_SNIPPET_REGEX.findAll(html).toList()
            val out = mutableListOf<WebSearchHit>()
            for (i in links.indices) {
                val rawHref = links[i].groupValues[1].trim()
                val resolvedUrl = resolveDdgRedirectUrl(rawHref)
                if (!resolvedUrl.startsWith("http://") && !resolvedUrl.startsWith("https://")) continue
                val title = stripTags(links[i].groupValues[2]).take(120)
                val snippet = snippets.getOrNull(i)?.let { stripTags(it.groupValues[1]) }.orEmpty().take(260)
                if (title.isNotBlank()) {
                    out.add(
                        WebSearchHit(
                            title = title,
                            url = resolvedUrl,
                            snippet = snippet,
                            sourceEngine = "DuckDuckGo"
                        )
                    )
                }
            }
            return out
        }

        private fun resolveDdgRedirectUrl(rawHref: String): String {
            val unescaped = rawHref.replace("&amp;", "&")
            if (unescaped.contains("uddg=")) {
                val after = unescaped.substringAfter("uddg=").substringBefore("&")
                val decoded = runCatching { URLDecoder.decode(after, "UTF-8") }.getOrNull()
                if (!decoded.isNullOrBlank()) return decoded
            }
            return if (unescaped.startsWith("//")) "https:$unescaped" else unescaped
        }

        private fun stripTags(raw: String): String {
            return HTML_TAG_REGEX.replace(raw, " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}
