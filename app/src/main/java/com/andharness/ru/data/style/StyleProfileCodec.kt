package com.andharness.ru.data.style

import com.andharness.ru.domain.serialization.AppJson
import com.andharness.ru.domain.serialization.decodeOrNull
import com.andharness.ru.domain.style.StyleProfile
import kotlinx.serialization.encodeToString

/**
 * JSON encode/decode of [StyleProfile]. Corrupt, foreign or missing data degrades to an empty
 * profile, so a broken file can never crash the chat or the prompt builder.
 *
 * Reference: https://kotlinlang.org/api/kotlinx.serialization/
 */
object StyleProfileCodec {

    private val json = AppJson.Lenient

    fun encode(profile: StyleProfile): String =
        json.encodeToString(StyleProfileRules.sanitize(profile))

    fun decode(raw: String?): StyleProfile =
        json.decodeOrNull<StyleProfile>(raw)?.let { StyleProfileRules.sanitize(it) } ?: StyleProfile()
}
