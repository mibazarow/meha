package com.andharness.ru.domain.style

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleProfileTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun defaultsAreValidAndEmpty() {
        val profile = StyleProfile()

        assertEquals(1, profile.version)
        assertEquals(0, profile.samples)
        assertEquals(StyleMode.AUTO, profile.mode)
        assertTrue(profile.traits.isEmpty())
        assertNull(profile.firstDay)
        assertNull(profile.card)
        assertNull(profile.trait(StyleTraitKey.ADDRESS))
    }

    @Test
    fun withTraitAppendsNewAndReplacesExistingInPlace() {
        val brevity = StyleTrait(key = StyleTraitKey.BREVITY, value = 0.8)
        val address = StyleTrait(key = StyleTraitKey.ADDRESS, value = 0.9)

        val first = StyleProfile().withTrait(brevity).withTrait(address)
        assertEquals(listOf(StyleTraitKey.BREVITY, StyleTraitKey.ADDRESS), first.traits.map { it.key })

        val replaced = first.withTrait(brevity.copy(value = 0.4))
        assertEquals(listOf(StyleTraitKey.BREVITY, StyleTraitKey.ADDRESS), replaced.traits.map { it.key })
        assertEquals(0.4, replaced.trait(StyleTraitKey.BREVITY)?.value ?: 0.0, 0.0001)
    }

    @Test
    fun jsonRoundTripPreservesEverything() {
        val profile = StyleProfile(
            samples = 42,
            sessions = 7,
            firstDay = 20_000L,
            lastDay = 20_010L,
            lastMessageAt = 1_700_000_000_000L,
            mode = StyleMode.MANUAL,
            traits = listOf(
                StyleTrait(
                    key = StyleTraitKey.PROFANITY,
                    value = 0.12,
                    observations = 80,
                    hitMessages = 10,
                    dayKeys = listOf(20_001L, 20_002L),
                    lastSeenAt = 1_700_000_000_000L,
                ),
            ),
            card = "коротко, на ты",
            cardUpdatedAt = 1_700_000_000_000L,
            manualCard = "пиши по-дружески",
        )

        val decoded = json.decodeFromString<StyleProfile>(json.encodeToString(profile))

        assertEquals(profile, decoded)
    }

    @Test
    fun modeRoundTrips() {
        StyleMode.entries.forEach { mode ->
            val decoded = json.decodeFromString<StyleMode>(json.encodeToString(mode))
            assertEquals(mode, decoded)
        }
    }
}
