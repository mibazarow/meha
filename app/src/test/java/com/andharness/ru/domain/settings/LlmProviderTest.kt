package com.andharness.ru.domain.settings

import com.andharness.ru.domain.llm.LlmAuthStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM checks of the saved-provider profile value object. */
class LlmProviderTest {

    @Test
    fun draftIsNotSavedAndHasZeroId() {
        val draft = LlmProvider()
        assertEquals(LlmProvider.UNSAVED_ID, draft.id)
        assertFalse(draft.isSaved)
    }

    @Test
    fun persistedProfileIsSaved() {
        assertTrue(LlmProvider(id = 7L, endpoint = "https://api.example/v1").isSaved)
    }

    @Test
    fun displayNamePrefersTheExplicitName() {
        val provider = LlmProvider(name = "  DeepSeek ", endpoint = "https://api.deepseek.com")
        assertEquals("DeepSeek", provider.displayName)
    }

    @Test
    fun displayNameFallsBackToTheEndpoint() {
        val provider = LlmProvider(name = "   ", endpoint = "  https://api.example/v1 ")
        assertEquals("https://api.example/v1", provider.displayName)
    }

    @Test
    fun displayNameIsEmptyWhenNeitherIsSet() {
        assertEquals("", LlmProvider(name = "", endpoint = "").displayName)
    }

    @Test
    fun authDefaultsToBearer() {
        assertEquals(LlmAuthStyle.BEARER, LlmProvider().auth.style)
    }
}
