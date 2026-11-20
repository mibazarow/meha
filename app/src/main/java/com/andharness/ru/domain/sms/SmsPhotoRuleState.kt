package com.andharness.ru.domain.sms

import kotlinx.serialization.Serializable

/**
 * What is persisted on disk: the [rule] plus the bounded cooldown log ([replyLog] maps a normalized
 * number to the epoch-millis of its last automatic photo reply). Keeping the log in the store keeps
 * [SmsPhotoRule] pure.
 */

@Serializable
data class SmsPhotoRuleState(
    val rule: SmsPhotoRule = SmsPhotoRule(),
    val replyLog: Map<String, Long> = emptyMap(),
)
