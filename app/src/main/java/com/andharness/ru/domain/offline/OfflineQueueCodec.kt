package com.andharness.ru.domain.offline

import kotlinx.serialization.encodeToString
import com.andharness.ru.domain.serialization.AppJson
import com.andharness.ru.domain.serialization.decodeOrNull

/**
 * Pure encode/decode for the offline action queue. Depends only on kotlinx.serialization, so the
 * whole persistence contract is exercised on the JVM (see `OfflineActionQueueTest`); the
 * Android-bound store [com.andharness.ru.data.offline.OfflineActionQueue] only adds file I/O.
 *
 * Decoding is deliberately forgiving (same policy as `SkillCodec`): a corrupt/foreign/missing blob
 * degrades to an EMPTY list instead of throwing, records without an id/toolName are dropped, and an
 * oversized/hand-edited file is clamped to [MAX_ACTIONS] (oldest dropped) so it can never break the
 * worker.
 *
 * kotlinx.serialization Json reference: https://kotlinlang.org/api/kotlinx.serialization/
 */
object OfflineQueueCodec {

    /**
     * Hard cap on queued actions (the task's "~50"). When a new action is enqueued at the cap the
     * OLDEST one is dropped, so the file/context can never grow unchecked.
     */
    const val MAX_ACTIONS = 50

    private val json = AppJson.LenientEncodeDefaults

    fun encode(actions: List<OfflineQueuedAction>): String = json.encodeToString(actions)

    /** Never throws: a bad blob yields an empty list. Bounded to [MAX_ACTIONS], oldest dropped. */
    fun decode(raw: String?): List<OfflineQueuedAction> =
        json.decodeOrNull<List<OfflineQueuedAction>>(raw)
            ?.filter { it.id.isNotBlank() && it.toolName.isNotBlank() }
            ?.map { it.copy(attempts = it.attempts.coerceAtLeast(0)) }
            ?.takeLast(MAX_ACTIONS)
            ?: emptyList()
}
