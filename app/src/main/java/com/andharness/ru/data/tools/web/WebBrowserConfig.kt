package com.andharness.ru.data.tools.web

import java.util.Locale

/**
 * Pure, Android-free browser configuration for the web engine: the single documented mobile Chrome
 * User-Agent, the `Accept-Language` header built from the device locale, and honest bot-stub
 * detection. Free of Android types so every builder is trivially testable on the JVM.
 *
 * The User-Agent itself lives in one place — [WebContentExtractor.MOBILE_CHROME_UA] — and is
 * overridable at runtime via the `web_set_user_agent` tool; this object references it so the fetch
 * path and the WebView path never drift apart.
 */
internal object WebBrowserConfig {

    /**
     * The mobile Chrome User-Agent used by every browser surface (OkHttp fetch, search client,
     * WebView). Kept in [WebContentExtractor] as the single source of truth; referenced here so the
     * fetch handler does not duplicate it. It is a realistic device UA **without** the WebView
     * `; wv` marker, so it reads like a normal mobile Chrome rather than an in-app web view.
     */
    const val MOBILE_CHROME_UA: String = WebContentExtractor.MOBILE_CHROME_UA

    /**
     * Builds an `Accept-Language` header from the device locale(s). The primary locale is emitted
     * first (full `language-COUNTRY` tag at full weight, then the bare `language` tag), followed by
     * an English fallback so most sites still serve full content. Example:
     * `Locale("ru", "RU")` → `"ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"`.
     *
     * References:
     * - `Accept-Language` semantics: https://developer.mozilla.org/docs/Web/HTTP/Headers/Accept-Language
     * - `Locale` / `Locale.Builder`: https://developer.android.com/reference/java/util/Locale
     */
    fun buildAcceptLanguage(vararg locales: Locale?): String {
        val parts = ArrayList<String>()
        for (loc in locales) {
            if (loc == null) continue
            val lang = loc.language.lowercase(Locale.ROOT).replace('_', '-')
            val country = loc.country
            val full = if (country.isNotBlank()) "$lang-$country" else lang
            parts.add(full)
            parts.add("$lang;q=0.9")
        }
        // Universal fallback so sites that only serve English still return full content.
        parts.add("en-US;q=0.8")
        parts.add("en;q=0.7")
        return parts.joinToString(",")
    }

    /** Result of [detectBotStub]: non-null means the response/page is very likely a bot/anti-bot stub. */
    data class BotStub(
        /** Human-readable, honest explanation for the agent (no fabrication of page content). */
        val reason: String,
        /** Which signal fired, for logging/tests (e.g. "http_status_403", "cloudflare", "rate_limited"). */
        val signal: String,
    )

    /**
     * Honestly detects when a site served a bot/anti-bot stub instead of real content, so the agent
     * can report "automated access was blocked" instead of scraping a challenge page. Checks the HTTP
     * status first, then the page title/text for known WAF/captcha/rate-limit markers. Returns `null`
     * for a normal page.
     *
     * This is detection + honest reporting only. It never attempts to solve captchas or bypass WAFs;
     * doing so would violate most sites' ToS and, for captchas, likely anti-circumvention law
     * (DMCA 17 U.S.C. §1201 / equivalent). The agent must surface [BotStub.reason] and stop.
     *
     * References:
     * - Cloudflare "Checking your browser": https://support.cloudflare.com/hc/en-us/articles/200760926
     * - "Attention Required! CLOUDFLARE" (legal notice page): https://support.cloudflare.com/hc/en-us/articles/206024987
     */
    fun detectBotStub(title: String, text: String, httpStatus: Int = 0): BotStub? {
        if (httpStatus == 403) {
            return BotStub("Сайт «403 Доступ запрещён» заблокировал автоматический запрос (возможно, антибот-защита).", "http_status_403")
        }
        if (httpStatus == 429) {
            return BotStub("Сайт «429 Слишком много запросов» ограничил частоту автоматических запросов (rate limit).", "http_status_429")
        }
        if (httpStatus == 503) {
            return BotStub("Сайт «503» временно недоступен и показывает антибот-проверку.", "http_status_503")
        }

        val haystack = buildString {
            append(title.trim())
            append('\n')
            // Cap the text scan: stub markers are usually near the top of the challenge page.
            append(text.trim().take(8_000))
        }.lowercase(Locale.ROOT)

        // Cloudflare Turnstile / Under Attack.
        if (haystack.contains("checking your browser") ||
            haystack.contains("just a moment") ||
            haystack.contains("checking if you are a human")
        ) {
            return BotStub("Страница показывает антибот-проверку Cloudflare («Checking your browser» / «Just a moment»). Доступ для автоматических запросов закрыт.", "cloudflare")
        }
        // Cloudflare legal/notice block.
        if (haystack.contains("attention required") || haystack.contains("attention required! cloudflare")) {
            return BotStub("Страница показывает блок Cloudflare «Attention Required!» (юридическое уведомление, а не содержимое сайта).", "cloudflare_attention")
        }
        // Generic captcha / hCaptcha / reCAPTCHA.
        if (haystack.contains("captcha") ||
            haystack.contains("verify you are human") ||
            haystack.contains("confirm you are not a robot")
        ) {
            return BotStub("Страница просит пройти captcha (проверку «я не робот»). Это обход антибота — автоматически не решается.", "captcha")
        }
        // Generic access denial / rate limiting.
        if (haystack.contains("access denied") ||
            haystack.contains("you are blocked") ||
            haystack.contains("you have been blocked") ||
            haystack.contains("too many requests") ||
            haystack.contains("slow down") ||
            haystack.contains("unusual traffic") ||
            haystack.contains("please slow down")
        ) {
            return BotStub("Сайт заблокировал автоматический запрос («Access denied» / «Too Many Requests» / антибот-сообщение).", "access_denied")
        }
        return null
    }
}
