package com.andharness.ru.domain.person

/**
 * Pure dedup rule for incremental ingestion (update.md §4.4).
 *
 * An interaction is only a duplicate when it carries the producer's own stable reference
 * ([Interaction.externalRef] — an SMS/call-log row id, a notification key, ...) **and** that
 * reference was already indexed for the same person. Events without a stable reference (e.g. an
 * `SMS_RECEIVED` broadcast, which exposes no row id) are always inserted, because there is no honest
 * way to tell them apart — silently dropping them would lose real events.
 *
 * Kept dependency-free so it can be unit-tested on the JVM and reused by the indexer.
 */
object InteractionDedup {

    /**
     * @param externalRef the producer's stable id, may be blank/`null`.
     * @param alreadyIndexed whether a row with the same reference already exists for the person.
     */
    fun isDuplicate(externalRef: String?, alreadyIndexed: Boolean): Boolean =
        !externalRef.isNullOrBlank() && alreadyIndexed
}
