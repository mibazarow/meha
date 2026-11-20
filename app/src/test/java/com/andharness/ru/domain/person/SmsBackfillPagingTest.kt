package com.andharness.ru.domain.person

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [SmsBackfillPaging] (update.md §4.4): the SMS backfill keeps chaining runs while
 * a pass consumed a full page, and stops as soon as a pass consumed less than a page — the honest
 * signal that the provider cursor reached the end of the history.
 */
class SmsBackfillPagingTest {

    private val pageLimit = 2000

    @Test
    fun fullPageContinuesTheChain() {
        assertTrue(SmsBackfillPaging.shouldContinue(processedInPass = 2000, pageLimit = pageLimit))
    }

    @Test
    fun lessThanAFullPageStopsTheChain() {
        assertFalse(SmsBackfillPaging.shouldContinue(processedInPass = 1999, pageLimit = pageLimit))
        assertFalse(SmsBackfillPaging.shouldContinue(processedInPass = 1, pageLimit = pageLimit))
    }

    @Test
    fun aPassThatAddedNothingStopsTheChain() {
        // The empty pass is the terminal condition: cursor reached the end, nothing new to index.
        assertFalse(SmsBackfillPaging.shouldContinue(processedInPass = 0, pageLimit = pageLimit))
    }

    @Test
    fun nonPositivePageLimitNeverContinues() {
        assertFalse(SmsBackfillPaging.shouldContinue(processedInPass = 0, pageLimit = 0))
        assertFalse(SmsBackfillPaging.shouldContinue(processedInPass = 500, pageLimit = -1))
    }
}
