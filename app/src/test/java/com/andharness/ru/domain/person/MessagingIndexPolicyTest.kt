package com.andharness.ru.domain.person

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [MessagingIndexPolicy] (update.md §4.2/§4.4): messenger/mail ingestion is an
 * explicit opt-in restricted to a package allow-list.
 */
class MessagingIndexPolicyTest {

    private val allowed = setOf("org.telegram.messenger", "com.google.android.gm")

    // ---- eligibility: opt-in AND package ----

    @Test
    fun eligibleOnlyWhenOptedInAndListed() {
        assertTrue(
            MessagingIndexPolicy.isEligible(true, "org.telegram.messenger", allowed)
        )
    }

    @Test
    fun disabledOptInRejectsEvenAListedPackage() {
        assertFalse(
            MessagingIndexPolicy.isEligible(false, "org.telegram.messenger", allowed)
        )
    }

    @Test
    fun unlistedPackageIsRejectedWhileOptedIn() {
        assertFalse(MessagingIndexPolicy.isEligible(true, "com.example.chat", allowed))
    }

    @Test
    fun packageMatchIsTrimmedAndCaseInsensitive() {
        assertTrue(MessagingIndexPolicy.isEligible(true, "  org.telegram.messenger ", allowed))
        assertTrue(MessagingIndexPolicy.isEligible(true, "ORG.TELEGRAM.MESSENGER", allowed))
    }

    @Test
    fun blankOrNullPackageIsNeverEligible() {
        assertFalse(MessagingIndexPolicy.isEligible(true, null, allowed))
        assertFalse(MessagingIndexPolicy.isEligible(true, "", allowed))
        assertFalse(MessagingIndexPolicy.isEligible(true, "   ", allowed))
    }

    @Test
    fun emptyAllowListRejectsEverything() {
        assertFalse(MessagingIndexPolicy.isEligible(true, "org.telegram.messenger", emptySet()))
    }

    // ---- channel / identity mapping ----

    @Test
    fun mailPackagesMapToEmailChannelAndOthersToMessenger() {
        assertEquals(InteractionChannel.EMAIL, MessagingIndexPolicy.channelFor("com.google.android.gm"))
        assertEquals(InteractionChannel.EMAIL, MessagingIndexPolicy.channelFor("com.microsoft.outlook"))
        assertEquals(InteractionChannel.MESSENGER, MessagingIndexPolicy.channelFor("com.whatsapp"))
        assertEquals(InteractionChannel.MESSENGER, MessagingIndexPolicy.channelFor(null))
    }

    @Test
    fun anEmailShapedTitleBecomesAnEmailIdentityOtherwiseMessenger() {
        assertEquals(IdentityType.EMAIL, MessagingIndexPolicy.identityTypeFor("a@b.com"))
        assertEquals(IdentityType.MESSENGER, MessagingIndexPolicy.identityTypeFor("Серёга"))
        assertEquals(IdentityType.MESSENGER, MessagingIndexPolicy.identityTypeFor(null))
    }

    // ---- package-list parsing ----

    @Test
    fun packageListParsingTrimsLowercasesAndDeduplicates() {
        assertEquals(
            setOf("org.telegram.messenger", "com.whatsapp"),
            MessagingIndexPolicy.parsePackages(" Org.Telegram.Messenger , com.whatsapp, com.whatsapp "),
        )
    }

    @Test
    fun blankPackageListParsesToEmpty() {
        assertTrue(MessagingIndexPolicy.parsePackages(null).isEmpty())
        assertTrue(MessagingIndexPolicy.parsePackages("  ").isEmpty())
        assertTrue(MessagingIndexPolicy.parsePackages(",, ;").isEmpty())
    }

    // ---- defaults ----

    @Test
    fun defaultsContainPopularMessengersAndMailClients() {
        val defaults = MessagingIndexPolicy.DEFAULT_PACKAGES
        assertTrue(defaults.contains("org.telegram.messenger"))
        assertTrue(defaults.contains("com.whatsapp"))
        assertTrue(defaults.contains("com.google.android.gm"))
        assertTrue(defaults.contains("com.microsoft.outlook"))
        assertTrue(defaults.all { it.contains('.') })
    }

    @Test
    fun defaultsContainCommonSmsApps() {
        val defaults = MessagingIndexPolicy.DEFAULT_PACKAGES
        assertTrue(defaults.contains("com.google.android.apps.messaging"))
        assertTrue(defaults.contains("com.android.messaging"))
        assertTrue(defaults.contains("com.samsung.android.messaging"))
        assertTrue(defaults.contains("com.textra"))
        assertTrue(defaults.contains("org.thoughtcrime.securesms"))
    }

    @Test
    fun smsPackagesMapToTheSmsChannelWhileSignalStaysAMessenger() {
        assertEquals(
            InteractionChannel.SMS,
            MessagingIndexPolicy.channelFor("com.google.android.apps.messaging")
        )
        assertEquals(
            InteractionChannel.SMS,
            MessagingIndexPolicy.channelFor("com.samsung.android.messaging")
        )
        assertEquals(InteractionChannel.SMS, MessagingIndexPolicy.channelFor("com.textra"))
        assertEquals(
            InteractionChannel.MESSENGER,
            MessagingIndexPolicy.channelFor("org.thoughtcrime.securesms")
        )
        assertEquals(
            InteractionChannel.EMAIL,
            MessagingIndexPolicy.channelFor("com.google.android.gm")
        )
    }
}
