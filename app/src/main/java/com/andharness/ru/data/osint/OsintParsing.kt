package com.andharness.ru.data.osint

import com.andharness.ru.domain.serialization.AppJson
import com.andharness.ru.domain.formatting.collapseWhitespace
import kotlinx.serialization.json.Json

/**
 * Pure HTML → [OsintSource] / summary parsing, extracted from the web engine's DuckDuckGo HTML
 * handling so it can be reused by [OsintEngine] and unit-tested on the JVM.
 *
 * References:
 * - DuckDuckGo HTML endpoint used by `WebEngine`: `https://html.duckduckgo.com/html/?q=`
 * - Regex-based attribute/text extraction is a deliberate, bounded heuristic (no HTML parser dep).
 */
object OsintParsing {

    private const val DEFAULT_SEARCH_URL = "https://html.duckduckgo.com/html/?q="

    /** Search endpoint (same free HTML frontend the in-app web engine already uses). */
    fun searchUrl(query: String): String = DEFAULT_SEARCH_URL + java.net.URLEncoder.encode(query, "UTF-8")

    private val ANCHOR_PATTERNS = listOf(
        Regex(
            """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ),
        Regex(
            """<a[^>]*href="([^"]+)"[^>]*class="[^"]*result__a[^"]*"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ),
        Regex(
            """<a[^>]*class="[^"]*result-link[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ),
    )

    private val SNIPPET_PATTERN = Regex(
        """<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val TAG_PATTERN = Regex("<[^>]*>")
    private val SCRIPT_PATTERN = Regex(
        "<(script|style|noscript|svg|template)[^>]*>.*?</\\1>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** Extracts candidate sources from a DuckDuckGo-style HTML results page (bounded by the caller). */
    fun parseSearchHtml(html: String): List<OsintSource> {
        val snippets = SNIPPET_PATTERN.findAll(html).map { stripHtml(it.groupValues[1]) }.toList()
        val out = LinkedHashMap<String, OsintSource>()
        for (pattern in ANCHOR_PATTERNS) {
            for (match in pattern.findAll(html)) {
                val url = unwrapRedirect(match.groupValues[1])
                if (!url.startsWith("http")) continue
                val title = stripHtml(match.groupValues[2])
                if (title.length < 2) continue
                if (out.containsKey(url)) continue
                out[url] = OsintSource(title, url, snippets.getOrNull(out.size).orEmpty())
            }
            if (out.isNotEmpty()) break
        }
        return out.values.toList()
    }

    /** Resolves DuckDuckGo's `/l/?uddg=` redirect to the real target URL. */
    fun unwrapRedirect(href: String): String {
        var value = href.trim()
        if (value.startsWith("//")) value = "https:$value"
        value = value.replace("&amp;", "&")
        if (value.contains("uddg=")) {
            val match = Regex("""[?&]uddg=([^&]+)""").find(value)
            if (match != null) {
                return try {
                    java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
                } catch (e: Exception) {
                    value
                }
            }
        }
        return value
    }

    /**
     * Extracts a short, plain-text summary from an arbitrary HTML page: strips scripts/styles and
     * tags, collapses whitespace and cuts at [OsintBudget.MAX_SUMMARY_CHARS]. Returns `null` when
     * there is no usable text — callers must report "not found" instead of inventing content.
     */
    fun extractSummary(html: String): String? {
        val withoutScripts = SCRIPT_PATTERN.replace(html, " ")
        val text = stripHtml(withoutScripts)
        if (text.length < 40) return null
        return text.take(OsintBudget.MAX_SUMMARY_CHARS).trim().ifEmpty { null }
    }

    /** Removes tags and decodes the few entities that matter for plain-text output. */
    fun stripHtml(s: String): String = collapseWhitespace(
        s.replace(TAG_PATTERN, " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#x27;", "'")
            .replace("&#39;", "'")
    )

    /** Shared JSON instance for the persisted OSINT cache. */
    val json: Json = AppJson.LenientEncodeDefaults
}
