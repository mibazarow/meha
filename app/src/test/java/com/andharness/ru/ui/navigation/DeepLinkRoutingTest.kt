package com.andharness.ru.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * JVM tests for the pure intent→target mapping and idempotency key that pin BUG-051's deep-link
 * consumption (SPEC §20). No Android APIs are involved, so the mapping from a notification's chat
 * id to its destination and the "same tap again is a no-op" rule are tested exactly.
 */
class DeepLinkRoutingTest {

    @Test
    fun `a chat id maps to the specific chat route`() {
        assertEquals("chat/42", DeepLinkRouting.route(42L))
        assertEquals("chat/1", DeepLinkRouting.route(1L))
    }

    @Test
    fun `an absent chat maps to the home fallback`() {
        assertEquals(AppDestination.HOME, DeepLinkRouting.route(null))
    }

    @Test
    fun `the idempotency key is stable for an identical chat and message`() {
        assertEquals(
            DeepLinkRouting.key(7L, 99L),
            DeepLinkRouting.key(7L, 99L),
        )
    }

    @Test
    fun `the idempotency key differs for a different chat`() {
        assertNotEquals(
            DeepLinkRouting.key(7L, null),
            DeepLinkRouting.key(8L, null),
        )
    }

    @Test
    fun `the idempotency key differs for a different message`() {
        assertNotEquals(
            DeepLinkRouting.key(7L, 1L),
            DeepLinkRouting.key(7L, 2L),
        )
    }

    @Test
    fun `an absent message is the same as the -1L sentinel`() {
        assertEquals(
            DeepLinkRouting.key(7L, null),
            DeepLinkRouting.key(7L, -1L),
        )
    }
}
