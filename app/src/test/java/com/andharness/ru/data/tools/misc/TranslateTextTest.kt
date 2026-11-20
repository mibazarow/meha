package com.andharness.ru.data.tools.misc

import com.andharness.ru.domain.agent.LlmClient
import com.andharness.ru.domain.agent.LlmEvent
import com.andharness.ru.domain.agent.LlmRequest
import com.andharness.ru.domain.llm.LlmAuthConfig
import com.andharness.ru.domain.settings.LlmSettings
import com.andharness.ru.domain.settings.LlmSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake client that records the request and replays a fixed event list (no network). */
private class FakeLlmClient(private val events: List<LlmEvent>) : LlmClient {
    var calls = 0
    var lastRequest: LlmRequest? = null

    override fun complete(request: LlmRequest): Flow<LlmEvent> = flow {
        calls++
        lastRequest = request
        events.forEach { emit(it) }
    }
}

/** Minimal settings fake: only `isConfigured` matters to the handler. */
private class FakeTranslateSettings(private val configured: Boolean) : LlmSettingsStore {
    override val config: Flow<LlmSettings> = flowOf(LlmSettings(endpoint = "", model = "m"))

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

class TranslateTextTest {

    // ---------------------------------------------------------------- prompt builder

    @Test
    fun promptCarriesTargetSourceAndTone() {
        val prompt = buildTranslatePrompt(
            text = "Привет",
            to = "en",
            from = "ru",
            tone = "формальный"
        )
        assertTrue(prompt.contains("Целевой язык: en"))
        assertTrue(prompt.contains("Исходный язык: ru"))
        assertTrue(prompt.contains("Желаемый стиль: формальный"))
        assertTrue(prompt.contains("Привет"))
    }

    @Test
    fun promptAutoDetectsSourceWhenMissing() {
        val prompt = buildTranslatePrompt(text = "Hello", to = "ru", from = null, tone = null)
        assertTrue(prompt.contains("Целевой язык: ru"))
        assertTrue(prompt.contains("определи автоматически"))
        assertFalse("тон не задан — строки стиля быть не должно", prompt.contains("Желаемый стиль"))
        assertTrue(prompt.contains("Hello"))
    }

    @Test
    fun systemPromptDemandsTranslationOnly() {
        assertTrue(TRANSLATE_SYSTEM_PROMPT.contains("ТОЛЬКО переводом"))
        assertTrue(TRANSLATE_SYSTEM_PROMPT.contains("без пояснений"))
    }

    // ---------------------------------------------------------------- response post-processing

    @Test
    fun trimsWhitespaceAndSurroundingQuotes() {
        assertEquals("Привет", cleanTranslation("  Привет \n"))
        assertEquals("Привет", cleanTranslation("\"Привет\""))
        assertEquals("Привет", cleanTranslation("«Привет»"))
        assertEquals("Привет", cleanTranslation("'Привет'"))
    }

    @Test
    fun stripsMarkdownCodeFences() {
        assertEquals("Hello world", cleanTranslation("```\nHello world\n```"))
        assertEquals("Hello world", cleanTranslation("```text\nHello world\n```"))
        assertEquals("Hello world", cleanTranslation("```text\n\"Hello world\"\n```"))
    }

    @Test
    fun stripsNestedQuoteLayers() {
        assertEquals("Привет", cleanTranslation("«\"Привет\"»"))
        assertEquals("Привет", cleanTranslation("```\n«Привет»\n```"))
    }

    @Test
    fun keepsInnerQuotesAndMultilineTranslations() {
        assertEquals("Он сказал \"да\" и ушёл", cleanTranslation("Он сказал \"да\" и ушёл"))
        assertEquals("Строка 1\nСтрока 2", cleanTranslation("\nСтрока 1\nСтрока 2\n"))
    }

    // ---------------------------------------------------------------- language catalog

    @Test
    fun resolvesIsoCode() {
        assertEquals("en", TranslateLanguages.resolve("en"))
        assertEquals("ru", TranslateLanguages.resolve("ru"))
    }

    @Test
    fun resolvesRussianName() {
        assertEquals("de", TranslateLanguages.resolve("немецкий"))
        assertEquals("ru", TranslateLanguages.resolve("русский"))
    }

    @Test
    fun resolvesEnglishName() {
        assertEquals("fr", TranslateLanguages.resolve("French"))
        assertEquals("en", TranslateLanguages.resolve("english"))
    }

    @Test
    fun resolutionIsCaseInsensitive() {
        assertEquals("ru", TranslateLanguages.resolve("RU"))
        assertEquals("en", TranslateLanguages.resolve("АНГЛИЙСКИЙ"))
        assertEquals("zh", TranslateLanguages.resolve("ZH-Hans"))
    }

    @Test
    fun unknownLanguageDoesNotResolve() {
        assertNull(TranslateLanguages.resolve("klingon"))
        assertNull(TranslateLanguages.resolve(""))
        assertNull(TranslateLanguages.resolve("   "))
        assertNull(TranslateLanguages.resolve(null))
    }

    @Test
    fun autoDetectionIsRecognized() {
        assertTrue(TranslateLanguages.isAuto("auto"))
        assertTrue(TranslateLanguages.isAuto("AUTO"))
        assertTrue(TranslateLanguages.isAuto(""))
        assertTrue(TranslateLanguages.isAuto(null))
        assertFalse(TranslateLanguages.isAuto("en"))
    }

    // ---------------------------------------------------------------- handler honesty

    @Test
    fun refusesHonestlyWhenNoModelIsConfigured() = runTest {
        val client = FakeLlmClient(listOf(LlmEvent.Text("unused")))
        val handler = TranslateToolsHandler(client, FakeTranslateSettings(configured = false))
            .handlers().single()

        val result = handler.execute(args(text = "Привет", to = "en"))

        assertFalse(result.success)
        assertTrue(result.output.contains("не настроена"))
        assertEquals("no LLM call may be made without a configured model", 0, client.calls)
    }

    @Test
    fun reportsModelErrorHonestly() = runTest {
        val client = FakeLlmClient(listOf(LlmEvent.Error("нет соединения", false)))
        val handler = TranslateToolsHandler(client, FakeTranslateSettings(configured = true))
            .handlers().single()

        val result = handler.execute(args(text = "Привет", to = "en"))

        assertFalse(result.success)
        assertTrue(result.output.contains("нет соединения"))
    }

    @Test
    fun reportsEmptyModelAnswerHonestly() = runTest {
        val client = FakeLlmClient(listOf(LlmEvent.Done))
        val handler = TranslateToolsHandler(client, FakeTranslateSettings(configured = true))
            .handlers().single()

        val result = handler.execute(args(text = "Привет", to = "en"))

        assertFalse(result.success)
        assertTrue(result.output.contains("пустой ответ"))
    }

    @Test
    fun requiresTextAndTarget() = runTest {
        val client = FakeLlmClient(listOf(LlmEvent.Text("x")))
        val store = FakeTranslateSettings(configured = true)

        val noText = TranslateToolsHandler(client, store).handlers().single()
            .execute(buildJsonObject { put("to", "en") })
        assertFalse(noText.success)
        assertTrue(noText.output.contains("text"))

        val noTo = TranslateToolsHandler(client, store).handlers().single()
            .execute(buildJsonObject { put("text", "Привет") })
        assertFalse(noTo.success)
        assertTrue(noTo.output.contains("to"))
    }

    @Test
    fun rejectsUnknownTargetLanguageWithoutCallingLlm() = runTest {
        val client = FakeLlmClient(listOf(LlmEvent.Text("maj', poH")))
        val handler = TranslateToolsHandler(client, FakeTranslateSettings(configured = true))
            .handlers().single()

        val result = handler.execute(args(text = "Привет, мир", to = "klingon"))

        assertFalse(result.success)
        assertTrue(result.output.contains("klingon"))
        assertTrue("в ошибке должны быть примеры поддерживаемых языков", result.output.contains("ru"))
        assertEquals("unknown target language must not reach the LLM", 0, client.calls)
    }

    @Test
    fun normalizesRecognizedTargetLanguageForThePrompt() = runTest {
        val client = FakeLlmClient(listOf(LlmEvent.Text("Hallo")))
        val handler = TranslateToolsHandler(client, FakeTranslateSettings(configured = true))
            .handlers().single()

        val result = handler.execute(args(text = "Привет", to = "Немецкий"))

        assertTrue(result.success)
        assertTrue(client.lastRequest!!.messages.last().content!!.contains("Целевой язык: de"))
    }

    @Test
    fun acceptsAutoAndEmptySourceLanguage() = runTest {
        val client = FakeLlmClient(listOf(LlmEvent.Text("Hello")))
        val store = FakeTranslateSettings(configured = true)

        val auto = TranslateToolsHandler(client, store).handlers().single()
            .execute(args(text = "Привет", to = "en", from = "auto"))
        assertTrue(auto.success)

        val empty = TranslateToolsHandler(client, store).handlers().single()
            .execute(buildJsonObject { put("text", "Привет"); put("to", "en"); put("from", "") })
        assertTrue(empty.success)

        assertTrue(
            "auto/empty source must be auto-detected in the prompt",
            client.lastRequest!!.messages.last().content!!.contains("определи автоматически")
        )
    }

    @Test
    fun rejectsUnknownSourceLanguageWithoutCallingLlm() = runTest {
        val client = FakeLlmClient(listOf(LlmEvent.Text("x")))
        val handler = TranslateToolsHandler(client, FakeTranslateSettings(configured = true))
            .handlers().single()

        val result = handler.execute(args(text = "Привет", to = "en", from = "klingon"))

        assertFalse(result.success)
        assertTrue(result.output.contains("klingon"))
        assertEquals("unknown source language must not reach the LLM", 0, client.calls)
    }

    @Test
    fun returnsCleanedTranslationAndSendsStrictPrompt() = runTest {
        val client = FakeLlmClient(listOf(LlmEvent.Text("```\n\"Hello\"\n```"), LlmEvent.Done))
        val handler = TranslateToolsHandler(client, FakeTranslateSettings(configured = true))
            .handlers().single()

        val result = handler.execute(args(text = "Привет", to = "en", tone = "разговорный"))

        assertTrue(result.success)
        assertEquals("Hello", result.output)
        val request = client.lastRequest!!
        assertEquals("system", request.messages.first().role)
        assertEquals(TRANSLATE_SYSTEM_PROMPT, request.messages.first().content)
        assertTrue(request.messages.last().content!!.contains("разговорный"))
    }

    private fun args(
        text: String,
        to: String,
        tone: String? = null,
        from: String? = null
    ): JsonObject = buildJsonObject {
        put("text", text)
        put("to", to)
        if (tone != null) put("tone", tone)
        if (from != null) put("from", from)
    }
}
