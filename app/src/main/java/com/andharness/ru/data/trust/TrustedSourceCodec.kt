package com.andharness.ru.data.trust

import kotlinx.serialization.encodeToString
import com.andharness.ru.domain.serialization.AppJson
import com.andharness.ru.domain.serialization.decodeOrNull

/**
 * Pure JSON encode/decode of the trusted-source list. Any corrupt, foreign or missing value
 * degrades to an empty list, so a broken prefs blob can never crash a tool or the prompt builder.
 * Reference: https://kotlinlang.org/api/kotlinx.serialization/
 */
object TrustedSourceCodec {

    private val json = AppJson.Lenient

    fun encode(sources: List<TrustedSource>): String =
        json.encodeToString(TrustedSourceRules.dedupe(sources))

    fun decode(raw: String?): List<TrustedSource> =
        json.decodeOrNull<List<TrustedSource>>(raw)
            ?.let { TrustedSourceRules.dedupe(it) }
            ?: emptyList()
}
