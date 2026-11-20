package com.andharness.ru.domain.person

/**
 * Pure control of the paginated SMS backfill loop (update.md §4.4).
 *
 * The backfill reads SMS in bounded pages and persists the last seen provider `_id` as a cursor
 * between runs. A page that came back **full** may have more rows behind it, so the chain continues;
 * a page smaller than the limit — including an empty one — has reached the end of the history, so the
 * chain stops. This lets the indexer walk the **entire** history without a fixed run-count cap while
 * still never looping forever on an exhausted cursor.
 *
 * Kept dependency-free so it can be unit-tested on the JVM.
 */
object SmsBackfillPaging {

    /**
     * @param processedInPass rows consumed by the current pass. A row whose address is blank is
     *   skipped before counting, so a pass may legitimately consume fewer rows than it read.
     * @param pageLimit the fixed per-run bound (the worker uses 2000); a non-positive limit never
     *   continues, so a misconfigured bound can never turn into an unbounded loop.
     * @return `true` when another bounded run should follow.
     */
    fun shouldContinue(processedInPass: Int, pageLimit: Int): Boolean =
        pageLimit > 0 && processedInPass >= pageLimit
}
