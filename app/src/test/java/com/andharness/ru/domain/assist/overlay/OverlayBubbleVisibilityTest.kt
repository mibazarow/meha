package com.andharness.ru.domain.assist.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the bubble re-attach decision (BUG-037 resilience): the bubble is only ever
 * (re-)added when it is actually missing, so a healthy bubble is left untouched.
 */
class OverlayBubbleVisibilityTest {

    @Test
    fun addsTheBubbleWhenItWasNeverAdded() {
        assertTrue(OverlayBubbleVisibility.shouldAdd(bubbleAdded = false))
        assertFalse(OverlayBubbleVisibility.shouldAdd(bubbleAdded = true))
    }

    @Test
    fun reattachesOnlyWhenMarkedAddedButDetached() {
        assertTrue(OverlayBubbleVisibility.shouldReattach(bubbleAdded = true, viewAttached = false))
        assertFalse(OverlayBubbleVisibility.shouldReattach(bubbleAdded = true, viewAttached = true))
        assertFalse(OverlayBubbleVisibility.shouldReattach(bubbleAdded = false, viewAttached = false))
        assertFalse(OverlayBubbleVisibility.shouldReattach(bubbleAdded = false, viewAttached = true))
    }
}
