package com.andharness.ru.domain.messages

/**
 * Canonical string values of [StoredMessage.sourceType]: the kind of source a captured message came
 * from. Kept as plain constants (not an enum) because the value is persisted verbatim and a row
 * written by a newer version must survive a round-trip through the domain unchanged.
 */
object StoredMessageSource {
    const val SMS = "SMS"
    const val MMS = "MMS"
    const val MESSENGER = "MESSENGER"
    const val EMAIL = "EMAIL"
    const val CHAT = "CHAT"
}

/**
 * Canonical string values of [StoredMessage.direction], mirroring
 * [com.andharness.ru.domain.person.InteractionDirection].
 */
object StoredMessageDirection {
    const val INCOMING = "INCOMING"
    const val OUTGOING = "OUTGOING"
    const val UNKNOWN = "UNKNOWN"
}
