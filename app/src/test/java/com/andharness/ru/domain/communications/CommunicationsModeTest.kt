package com.andharness.ru.domain.communications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [CommunicationsMode]: the persisted tokens, the Russian labels and the lenient
 * parsing that must never leave the feature undefined.
 */
class CommunicationsModeTest {

    @Test
    fun hasExactlyOffChargingAlways() {
        assertEquals(3, CommunicationsMode.entries.size)
        assertEquals(
            listOf(CommunicationsMode.OFF, CommunicationsMode.CHARGING, CommunicationsMode.ALWAYS),
            CommunicationsMode.entries.toList(),
        )
    }

    @Test
    fun idsAreUniqueAndNonBlank() {
        val ids = CommunicationsMode.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.isNotBlank() })
    }

    @Test
    fun everyModeHasRussianLabel() {
        CommunicationsMode.entries.forEach { mode ->
            assertTrue(mode.label, mode.label.isNotBlank())
            assertTrue(mode.label, mode.label.any { it in 'а'..'я' || it in 'А'..'Я' })
        }
    }

    @Test
    fun defaultIsCharging() {
        assertEquals(CommunicationsMode.CHARGING, CommunicationsMode.DEFAULT)
    }

    @Test
    fun fromIdRoundTripsEveryMode() {
        CommunicationsMode.entries.forEach { mode ->
            assertEquals(mode, CommunicationsMode.fromId(mode.id))
        }
    }

    @Test
    fun fromIdFallsBackToDefaultForUnknownToken() {
        assertEquals(CommunicationsMode.DEFAULT, CommunicationsMode.fromId("does_not_exist"))
    }

    @Test
    fun fromIdFallsBackToDefaultForNull() {
        assertEquals(CommunicationsMode.DEFAULT, CommunicationsMode.fromId(null))
    }
}
