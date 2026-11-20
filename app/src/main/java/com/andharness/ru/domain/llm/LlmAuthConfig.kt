package com.andharness.ru.domain.llm

/**
 * Provider auth configuration (update.md, Приоритет №1): which scheme to use and the names it
 * needs. A blank [headerName] / [queryParam] means "fall back to Bearer" — see
 * `com.andharness.ru.data.llm.LlmAuth` for the exact rules.
 *
 * @param style      auth scheme; [LlmAuthStyle.BEARER] by default so existing setups are unchanged.
 * @param headerName header used when [style] is [LlmAuthStyle.HEADER], e.g. `x-api-key`.
 * @param queryParam query parameter used when [style] is [LlmAuthStyle.QUERY], e.g. `api_key`.
 */
data class LlmAuthConfig(
    val style: LlmAuthStyle = LlmAuthStyle.BEARER,
    val headerName: String = "",
    val queryParam: String = "",
) {
    companion object {
        /** Bearer, no custom names — the behaviour before auth styles existed. */
        val DEFAULT: LlmAuthConfig = LlmAuthConfig()
    }
}
