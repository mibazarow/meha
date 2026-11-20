package com.andharness.ru.ui.language

import com.andharness.ru.domain.llm.LlmAuthConfig
import com.andharness.ru.domain.locale.AppLanguage
import com.andharness.ru.domain.locale.LocaleApplier
import com.andharness.ru.domain.settings.LlmSettings
import com.andharness.ru.domain.settings.LlmSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM-тесты [LanguageChooserViewModel]: стартовое состояние [LanguageChooserViewModel.chosen]
 * ВСЕГДА `false` (даже если язык выбирали в прошлом сеансе — шаг языка не пропускается, пока не
 * сохранено подключение к модели); выбор языка нормализуется, сохраняется в стор, применяется через
 * [LocaleApplier] и ровно один раз (повторный вызов игнорируется). Детерминированно через
 * [StandardTestDispatcher] как `Dispatchers.Main`.
 */
class LanguageChooserViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial chosen is always false so the language step is never skipped`() = runTest(dispatcher) {
        assertFalse(
            LanguageChooserViewModel(FakeStore(chosen = false), LocaleApplier {}).chosen.value
        )
        // Even when a language was chosen in an earlier session (persisted flag true), the chooser
        // must NOT auto-advance on a later launch.
        assertFalse(
            LanguageChooserViewModel(FakeStore(chosen = true), LocaleApplier {}).chosen.value
        )
    }

    @Test
    fun `choose normalizes, persists, applies once and flips chosen`() = runTest(dispatcher) {
        val store = FakeStore(chosen = false)
        val applied = mutableListOf<String>()
        val viewModel = LanguageChooserViewModel(store, LocaleApplier { applied += it })

        viewModel.choose("EN")
        advanceUntilIdle()
        // Second call must be a no-op (the recreate-driven recomposition cannot change the value).
        viewModel.choose(AppLanguage.RUSSIAN)
        advanceUntilIdle()

        assertEquals(listOf(AppLanguage.ENGLISH), store.updated)
        assertEquals(listOf(AppLanguage.ENGLISH), applied)
        assertTrue(viewModel.chosen.value)
        // The dedicated "explicitly chosen" flag is set exactly once — that is what the gate reads.
        assertEquals(1, store.markedCount)
    }

    @Test
    fun `choose sets the dedicated language-chosen flag exactly once`() = runTest(dispatcher) {
        val store = FakeStore(chosen = false)
        val viewModel = LanguageChooserViewModel(store, LocaleApplier {})

        viewModel.choose(AppLanguage.RUSSIAN)
        advanceUntilIdle()

        assertEquals(1, store.markedCount)
    }

    private class FakeStore(private val chosen: Boolean) : LlmSettingsStore {
        val updated = mutableListOf<String>()
        var markedCount = 0

        override val config: Flow<LlmSettings> = flowOf(LlmSettings(endpoint = "", model = ""))

        override suspend fun updateEndpoint(value: String) = Unit
        override suspend fun updateModel(value: String) = Unit
        override suspend fun updateReasoningEffort(value: String) = Unit
        override suspend fun updateContextCompressionTokens(value: Long) = Unit

        override suspend fun updateLanguage(value: String) {
            updated += value
        }

        override fun hasChosenLanguage(): Boolean = chosen

        override fun markLanguageChosen() {
            markedCount++
        }

        override suspend fun getApiKey(): String? = null
        override suspend fun setApiKey(key: String) = Unit
        override suspend fun clearApiKey() = Unit
        override suspend fun getAuthConfig(): LlmAuthConfig = LlmAuthConfig()
        override suspend fun setAuthConfig(config: LlmAuthConfig) = Unit
        override suspend fun reset() = Unit
        override fun isConfigured(): Flow<Boolean> = flowOf(false)
    }
}
