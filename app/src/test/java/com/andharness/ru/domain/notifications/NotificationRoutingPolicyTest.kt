package com.andharness.ru.domain.notifications

import com.andharness.ru.domain.person.IdentityNormalizer
import com.andharness.ru.domain.person.IdentityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM characterisation tests for [NotificationRoutingPolicy]: the routing decisions extracted
 * verbatim from `service/NotificationRelayService` (blank-title gate, filing identity, dedup time
 * basis, sender fallback). Expected values mirror the pre-extraction service behaviour.
 */
class NotificationRoutingPolicyTest {

    private val pkg = "org.telegram.messenger"

    // ---- blank-title gate ----

    @Test
    fun emptyTitleIsNotIndexable() {
        assertFalse(NotificationRoutingPolicy.hasIndexableTitle(""))
    }

    @Test
    fun whitespaceTitleIsNotIndexable() {
        assertFalse(NotificationRoutingPolicy.hasIndexableTitle("   \t\n "))
    }

    @Test
    fun nonBlankTitleIsIndexable() {
        assertTrue(NotificationRoutingPolicy.hasIndexableTitle("New message"))
    }

    // ---- filing identity: conversation key wins over title ----

    @Test
    fun missingConversationKeyFallsBackToTitle() {
        val identity = NotificationRoutingPolicy.identityFor(
            conversationKey = null,
            title = "Иван Петров",
            packageName = pkg,
        )
        assertEquals("Иван Петров", identity.value)
    }

    @Test
    fun conversationKeyWinsOverTitle() {
        val identity = NotificationRoutingPolicy.identityFor(
            conversationKey = "Work chat",
            title = "Иван Петров",
            packageName = pkg,
        )
        assertEquals("Work chat", identity.value)
    }

    // ---- filing identity: messenger scoped to app, e-mail is global ----

    @Test
    fun messengerHandleIsScopedToPostingPackage() {
        val identity = NotificationRoutingPolicy.identityFor(
            conversationKey = null,
            title = "@durov",
            packageName = pkg,
        )
        assertEquals(IdentityType.MESSENGER, identity.type)
        assertEquals(pkg, identity.appPackage)
    }

    @Test
    fun emailTitleBecomesGlobalEmailIdentity() {
        val identity = NotificationRoutingPolicy.identityFor(
            conversationKey = null,
            title = "User@Example.COM",
            packageName = pkg,
        )
        assertEquals(IdentityType.EMAIL, identity.type)
        assertEquals(IdentityNormalizer.NO_PACKAGE, identity.appPackage)
        assertEquals("User@Example.COM", identity.value)
    }

    @Test
    fun emailConversationKeyBeatsTitleAndDropsPackage() {
        val identity = NotificationRoutingPolicy.identityFor(
            conversationKey = "boss@company.ru",
            title = "New mail",
            packageName = "com.google.android.gm",
        )
        assertEquals(IdentityType.EMAIL, identity.type)
        assertEquals("boss@company.ru", identity.value)
        assertEquals(IdentityNormalizer.NO_PACKAGE, identity.appPackage)
    }

    // ---- dedup time basis ----

    @Test
    fun realTimestampsUseTheMessageTime() {
        assertEquals(1234567890L, NotificationRoutingPolicy.dedupAt(hasTimestamps = true, messageAt = 1234567890L))
    }

    @Test
    fun timestamplessSourceUsesFixedZeroBasis() {
        assertEquals(0L, NotificationRoutingPolicy.dedupAt(hasTimestamps = false, messageAt = 9876543210L))
    }

    // ---- sender fallback ----

    @Test
    fun messageSenderWins() {
        assertEquals("Alice", NotificationRoutingPolicy.senderFor("Alice", "Work chat"))
    }

    @Test
    fun missingSenderFallsBackToConversationKey() {
        assertEquals("Work chat", NotificationRoutingPolicy.senderFor(null, "Work chat"))
    }

    @Test
    fun noSenderAndNoConversationKeyYieldsNull() {
        assertNull(NotificationRoutingPolicy.senderFor(null, null))
    }
}
