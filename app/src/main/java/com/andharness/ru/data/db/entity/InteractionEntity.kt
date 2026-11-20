package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One interaction with a [PersonEntity] (update.md §4.3, table `interactions`): an SMS, a call, a
 * messenger/email/chat event.
 *
 * Only **metadata + a short [summary]** are persisted — never the body of a message
 * (update.md §4.4/§4.9). [externalRef] is the producer's own stable id (SMS `_id`, call-log id,
 * notification key, ...) so the incremental ingester can avoid inserting the same event twice.
 *
 * [channel] and [direction] hold the names of
 * [com.andharness.ru.domain.person.InteractionChannel] / [com.andharness.ru.domain.person.InteractionDirection].
 */
@Entity(
    tableName = "interactions",
    indices = [
        Index(value = ["personId", "at"]),
        Index("at")
    ]
)
data class InteractionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Owning person ([PersonEntity.id]). */
    val personId: Long,
    /** [com.andharness.ru.domain.person.InteractionChannel] name: `SMS` | `CALL` | `MESSENGER` | `EMAIL` | `CHAT`. */
    val channel: String,
    /** [com.andharness.ru.domain.person.InteractionDirection] name: `INCOMING` | `OUTGOING` | `MISSED` | `UNKNOWN`. */
    val direction: String,
    /** Epoch millis when the interaction happened (not when it was indexed). */
    val at: Long,
    /** Short title, e.g. the conversation name or the call type. */
    val title: String?,
    /** Bounded, non-sensitive summary; the message body is never stored. */
    val summary: String?,
    /** Producer's stable id of the source event (dedup key), when available. */
    val externalRef: String?,
    /** Package of the app the event came from, when relevant (messenger/mail). */
    val sourceApp: String?
)
