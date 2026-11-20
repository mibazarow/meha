package com.andharness.ru.data.profile


/** Result of a [GlobalProfileStore.put] — [fact] is the stored/duplicate fact (null when invalid). */
data class ProfileWriteResult(
    val fact: ProfileFact?,
    val outcome: ProfileWriteOutcome,
)
