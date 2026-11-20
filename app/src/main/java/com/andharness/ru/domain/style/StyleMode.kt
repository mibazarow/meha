package com.andharness.ru.domain.style

import kotlinx.serialization.Serializable

/**
 * How the assistant is allowed to adapt its form of speech.
 *
 * [AUTO] follows the learned profile (the default), [NEUTRAL] keeps the warm neutral baseline and
 * ignores the profile without erasing it, and [MANUAL] uses [StyleProfile.manualCard] verbatim.
 */
@Serializable
enum class StyleMode {
    AUTO,
    NEUTRAL,
    MANUAL,
}
