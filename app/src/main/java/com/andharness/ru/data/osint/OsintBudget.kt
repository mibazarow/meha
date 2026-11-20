package com.andharness.ru.data.osint


/**
 * Hard budget of the free-web OSINT pass (update.md §2.5): **15 s total, at most the first 5
 * sources**. Kept as a pure object so the limits are unit-testable without any network.
 */
object OsintBudget {
    /** Total wall-clock budget for one research pass. */
    const val TIME_BUDGET_MS = 15_000L

    /** Never look at more than the first five candidate sources. */
    const val MAX_SOURCES = 5

    /** Hard byte cap for one fetched page. */
    const val MAX_FETCH_BYTES = 64 * 1024

    /** Timeout for the search-results page request. */
    const val SEARCH_TIMEOUT_MS = 6_000L

    /** Timeout for one source-page request. */
    const val FETCH_TIMEOUT_MS = 4_000L

    /** Minimum remaining budget worth spending on a source fetch. */
    const val MIN_FETCH_BUDGET_MS = 1_500L

    /** At most this many characters of extracted text are kept per source. */
    const val MAX_SUMMARY_CHARS = 600

    /** Caps an arbitrary candidate list to the first [MAX_SOURCES] entries. */
    fun takeSources(all: List<OsintSource>): List<OsintSource> = all.take(MAX_SOURCES)

    /** True when the pass started at [startedAt] has exhausted [TIME_BUDGET_MS] by [now]. */
    fun isExpired(startedAt: Long, now: Long): Boolean = now - startedAt >= TIME_BUDGET_MS

    /** Milliseconds left in the budget (never negative). */
    fun remainingMs(startedAt: Long, now: Long): Long =
        (TIME_BUDGET_MS - (now - startedAt)).coerceAtLeast(0L)

    /** True when there is still enough budget to fetch one more source. */
    fun canFetchMore(startedAt: Long, now: Long): Boolean =
        remainingMs(startedAt, now) >= MIN_FETCH_BUDGET_MS
}
