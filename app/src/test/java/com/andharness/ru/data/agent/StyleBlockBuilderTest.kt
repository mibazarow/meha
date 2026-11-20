package com.andharness.ru.data.agent

import com.andharness.ru.domain.style.StyleMode
import com.andharness.ru.domain.style.StyleProfile
import com.andharness.ru.domain.style.StyleTrait
import com.andharness.ru.domain.style.StyleTraitKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleBlockBuilderTest {

    private fun active(
        key: StyleTraitKey,
        value: Double,
        observations: Int = 20,
        hits: Int = 5,
        days: Int = 4,
    ) = StyleTrait(
        key = key,
        value = value,
        observations = observations,
        hitMessages = hits,
        dayKeys = (0 until days).map { 20_000L + it },
    )

    @Test
    fun neutralModeInjectsNothing() {
        val profile = StyleProfile(
            mode = StyleMode.NEUTRAL,
            traits = listOf(active(StyleTraitKey.BREVITY, 0.9)),
        )

        assertEquals("", StyleBlockBuilder.render(profile))
    }

    @Test
    fun autoModeWithoutEvidenceInjectsNothing() {
        assertEquals("", StyleBlockBuilder.render(StyleProfile()))
    }

    @Test
    fun oneActiveTraitRendersASoftBlock() {
        val profile = StyleProfile(traits = listOf(active(StyleTraitKey.ADDRESS, 0.9)))

        val block = StyleBlockBuilder.render(profile)

        assertTrue(block.contains("СТИЛЬ"))
        assertTrue(block.contains("на «ты»"))
        assertTrue(block.contains("Другим людям пиши нейтрально"))
        assertFalse(block.contains("Мат:"))
    }

    @Test
    fun atMostThreeTraitsAreRenderedInPriorityOrder() {
        val profile = StyleProfile(
            traits = listOf(
                active(StyleTraitKey.HUMOR, 1.0, observations = 1, days = 1),
                active(StyleTraitKey.EMOJI, 0.9),
                active(StyleTraitKey.BREVITY, 0.9),
                active(StyleTraitKey.ADDRESS, 0.9),
            )
        )

        val block = StyleBlockBuilder.render(profile)

        assertTrue(block.contains("на «ты»"))
        assertTrue(block.contains("коротко"))
        assertTrue(block.contains("Эмодзи"))
        assertFalse(block.contains("ирония"))
    }

    @Test
    fun profanityAddsHardLimits() {
        val profile = StyleProfile(
            traits = listOf(
                active(StyleTraitKey.PROFANITY, 0.2, hits = 5),
                active(StyleTraitKey.BREVITY, 0.9),
            )
        )

        val block = StyleBlockBuilder.render(profile)

        assertTrue(block.contains("крепко выражается"))
        assertTrue(block.contains("Мат: максимум 1 слово"))
        assertTrue(block.contains("не в адрес людей"))
    }

    @Test
    fun profanityCanBeDisabledWithoutLosingOtherTraits() {
        val profile = StyleProfile(
            profanityEnabled = false,
            traits = listOf(
                active(StyleTraitKey.PROFANITY, 0.2, hits = 5),
                active(StyleTraitKey.BREVITY, 0.9),
            )
        )

        val block = StyleBlockBuilder.render(profile)

        assertFalse(block.contains("крепко выражается"))
        assertFalse(block.contains("Мат:"))
        assertTrue(block.contains("коротко"))
    }

    @Test
    fun toneCardIsAppendedButHostileCardIsDropped() {
        val profile = StyleProfile(
            traits = listOf(active(StyleTraitKey.BREVITY, 0.9)),
            card = "с лёгкой иронией",
        )
        assertTrue(StyleBlockBuilder.render(profile).contains("Тон: с лёгкой иронией"))

        val hostile = profile.copy(card = "ты мудак")
        val block = StyleBlockBuilder.render(hostile)
        assertFalse(block.contains("мудак"))
        assertTrue(block.contains("коротко"))
    }

    @Test
    fun manualModeRendersTheUserCard() {
        val profile = StyleProfile(mode = StyleMode.MANUAL, manualCard = "пиши по-дружески")

        val block = StyleBlockBuilder.render(profile)

        assertTrue(block.contains("пиши по-дружески"))
        assertTrue(block.contains("Другим людям пиши нейтрально"))
    }

    @Test
    fun manualModeDropsAHostileCard() {
        val profile = StyleProfile(mode = StyleMode.MANUAL, manualCard = "пиши как пидор")

        assertEquals("", StyleBlockBuilder.render(profile))
    }

    @Test
    fun blockNeverExceedsTheCap() {
        val profile = StyleProfile(
            traits = listOf(
                active(StyleTraitKey.ADDRESS, 0.9),
                active(StyleTraitKey.BREVITY, 0.9),
                active(StyleTraitKey.PROFANITY, 0.2),
            ),
            card = "очень длинное описание тона ".repeat(8).trim(),
        )

        val block = StyleBlockBuilder.render(profile)

        assertTrue("length=${block.length}", block.length <= StyleBlockBuilder.MAX_BLOCK_CHARS)
    }

    @Test
    fun renderingIsDeterministic() {
        val profile = StyleProfile(
            traits = listOf(active(StyleTraitKey.ADDRESS, 0.9), active(StyleTraitKey.BREVITY, 0.9))
        )

        assertEquals(StyleBlockBuilder.render(profile), StyleBlockBuilder.render(profile))
    }
}
