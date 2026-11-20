package com.andharness.ru.data.trust

import kotlinx.serialization.Serializable

/**
 * One source whose external text (SMS, e-mail, notification, page, document) the user has allowed
 * to command the agent without an explicit confirmation each time.
 *
 * [type] is one of [TrustedSourceRules.TYPES]; [value] is the normalized identifier (a phone for
 * `sms`, an address for `email`, a package name for `notification`, a domain for `web`).
 */

@Serializable
data class TrustedSource(
    val type: String,
    val value: String,
    val addedAt: Long,
)
