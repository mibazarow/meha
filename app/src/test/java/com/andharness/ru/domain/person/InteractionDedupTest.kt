package com.andharness.ru.domain.person

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [InteractionDedup] (update.md §4.4): an interaction is only skipped when it
 * carries a stable producer reference that was already indexed.
 */
class InteractionDedupTest {

    @Test
    fun stableReferenceAlreadyIndexedIsADuplicate() {
        assertTrue(InteractionDedup.isDuplicate("sms:42", alreadyIndexed = true))
        assertTrue(InteractionDedup.isDuplicate("call:7", alreadyIndexed = true))
    }

    @Test
    fun stableReferenceNotYetIndexedIsNotADuplicate() {
        assertFalse(InteractionDedup.isDuplicate("sms:42", alreadyIndexed = false))
    }

    @Test
    fun eventWithoutStableReferenceIsNeverDropped() {
        // An SMS_RECEIVED broadcast carries no row id: the same note existing must not suppress it.
        assertFalse(InteractionDedup.isDuplicate(null, alreadyIndexed = true))
        assertFalse(InteractionDedup.isDuplicate("", alreadyIndexed = true))
        assertFalse(InteractionDedup.isDuplicate("   ", alreadyIndexed = true))
    }
}
