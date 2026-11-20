package com.andharness.ru.data.agent

import com.andharness.ru.data.style.StyleProfileStore
import com.andharness.ru.domain.agent.LlmClient
import com.andharness.ru.domain.agent.LlmEvent
import com.andharness.ru.domain.agent.LlmMessage
import com.andharness.ru.domain.agent.LlmRequest
import com.andharness.ru.domain.llm.LlmAuthConfig
import com.andharness.ru.domain.settings.LlmSettings
import com.andharness.ru.domain.settings.LlmSettingsStore
import com.andharness.ru.domain.style.StyleMode
import com.andharness.ru.domain.style.StyleProfile
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class CardLlmClient(private val events: List<LlmEvent>) : LlmClient {
    var calls = 0
    var lastRequest: LlmRequest? = null

    override fun complete(request: LlmRequest): Flow<LlmEvent> = flow {
        calls++
        lastRequest = request
        events.forEach { emit(it) }
    }
}

private class CardSettings(private val configured: Boolean) : LlmSettingsStore {
    override val config: Flow<LlmSettings> = flowOf(LlmSettings(endpoint = "https://x.test/v1", model = "m"))
    override suspend fun updateEndpoint(value: String) = Unit
    override suspend fun updateModel(value: String) = Unit
    override suspend fun updateReasoningEffort(value: String) = Unit
    override suspend fun updateContextCompressionTokens(value: Long) = Unit
    override suspend fun getApiKey(): String? = null
    override suspend fun setApiKey(key: String) = Unit
    override suspend fun clearApiKey() = Unit
    override suspend fun getAuthConfig(): LlmAuthConfig = LlmAuthConfig.DEFAULT
    override suspend fun setAuthConfig(config: LlmAuthConfig) = Unit
    override suspend fun reset() = Unit
    override fun isConfigured(): Flow<Boolean> = flowOf(configured)
}

class StyleCardExtractorTest {

    private val dirs = mutableListOf<File>()
    private val now = 1_700_000_000_000L

    private fun newStore(): StyleProfileStore =
        StyleProfileStore(Files.createTempDirectory("style-card-test").toFile().also { dirs += it })

    @After
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun user(text: String) = LlmMessage("user", text)
    private fun assistant(text: String) = LlmMessage("assistant", text)

    @Test
    fun parsesOneCleanLine() {
        assertEquals("коротко, на ты", parseStyleCard("- «коротко, на ты»."))
        assertEquals("с иронией", parseStyleCard("с иронией\nвторая строка"))
        assertEquals("очень", parseStyleCard("очень длинная карточка", maxChars = 5))
    }

    @Test
    fun rejectsBlankAndHostileCards() {
        assertNull(parseStyleCard("   "))
        assertNull(parseStyleCard("ты мудак"))
    }

    @Test
    fun gateRequiresSamplesAndRespectsModeAndCooldown() {
        val extractor = StyleCardExtractor(CardLlmClient(emptyList()), CardSettings(true), newStore())

        assertFalse(extractor.shouldRun(StyleProfile(samples = STYLE_MIN_SAMPLES - 1), now))
        assertTrue(extractor.shouldRun(StyleProfile(samples = STYLE_MIN_SAMPLES), now))
        assertFalse(extractor.shouldRun(StyleProfile(samples = 50, mode = StyleMode.NEUTRAL), now))
        assertFalse(
            extractor.shouldRun(
                StyleProfile(samples = 50, card = "коротко", cardUpdatedAt = now),
                now,
            )
        )
        assertTrue(
            extractor.shouldRun(
                StyleProfile(samples = 50, card = "коротко", cardUpdatedAt = now - STYLE_CARD_COOLDOWN_MS),
                now,
            )
        )
    }

    @Test
    fun transcriptUsesOnlyUserMessagesAndCapsThem() {
        val extractor = StyleCardExtractor(CardLlmClient(emptyList()), CardSettings(true), newStore())
        val dropped = listOf(
            user("первое"),
            assistant("ответ агента"),
            user("x".repeat(400)),
        )

        val transcript = extractor.buildTranscript(dropped)

        assertTrue(transcript.contains("первое"))
        assertFalse(transcript.contains("ответ агента"))
        assertTrue(transcript.length <= STYLE_MAX_INPUT_MESSAGE_CHARS * 2 + 10)
    }

    @Test
    fun savesTheCardOnceAndHonorsCooldown() = runTest {
        val store = newStore()
        store.update { it.copy(samples = STYLE_MIN_SAMPLES) }
        val client = CardLlmClient(listOf(LlmEvent.Text("коротко, на ты, с иронией"), LlmEvent.Done))
        val extractor = StyleCardExtractor(client, CardSettings(true), store)

        assertTrue(extractor.updateIfDue(listOf(user("привет")), now))
        assertEquals("коротко, на ты, с иронией", store.current().card)
        assertEquals(1, client.calls)

        assertFalse(extractor.updateIfDue(listOf(user("привет")), now + 1_000L))
        assertEquals(1, client.calls)
    }

    @Test
    fun skipsWhenTheModelIsNotConfigured() = runTest {
        val store = newStore()
        store.update { it.copy(samples = STYLE_MIN_SAMPLES) }
        val client = CardLlmClient(listOf(LlmEvent.Text("коротко"), LlmEvent.Done))
        val extractor = StyleCardExtractor(client, CardSettings(false), store)

        assertFalse(extractor.updateIfDue(listOf(user("привет")), now))
        assertEquals(0, client.calls)
    }

    @Test
    fun hostileModelOutputIsNotSaved() = runTest {
        val store = newStore()
        store.update { it.copy(samples = STYLE_MIN_SAMPLES) }
        val client = CardLlmClient(listOf(LlmEvent.Text("ты мудак"), LlmEvent.Done))
        val extractor = StyleCardExtractor(client, CardSettings(true), store)

        assertFalse(extractor.updateIfDue(listOf(user("привет")), now))
        assertNull(store.current().card)
    }
}
