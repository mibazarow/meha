package com.andharness.ru.data.tools.web

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.Reader
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The `web_fetch` tool: fetch a URL over HTTP and return its readable text.
 *
 * Deliberately **does not use `WebView`** (that was BUG-007). A `WebView` must be created and driven
 * on the main thread, and the previous implementation did exactly that (`withContext(Dispatchers.Main.immediate)`)
 * and then waited up to 25 s — which blocked the UI / blew the debug broadcast's ~10 s `goAsync()`
 * window and produced an ANR. This version performs a plain, bounded OkHttp GET on
 * [Dispatchers.IO], never touches the main thread, and always returns within [timeoutSeconds].
 *
 * Trade-off (honest): with no JS engine we only see the **served HTML**, so pages whose content is
 * rendered purely by JavaScript may come back empty — for those use `web_open` + `web_readable`
 * (the persistent `WebEngine` WebView session). Hard anti-bot WAFs (Cloudflare) are still not passed.
 *
 * Official references:
 * - OkHttp timeouts (`callTimeout` / `connectTimeout` / `readTimeout`):
 *   https://square.github.io/okhttp/recipes/#timeouts-kt-java
 * - `OkHttpClient.Builder#callTimeout(long, TimeUnit)`:
 *   https://square.github.io/okhttp/4/okhttp/okhttp3/OkHttpClient.Builder/#callTimeout(long,java.util.concurrent.TimeUnit)
 * - Blocking network I/O belongs on `Dispatchers.IO`:
 *   https://developer.android.com/kotlin/coroutines/coroutines-best-practices#inject-dispatchers
 * - `WebView` must be constructed/used on a thread with a `Looper` (the reason it is avoided here):
 *   https://developer.android.com/reference/android/webkit/WebView
 */
class WebFetchToolsHandler(
    private val okHttpClient: OkHttpClient?,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SEC,
    /**
     * `Accept-Language` header value. Defaults to the device locale (e.g. `ru-RU,ru;q=0.9,en-US;q=0.8,
     * en;q=0.7`) so the fetch path looks like a normal mobile browser; overridable for tests.
     */
    private val acceptLanguage: () -> String = { WebBrowserConfig.buildAcceptLanguage(Locale.getDefault()) },
) {

    fun all(): List<ToolHandler> = listOf(webFetch)

    private val webFetch = object : ToolHandler {
        override val name = "web_fetch"
        override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
            val url = (arguments["url"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (url.isBlank()) return@withContext ToolResult(false, "Укажите url для загрузки.")
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return@withContext ToolResult(false, "Поддерживаются только http/https ссылки.")
            }
            val client = okHttpClient
                ?: return@withContext ToolResult(
                    false,
                    "Сетевой клиент недоступен — загрузка «$url» невозможна.",
                )

            // Bound the WHOLE call (connect + redirects + body read), not just one socket read, so
            // this tool can never outlive the caller's budget (agent turn / DEBUG_TOOL broadcast).
            val bounded = client.newBuilder()
                .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", WebBrowserConfig.MOBILE_CHROME_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", acceptLanguage())
                .build()

            try {
                // Outer hard bound as a second line of defence: even a misbehaving interceptor or a
                // stuck DNS resolver cannot make this call outlive the budget.
                withTimeoutOrNull((timeoutSeconds + 2) * 1000L) {
                    bounded.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            // 403/429/503 from an anti-bot WAF is reported honestly (automated
                            // access blocked), not as a generic failure:
                            // https://developer.android.com/reference/java/net/HttpURLConnection
                            WebBrowserConfig.detectBotStub("", "", response.code)?.let {
                                return@withTimeoutOrNull ToolResult(false, it.reason)
                            }
                            return@withTimeoutOrNull ToolResult(
                                false,
                                "Сервер вернул HTTP ${response.code} для «$url».",
                            )
                        }
                        val body = response.body
                            ?: return@withTimeoutOrNull ToolResult(false, "Пустой ответ сервера для «$url».")
                        val html = body.charStream().use { readBounded(it, MAX_HTML_CHARS) }
                        val text = htmlToText(html)
                        // A 200 page may still be a WAF/captcha challenge; detect it honestly.
                        WebBrowserConfig.detectBotStub("", text.orEmpty(), 0)?.let {
                            return@withTimeoutOrNull ToolResult(false, it.reason)
                        }
                        if (text.isNullOrBlank()) {
                            return@withTimeoutOrNull ToolResult(
                                false,
                                "Не удалось получить читаемое содержимое «$url» (страница пуста, не HTML или " +
                                    "рендерится только JavaScript — для таких страниц используйте " +
                                    "web_open + web_readable).",
                            )
                        }
                        ToolResult(true, text.take(MAX_TEXT_CHARS))
                    }
                } ?: ToolResult(
                    false,
                    "Таймаут ${timeoutSeconds}с: не удалось загрузить «$url» (сайт не ответил вовремя).",
                )
            } catch (e: IOException) {
                ToolResult(false, "Ошибка загрузки «$url»: ${e.message ?: e::class.java.simpleName}")
            } catch (e: Exception) {
                ToolResult(false, "Ошибка: ${e.message}")
            }
        }
    }

    private companion object {
        /** Total budget of one `web_fetch`; fits inside the debug broadcast's ~10 s window. */
        const val DEFAULT_TIMEOUT_SEC = 8L
        const val MAX_TEXT_CHARS = 20_000
        const val MAX_HTML_CHARS = 512 * 1024
    }
}

/** Reads at most [maxChars] characters from [reader]; the rest of the body is intentionally dropped. */
private fun readBounded(reader: Reader, maxChars: Int): String {
    val out = StringBuilder(minOf(maxChars, 64 * 1024))
    val buffer = CharArray(8 * 1024)
    var remaining = maxChars
    while (remaining > 0) {
        val read = reader.read(buffer, 0, minOf(buffer.size, remaining))
        if (read < 0) break
        out.append(buffer, 0, read)
        remaining -= read
    }
    return out.toString()
}

/**
 * Minimal, dependency-free HTML → readable text: drops `script`/`style`/`noscript`/`svg`/`template`,
 * keeps `title`, turns block tags into line breaks, strips the remaining tags, decodes common HTML
 * entities and collapses whitespace. Returns `null` when nothing readable is left.
 *
 * Pure Kotlin (no Android APIs) so it is covered by a plain JVM unit test.
 */
internal fun htmlToText(html: String): String? {
    if (html.isBlank()) return null
    val withoutHidden = HIDDEN_PATTERN.replace(html, " ")
    val title = TITLE_PATTERN.find(withoutHidden)
        ?.groupValues?.getOrNull(1)
        ?.let { decodeEntities(it) }
        ?.trim()
        .orEmpty()
    val broken = BLOCK_PATTERN.replace(withoutHidden, "\n")
    val text = decodeEntities(TAG_PATTERN.replace(broken, " "))
        .replace(INLINE_WS_PATTERN, " ")
        .replace(BLANK_LINES_PATTERN, "\n\n")
        .trim()
    return when {
        title.isBlank() && text.isBlank() -> null
        title.isBlank() -> text
        text.isBlank() -> title
        else -> "$title\n\n$text"
    }
}

private val HIDDEN_PATTERN = Regex(
    "<(script|style|noscript|svg|template)\\b[^>]*>.*?</\\1\\s*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private val TITLE_PATTERN = Regex(
    "<title\\b[^>]*>(.*?)</title\\s*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private val BLOCK_PATTERN = Regex(
    "</?(p|div|br|li|ul|ol|tr|h[1-6]|section|article|header|footer|table|blockquote)\\b[^>]*>",
    RegexOption.IGNORE_CASE,
)

private val TAG_PATTERN = Regex("<[^>]*>")

private val INLINE_WS_PATTERN = Regex("[ \\t\\u00A0\\u000B\\u000C\\r]+")

private val BLANK_LINES_PATTERN = Regex("\\n{3,}")

private val ENTITY_PATTERN = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")

/** Decodes the handful of HTML entities that matter for plain text (named + numeric). */
private fun decodeEntities(value: String): String {
    if (!value.contains('&')) return value
    return ENTITY_PATTERN.replace(value) { match ->
        when (val entity = match.groupValues[1]) {
            "nbsp" -> " "
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "mdash" -> "—"
            "ndash" -> "–"
            "laquo" -> "«"
            "raquo" -> "»"
            else -> when {
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.drop(2).toIntOrNull(16)?.let(::codePointToString) ?: match.value
                entity.startsWith("#") ->
                    entity.drop(1).toIntOrNull()?.let(::codePointToString) ?: match.value
                else -> match.value
            }
        }
    }
}

private fun codePointToString(code: Int): String? =
    if (code in 1..0x10FFFF && code !in 0xD800..0xDFFF) String(Character.toChars(code)) else null
