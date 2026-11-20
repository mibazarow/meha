package com.andharness.ru.data.autofill

import kotlinx.serialization.encodeToString
import com.andharness.ru.domain.serialization.AppJson
import com.andharness.ru.domain.serialization.decodeOrNull

/**
 * Pure encode/decode of [AutofillProfile], so it is exercised on the JVM by
 * `AutofillProfileCodecTest` (the Android-bound [AutofillProfileStore] is not).
 *
 * Any corrupt, foreign or missing value degrades to an empty profile, exactly like
 * `GeofenceStore`/`UiMacroCodec`, so a broken store can never make the service crash the system
 * process. Reference: https://kotlinlang.org/api/kotlinx.serialization/
 */
object AutofillProfileCodec {

    private val json = AppJson.Lenient

    fun encode(profile: AutofillProfile): String = json.encodeToString(profile)

    fun decode(raw: String?): AutofillProfile =
        json.decodeOrNull<AutofillProfile>(raw) ?: AutofillProfile()
}
