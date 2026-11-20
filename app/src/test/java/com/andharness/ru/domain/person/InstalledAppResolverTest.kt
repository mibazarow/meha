package com.andharness.ru.domain.person

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the pure [InstalledAppResolver] matching order and case-insensitivity. */
class InstalledAppResolverTest {

    private val telegram = InstalledApp("org.telegram.messenger", "Telegram")
    private val telegramX = InstalledApp("org.thunderdog.challegram", "Telegram X")
    private val max = InstalledApp("ru.oneme.app", "MAX")
    private val whatsapp = InstalledApp("com.whatsapp", "WhatsApp")

    private val apps = listOf(telegram, telegramX, max, whatsapp)

    @Test
    fun exactPackageWinsOverEverything() {
        assertEquals(listOf(max), InstalledAppResolver.resolve("ru.oneme.app", apps))
    }

    @Test
    fun exactPackageIsCaseInsensitive() {
        assertEquals(listOf(telegram), InstalledAppResolver.resolve("ORG.Telegram.MESSENGER", apps))
    }

    @Test
    fun exactLabelMatches() {
        assertEquals(listOf(whatsapp), InstalledAppResolver.resolve("WhatsApp", apps))
    }

    @Test
    fun exactLabelIsCaseInsensitive() {
        assertEquals(listOf(max), InstalledAppResolver.resolve("max", apps))
    }

    @Test
    fun exactLabelBeatsContainedLabel() {
        assertEquals(listOf(telegram), InstalledAppResolver.resolve("Telegram", apps))
    }

    @Test
    fun labelContainsMatchesWhenNoExact() {
        assertEquals(listOf(telegram, telegramX), InstalledAppResolver.resolve("Teleg", apps))
    }

    @Test
    fun labelContainsIsCaseInsensitive() {
        assertEquals(listOf(telegram, telegramX), InstalledAppResolver.resolve("TELEGRA", apps))
    }

    @Test
    fun ambiguousQueryReturnsEveryCandidateInInputOrder() {
        assertEquals(listOf(telegram, telegramX, whatsapp), InstalledAppResolver.resolve("T", apps))
    }

    @Test
    fun unknownQueryReturnsEmpty() {
        assertTrue(InstalledAppResolver.resolve("нет такого", apps).isEmpty())
    }

    @Test
    fun blankOrWhitespaceQueryReturnsEmpty() {
        assertTrue(InstalledAppResolver.resolve("", apps).isEmpty())
        assertTrue(InstalledAppResolver.resolve("   ", apps).isEmpty())
    }

    @Test
    fun queryIsTrimmedBeforeMatching() {
        assertEquals(listOf(max), InstalledAppResolver.resolve("  MAX  ", apps))
    }

    @Test
    fun duplicatesByPackageAreCollapsedToTheFirstEntry() {
        val duplicated = apps + InstalledApp("ru.oneme.app", "MAX Messenger")
        assertEquals(listOf(max), InstalledAppResolver.resolve("ru.oneme.app", duplicated))
    }
}
