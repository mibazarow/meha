package com.andharness.ru.data.osint

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Free-web OSINT for a phone number (update.md §2.5). No API keys, no paid services: it fetches the
 * same DuckDuckGo **HTML** search frontend the in-app web engine already uses, parses the candidate
 * results and enriches at most the first [OsintBudget.MAX_SOURCES] of them.
 *
 * Hard limits (enforced here, not by hope):
 * - total budget [OsintBudget.TIME_BUDGET_MS] (15 s) — every request is wrapped in a remaining-budget
 *   timeout and no new source is fetched once [OsintBudget.canFetchMore] is false;
 * - at most 5 sources ([OsintBudget.takeSources]);
 * - one byte cap per page ([OsintBudget.MAX_FETCH_BYTES]) via `Response.peekBody`, no retries.
 *
 * Etiquette: a best-effort `robots.txt` check per origin (bounded, cached in memory) gates every
 * source fetch; one request per source, no crawling, no hammering. If `robots.txt` cannot be read
 * the origin is treated as allowed, which is the standard interpretation of an absent file.
 *
 * Honesty: this NEVER fabricates. If the search returns nothing, or every fetch fails, the result
 * carries an explicit "not found" summary. Search snippets are reported as snippets, not as facts.
 *
 * References:
 * - `OkHttpClient.newBuilder` / `Request` / `Response.peekBody`:
 *   https://square.github.io/okhttp/4.x/okhttp/okhttp3/-response/peek-body/
 * - `kotlinx.coroutines.withTimeoutOrNull`: https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-timeout-or-null.html
 * - DuckDuckGo HTML endpoint (free, no key): https://html.duckduckgo.com/html/
 */
@Singleton
class OsintEngine @Inject constructor(
    private val client: OkHttpClient,
    private val cache: OsintCacheStore,
) {

    /**
     * Researches [rawNumber] and caches the result. Returns the fresh cached value when it is still
     * within [OsintCacheStore.TTL_MS], unless [force] is set.
     */
    suspend fun research(rawNumber: String, force: Boolean = false): NumberOsint =
        withContext(Dispatchers.IO) {
            val normalized = NumberNormalizer.normalize(rawNumber)
            if (normalized == null) {
                return@withContext NumberOsint(
                    number = rawNumber.trim(),
                    summary = NOT_A_NUMBER,
                    sources = emptyList(),
                    updatedAt = System.currentTimeMillis(),
                )
            }
            if (!force) {
                val cached = cache.get(normalized)
                if (cache.isFresh(cached)) return@withContext cached!!.copy(fromCache = true)
            }

            val started = System.currentTimeMillis()
            val sources = try {
                searchAndEnrich(normalized, started)
            } catch (e: Exception) {
                Log.w(TAG, "OSINT pass for $normalized failed", e)
                emptyList()
            }
            val top = OsintBudget.takeSources(sources)
            val summary = if (top.isEmpty()) notFound(normalized) else summarize(normalized, top)
            val record = NumberOsint(
                number = normalized,
                summary = summary,
                sources = top,
                updatedAt = System.currentTimeMillis(),
                fromCache = false,
            )
            cache.put(record)
            record
        }

    private suspend fun searchAndEnrich(number: String, startedAt: Long): List<OsintSource> {
        val query = "$number телефон кто звонил отзывы"
        val searchHtml = fetchText(
            OsintParsing.searchUrl(query),
            OsintBudget.SEARCH_TIMEOUT_MS,
        ) ?: return emptyList()

        val candidates = OsintBudget.takeSources(OsintParsing.parseSearchHtml(searchHtml))
        return candidates.map { source ->
            if (!OsintBudget.canFetchMore(startedAt, System.currentTimeMillis())) {
                return@map source
            }
            if (!isAllowedByRobots(source.url)) return@map source
            val timeout = minOf(
                OsintBudget.FETCH_TIMEOUT_MS,
                OsintBudget.remainingMs(startedAt, System.currentTimeMillis()),
            )
            val page = fetchText(source.url, timeout)
            val extracted = page?.let { OsintParsing.extractSummary(it) }
            if (extracted.isNullOrBlank()) source else source.copy(snippet = extracted)
        }
    }

    /** One bounded GET. Returns `null` on any failure/timeout — never throws to the caller. */
    private suspend fun fetchText(url: String, timeoutMs: Long): String? {
        if (timeoutMs <= 0) return null
        return withTimeoutOrNull(timeoutMs) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", MOBILE_CHROME_UA)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .build()
                val bounded = client.newBuilder()
                    .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .build()
                bounded.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.peekBody(OsintBudget.MAX_FETCH_BYTES.toLong()).string()
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Best-effort `robots.txt` gate: fetches `https://<host>/robots.txt` once per host per process
     * (bounded, cached), and rejects a source URL whose path is disallowed for our UA. A missing or
     * unreadable `robots.txt` allows the URL (standard interpretation).
     */
    private fun isAllowedByRobots(url: String): Boolean = try {
        val uri = URI(url)
        val host = uri.host ?: return true
        val rules = robotsCache.getOrPut(host) { loadRobots(uri.scheme, host) ?: emptyList() }
        val path = uri.path?.ifBlank { "/" } ?: "/"
        !rules.any { it.isNotBlank() && path.startsWith(it) }
    } catch (e: Exception) {
        true
    }

    private fun loadRobots(scheme: String?, host: String): List<String>? = try {
        val base = scheme ?: "https"
        val request = Request.Builder()
            .url("$base://$host/robots.txt")
            .header("User-Agent", MOBILE_CHROME_UA)
            .build()
        client.newBuilder()
            .callTimeout(ROBOTS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) return@use null
                val text = response.peekBody(ROBOTS_MAX_BYTES.toLong()).string()
                parseDisallow(text)
            }
    } catch (e: Exception) {
        null
    }

    /** Extracts `Disallow:` paths for the wildcard user-agent block only. */
    private fun parseDisallow(robots: String): List<String> {
        val out = mutableListOf<String>()
        var inWildcard = false
        robots.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val lower = line.lowercase()
            when {
                lower.startsWith("user-agent:") -> {
                    val agent = line.substringAfter(':').trim()
                    inWildcard = agent == "*"
                }
                inWildcard && lower.startsWith("disallow:") -> {
                    val path = line.substringAfter(':').trim()
                    if (path.isNotEmpty()) out.add(path)
                }
            }
        }
        return out
    }

    private fun summarize(number: String, sources: List<OsintSource>): String = buildString {
        appendLine("Открытый веб-поиск по номеру $number: найдено источников — ${sources.size}.")
        appendLine(
            "Ниже — фрагменты найденных страниц как есть. Это НЕ подтверждение владельца или " +
                "оператора и не гарантия достоверности."
        )
        sources.forEachIndexed { index, source ->
            appendLine("${index + 1}. ${source.title} — ${source.url}")
            if (source.snippet.isNotBlank()) appendLine("   ${source.snippet.take(OsintBudget.MAX_SUMMARY_CHARS)}")
        }
    }.trim()

    private fun notFound(number: String): String =
        "Ничего не найдено по открытым источникам (свободный веб-поиск) для $number. " +
            "Данные не выдуманы."

    companion object {
        private const val TAG = "Andharness"
        private const val ROBOTS_TIMEOUT_MS = 1_500L
        private const val ROBOTS_MAX_BYTES = 32 * 1024

        private const val MOBILE_CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; SM-A075F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        const val NOT_A_NUMBER =
            "Номер не распознан (не телефонный номер). OSINT не выполнялся — данные не выдуманы."

        /** Process-wide `robots.txt` cache: host → disallowed path prefixes (empty = allowed). */
        private val robotsCache = ConcurrentHashMap<String, List<String>>()
    }
}
