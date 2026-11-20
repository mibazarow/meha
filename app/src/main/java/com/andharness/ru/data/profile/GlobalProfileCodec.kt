package com.andharness.ru.data.profile

import kotlinx.serialization.encodeToString
import com.andharness.ru.domain.serialization.AppJson
import com.andharness.ru.domain.serialization.decodeOrNull

/**
 * Pure JSON encode/decode of the profile list. Any corrupt, foreign or missing value degrades to an
 * empty list, so a broken file can never crash a tool or the prompt builder.
 * Reference: https://kotlinlang.org/api/kotlinx.serialization/
 */
object GlobalProfileCodec {

    private val json = AppJson.Lenient

    fun encode(entries: List<ProfileFact>): String =
        json.encodeToString(ProfileRules.sanitize(entries))

    fun decode(raw: String?): List<ProfileFact> =
        json.decodeOrNull<List<ProfileFact>>(raw)?.let { ProfileRules.sanitize(it) } ?: emptyList()
}
