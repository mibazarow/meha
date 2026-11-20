package com.andharness.ru.data.tools.contacts

/**
 * Android-free decision logic for `contact_merge` / `contact_split`: it picks one raw contact
 * per aggregate contact and rejects requests that cannot be applied.
 *
 * `ContactsContract.AggregationExceptions` operates on RAW contact ids
 * (`RAW_CONTACT_ID1` / `RAW_CONTACT_ID2`), never on aggregate contact ids, so the ids returned
 * by `contact_search` must always be resolved to raw contact ids first.
 *
 * Covered by the JVM test `ContactAggregationTest`.
 */
object ContactAggregation {

    /** Outcome of resolving a requested aggregation between two aggregate contacts. */
    sealed interface Result {
        /** Both raw contacts were found and differ; the rule can be written. */
        data class Resolved(val rawContactId1: Long, val rawContactId2: Long) : Result

        /** The request cannot be applied; [message] is a user-facing, actionable reason. */
        data class Invalid(val message: String) : Result
    }

    /**
     * Resolves the raw-contact pair to write for the aggregation of [contactId1]/[contactId2].
     *
     * A contact can aggregate several raw contacts; the rule is written between one raw contact
     * of each side (the first one, mirroring the previous behaviour).
     */
    fun resolveRawContactPair(
        contactId1: String,
        contactId2: String,
        rawContactIds1: List<Long>,
        rawContactIds2: List<Long>,
    ): Result {
        val raw1 = rawContactIds1.firstOrNull()
            ?: return Result.Invalid("Контакт $contactId1 не найден (нет raw-контакта).")
        val raw2 = rawContactIds2.firstOrNull()
            ?: return Result.Invalid("Контакт $contactId2 не найден (нет raw-контакта).")
        if (raw1 == raw2) {
            return Result.Invalid("Оба contact_id указывают на один raw-контакт — изменять нечего.")
        }
        return Result.Resolved(raw1, raw2)
    }
}
