package com.andharness.ru.domain.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for channel selection (update.md §4.7): AUTO prefers SMS when a phone exists,
 * an explicit channel is honoured only when the matching handle exists, and nothing is ever sent
 * through a channel the person cannot be reached on.
 */
class MessageChannelSelectorTest {

    private fun chosen(request: MessageChannelRequest, hasPhone: Boolean, hasMessenger: Boolean):
        MessageChannel? = when (val decision = MessageChannelSelector.select(request, hasPhone, hasMessenger)) {
        is ChannelDecision.Chosen -> decision.channel
        is ChannelDecision.Unavailable -> null
    }

    @Test
    fun autoPrefersSmsWhenPhoneExists() {
        assertEquals(MessageChannel.SMS, chosen(MessageChannelRequest.AUTO, true, true))
        assertEquals(MessageChannel.SMS, chosen(MessageChannelRequest.AUTO, true, false))
    }

    @Test
    fun autoFallsBackToMessengerWithoutPhone() {
        assertEquals(MessageChannel.MESSENGER, chosen(MessageChannelRequest.AUTO, false, true))
    }

    @Test
    fun autoIsUnavailableWithNoHandles() {
        val decision = MessageChannelSelector.select(MessageChannelRequest.AUTO, false, false)
        assertTrue(decision is ChannelDecision.Unavailable)
    }

    @Test
    fun explicitSmsNeedsAPhone() {
        assertEquals(MessageChannel.SMS, chosen(MessageChannelRequest.SMS, true, true))
        val decision = MessageChannelSelector.select(MessageChannelRequest.SMS, false, true)
        assertTrue("an explicit SMS without a phone must not silently fall back", decision is ChannelDecision.Unavailable)
    }

    @Test
    fun explicitMessengerNeedsAMessengerHandle() {
        assertEquals(MessageChannel.MESSENGER, chosen(MessageChannelRequest.MESSENGER, true, true))
        val decision = MessageChannelSelector.select(MessageChannelRequest.MESSENGER, true, false)
        assertTrue("an explicit messenger without a handle must not silently fall back", decision is ChannelDecision.Unavailable)
    }

    @Test
    fun requestParsingDefaultsToAutoAndRejectsUnknown() {
        assertEquals(MessageChannelRequest.AUTO, MessageChannelRequest.from(null))
        assertEquals(MessageChannelRequest.AUTO, MessageChannelRequest.from("  "))
        assertEquals(MessageChannelRequest.AUTO, MessageChannelRequest.from("auto"))
        assertEquals(MessageChannelRequest.SMS, MessageChannelRequest.from("sms"))
        assertEquals(MessageChannelRequest.MESSENGER, MessageChannelRequest.from("Messenger"))
        assertEquals(null, MessageChannelRequest.from("telegram"))
    }

    @Test
    fun channelParsingIsCaseInsensitive() {
        assertEquals(MessageChannel.SMS, MessageChannel.from("sms"))
        assertEquals(MessageChannel.MESSENGER, MessageChannel.from("Messenger"))
        assertEquals(null, MessageChannel.from("signal"))
    }
}
