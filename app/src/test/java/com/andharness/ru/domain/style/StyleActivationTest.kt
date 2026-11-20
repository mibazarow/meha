package com.andharness.ru.domain.style

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleActivationTest {

    private fun trait(
        key: StyleTraitKey,
        value: Double,
        observations: Int = 10,
        hits: Int = 0,
        days: Int = 2,
    ) = StyleTrait(
        key = key,
        value = value,
        observations = observations,
        hitMessages = hits,
        dayKeys = (0 until days).map { 20_000L + it },
    )

    @Test
    fun addressActivatesForEitherDominantForm() {
        assertTrue(StyleActivation.isActive(trait(StyleTraitKey.ADDRESS, 0.9)))
        assertTrue(StyleActivation.isActive(trait(StyleTraitKey.ADDRESS, 0.1)))
        assertFalse(StyleActivation.isActive(trait(StyleTraitKey.ADDRESS, 0.5)))
    }

    @Test
    fun brevityRequiresTenObservations() {
        assertFalse(StyleActivation.isActive(trait(StyleTraitKey.BREVITY, 0.9, observations = 9)))
        assertTrue(StyleActivation.isActive(trait(StyleTraitKey.BREVITY, 0.9, observations = 10)))
    }

    @Test
    fun profanityRequiresFiveHitMessagesAndFourDays() {
        assertFalse(
            StyleActivation.isActive(
                trait(StyleTraitKey.PROFANITY, 0.2, observations = 30, hits = 4, days = 4)
            )
        )
        assertFalse(
            StyleActivation.isActive(
                trait(StyleTraitKey.PROFANITY, 0.2, observations = 30, hits = 5, days = 3)
            )
        )
        assertTrue(
            StyleActivation.isActive(
                trait(StyleTraitKey.PROFANITY, 0.2, observations = 30, hits = 5, days = 4)
            )
        )
    }

    @Test
    fun humorIsActivatedOnlyByTheCard() {
        assertTrue(StyleActivation.isActive(trait(StyleTraitKey.HUMOR, 0.6, observations = 1, days = 0)))
        assertFalse(StyleActivation.isActive(trait(StyleTraitKey.HUMOR, 0.4, observations = 1, days = 0)))
    }
}
