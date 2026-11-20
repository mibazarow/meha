package com.andharness.ru.data.llm

import com.andharness.ru.domain.llm.LlmAuthConfig
import com.andharness.ru.domain.llm.LlmAuthStyle
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Applies the configured provider auth scheme to an outgoing request (update.md, Приоритет №1).
 *
 * The single rule that keeps self-hosted servers working: a **blank/absent key never adds any
 * auth**, whatever [LlmAuthConfig.style] says — no header and no query parameter. The same applies
 * to [LlmAuthStyle.QUERY] when there is no key to append.
 *
 * Fallbacks (documented so a misconfigured provider degrades instead of failing):
 * - [LlmAuthStyle.HEADER] with a blank `headerName` behaves as [LlmAuthStyle.BEARER].
 * - [LlmAuthStyle.QUERY] with a blank `queryParam` behaves as [LlmAuthStyle.BEARER].
 *
 * The URL is rewritten only for [LlmAuthStyle.QUERY]; it is never string-concatenated — OkHttp's
 * [okhttp3.HttpUrl.Builder.addQueryParameter] handles existing query strings and percent-encoding.
 *
 * References:
 * - OkHttp `HttpUrl` / `newBuilder` / `addQueryParameter`:
 *   https://square.github.io/okhttp/4/okhttp/okhttp3/HttpUrl/
 * - OkHttp `Request.Builder.header`:
 *   https://square.github.io/okhttp/4/okhttp/okhttp3/Request.Builder/
 */
object LlmAuth {

    /**
     * Returns [url] with the key appended as a query parameter when the effective style is
     * [LlmAuthStyle.QUERY]; otherwise returns [url] unchanged. A blank key never modifies the URL.
     */
    fun urlWithAuth(url: String, key: String?, config: LlmAuthConfig): String {
        if (key.isNullOrBlank() || effectiveStyle(config) != LlmAuthStyle.QUERY) return url
        val httpUrl = url.toHttpUrlOrNull() ?: return url
        return httpUrl.newBuilder()
            .addQueryParameter(config.queryParam.trim(), key)
            .build()
            .toString()
    }

    /**
     * Adds the auth header for the effective [LlmAuthStyle.BEARER] / [LlmAuthStyle.HEADER]; a no-op
     * for [LlmAuthStyle.QUERY], [LlmAuthStyle.NONE] and for a blank key.
     */
    fun applyHeader(builder: Request.Builder, key: String?, config: LlmAuthConfig): Request.Builder {
        if (key.isNullOrBlank()) return builder
        return when (effectiveStyle(config)) {
            LlmAuthStyle.BEARER -> builder.header("Authorization", "Bearer $key")
            LlmAuthStyle.HEADER -> builder.header(config.headerName.trim(), key)
            LlmAuthStyle.QUERY, LlmAuthStyle.NONE -> builder
        }
    }

    /** Resolves the fallback rules: a HEADER/QUERY style with a blank name degrades to BEARER. */
    private fun effectiveStyle(config: LlmAuthConfig): LlmAuthStyle = when (config.style) {
        LlmAuthStyle.HEADER -> if (config.headerName.isBlank()) LlmAuthStyle.BEARER else LlmAuthStyle.HEADER
        LlmAuthStyle.QUERY -> if (config.queryParam.isBlank()) LlmAuthStyle.BEARER else LlmAuthStyle.QUERY
        else -> config.style
    }
}
