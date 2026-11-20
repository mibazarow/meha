package com.andharness.ru.domain.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleProfileReducerTest {

    private val day = 20_000L
    private val t0 = 1_700_000_000_000L
    private val hour = 60 * 60 * 1000L
    private val week = 7 * 24 * hour

    private fun apply(
        profile: StyleProfile,
        text: String,
        at: Long = t0,
        dayKey: Long = day,
        profanityHits: Int = 0,
    ): StyleProfile = StyleProfileReducer.apply(
        profile = profile,
        signal = StyleAnalyzer.analyze(text, profanityHits),
        now = at,
        day = dayKey,
    )

    @Test
    fun blankSignalIsIgnored() {
        val profile = StyleProfile(samples = 3)
        assertEquals(profile, StyleProfileReducer.apply(profile, StyleSignal.EMPTY, t0, day))
    }

    @Test
    fun firstMessageStartsProfileAndSession() {
        val updated = apply(StyleProfile(), "привет")

        assertEquals(1, updated.samples)
        assertEquals(1, updated.sessions)
        assertEquals(day, updated.firstDay)
        assertEquals(day, updated.lastDay)
        assertEquals(t0, updated.lastMessageAt)
    }

    @Test
    fun sessionStartsOnlyAfterTwoHourGap() {
        var profile = apply(StyleProfile(), "привет", at = t0)
        profile = apply(profile, "как дела", at = t0 + hour)
        assertEquals(1, profile.sessions)

        profile = apply(profile, "снова я", at = t0 + 4 * hour)
        assertEquals(2, profile.sessions)
    }

    @Test
    fun brevityNeedsTenObservationsAndTwoDays() {
        var profile = StyleProfile()
        repeat(10) { profile = apply(profile, "ок") }

        val oneDay = profile.trait(StyleTraitKey.BREVITY)
        assertEquals(10, oneDay?.observations)
        assertEquals(1.0, oneDay?.value ?: 0.0, 0.0001)
        assertFalse(StyleActivation.isActive(oneDay!!))

        profile = apply(profile, "ок", dayKey = day + 1)
        assertTrue(StyleActivation.isActive(profile.trait(StyleTraitKey.BREVITY)!!))
    }

    @Test
    fun informalAddressBecomesActiveWithTy() {
        var profile = StyleProfile()
        repeat(2) { profile = apply(profile, "ты где?", dayKey = day) }
        repeat(2) { profile = apply(profile, "ты где?", dayKey = day + 1) }
        repeat(2) { profile = apply(profile, "ты где?", dayKey = day + 2) }
        repeat(2) { profile = apply(profile, "ты где?", dayKey = day + 3) }

        val address = profile.trait(StyleTraitKey.ADDRESS)!!
        assertEquals(8, address.observations)
        assertTrue(StyleActivation.isActive(address))
        assertEquals(AddressForm.TY, StyleActivation.addressForm(address))
    }

    @Test
    fun formalAddressIsAlsoActiveButRendersAsVy() {
        var profile = StyleProfile()
        repeat(4) { profile = apply(profile, "вы где?", dayKey = day) }
        repeat(4) { profile = apply(profile, "вы где?", dayKey = day + 1) }

        val address = profile.trait(StyleTraitKey.ADDRESS)!!
        assertEquals(0.0, address.value, 0.0001)
        assertTrue(StyleActivation.isActive(address))
        assertEquals(AddressForm.VY, StyleActivation.addressForm(address))
    }

    @Test
    fun profanityNeedsFiveHitsAcrossFourDays() {
        var profile = StyleProfile()
        repeat(20) { index ->
            profile = apply(
                profile,
                "да похуй",
                dayKey = day + index / 5,
                profanityHits = if (index < 5) 1 else 0,
            )
        }

        val profanity = profile.trait(StyleTraitKey.PROFANITY)!!
        assertEquals(20, profanity.observations)
        assertEquals(5, profanity.hitMessages)
        assertEquals(4, profanity.dayKeys.size)
        assertTrue(StyleActivation.isActive(profanity))
    }

    @Test
    fun fiveProfanityHitsInOneDayAreNotEnough() {
        var profile = StyleProfile()
        repeat(20) { index ->
            profile = apply(profile, "да похуй", profanityHits = if (index < 5) 1 else 0)
        }

        val profanity = profile.trait(StyleTraitKey.PROFANITY)!!
        assertEquals(1, profanity.dayKeys.size)
        assertFalse(StyleActivation.isActive(profanity))
    }

    @Test
    fun fourProfanityHitsAreNotEnoughEvenAcrossDays() {
        var profile = StyleProfile()
        repeat(20) { index ->
            profile = apply(
                profile,
                "да похуй",
                dayKey = day + index / 5,
                profanityHits = if (index < 4) 1 else 0,
            )
        }

        val profanity = profile.trait(StyleTraitKey.PROFANITY)!!
        assertEquals(4, profanity.hitMessages)
        assertFalse(StyleActivation.isActive(profanity))
    }

    @Test
    fun decayReducesValueAndEventuallyDropsTheTrait() {
        val trait = StyleTrait(
            key = StyleTraitKey.PROFANITY,
            value = 0.9,
            observations = 20,
            hitMessages = 10,
            dayKeys = listOf(day),
            lastSeenAt = t0,
            lastHitAt = t0,
        )
        val profile = StyleProfile(traits = listOf(trait))

        val twoWeeks = StyleProfileReducer.decay(profile, t0 + 2 * week)
        assertEquals(0.9 * 0.95 * 0.95, twoWeeks.trait(StyleTraitKey.PROFANITY)?.value ?: 0.0, 0.0001)

        val muchLater = StyleProfileReducer.decay(profile, t0 + 40 * week)
        assertNull(muchLater.trait(StyleTraitKey.PROFANITY))
    }

    @Test
    fun addressDecaysFromLastObservationNotLastTyHit() {
        val trait = StyleTrait(
            key = StyleTraitKey.ADDRESS,
            value = 0.0,
            observations = 10,
            hitMessages = 0,
            dayKeys = listOf(day),
            lastSeenAt = t0 + 10 * week,
            lastHitAt = t0,
        )
        val profile = StyleProfile(traits = listOf(trait))

        val decayed = StyleProfileReducer.decay(profile, t0 + 11 * week)

        assertTrue(decayed.trait(StyleTraitKey.ADDRESS) != null)
    }

    @Test
    fun dayKeysAreCappedToTheNewestFourteen() {
        var profile = StyleProfile()
        repeat(20) { offset ->
            profile = apply(profile, "ок", dayKey = day + offset)
        }

        val days = profile.trait(StyleTraitKey.BREVITY)!!.dayKeys
        assertEquals(StyleProfileReducer.MAX_DAY_KEYS, days.size)
        assertEquals(day + 6, days.first())
        assertEquals(day + 19, days.last())
    }

    @Test
    fun activeTraitsAreOrderedAndCapped() {
        val profile = StyleProfile(
            traits = listOf(
                StyleTrait(StyleTraitKey.HUMOR, value = 1.0, observations = 5),
                StyleTrait(
                    StyleTraitKey.BREVITY,
                    value = 0.9,
                    observations = 20,
                    dayKeys = listOf(day, day + 1),
                ),
                StyleTrait(
                    StyleTraitKey.ADDRESS,
                    value = 0.9,
                    observations = 20,
                    dayKeys = listOf(day, day + 1),
                ),
            )
        )

        val active = StyleActivation.activeTraits(profile, limit = 2)

        assertEquals(listOf(StyleTraitKey.ADDRESS, StyleTraitKey.BREVITY), active.map { it.key })
    }
}
