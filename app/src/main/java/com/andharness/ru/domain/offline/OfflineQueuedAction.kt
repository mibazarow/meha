package com.andharness.ru.domain.offline

import kotlinx.serialization.Serializable

/**
 * One tool call that failed because the device had no network, kept so it can be retried when
 * connectivity returns (§2 of the product checklist, "Очередь действий offline").
 *
 * It deliberately stores the ORIGINAL [toolName] and [argumentsJson] verbatim, so the retry can
 * re-invoke the tool through the SAME dispatch path the agent used (`ToolRegistry.execute`). Only
 * tools deemed safe to retry are ever stored (see [OfflineQueuePolicy]); irreversible local actions
 * (SMS, calls, deletes, files, contacts, calendar) are never queued.
 *
 * [attempts] counts DRAIN attempts (how many times the background worker tried to re-run this
 * action), starting at 0 when it is enqueued. [lastError] is the honest message of the most recent
 * retry failure, so the UI/log can say exactly why an action is still pending.
 *
 * Serialized by [OfflineQueueCodec]; the whole model is Android-free and thus JVM-testable.
 */
@Serializable
data class OfflineQueuedAction(
    /** Stable identity of this queued call (used by remove/recordFailure). */
    val id: String,
    /** Canonical tool name, e.g. `http_request`. */
    val toolName: String,
    /** The exact JSON arguments string the model produced. Replayed unchanged on retry. */
    val argumentsJson: String,
    /** Epoch millis when the action was queued (ordering key: oldest first). */
    val createdAt: Long,
    /** Number of drain attempts already made (0 = never retried yet). */
    val attempts: Int = 0,
    /** Honest message of the last failure, or null while it has never been retried. */
    val lastError: String? = null,
)
