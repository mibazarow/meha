package com.andharness.ru.ui.onboarding

import com.andharness.ru.domain.common.Hashing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты чистой логики облачного шага онбординга [OnboardingModels]: когда каталог грузится
 * автоматически и когда поздний ответ ещё актуален. Android/Compose здесь не задействованы.
 *
 * Отпечаток ключа считается общим [Hashing.keyFingerprint] (SHA-256, hex, усечённый) — тем же,
 * что использует `ModelsCatalogClient` и аудит-цепочка, поэтому тест фиксирует совместимость
 * late-guard с идентичностью кэша клиента.
 */
class OnboardingModelsTest {

    @Test
    fun looksLikeUrl_acceptsHttpAndHttps_includingSurroundingSpaces() {
        assertTrue(OnboardingModels.looksLikeUrl("https://api.example.com/v1"))
        assertTrue(OnboardingModels.looksLikeUrl("http://127.0.0.1:8080"))
        assertTrue(OnboardingModels.looksLikeUrl("  https://api.example.com  "))
    }

    @Test
    fun looksLikeUrl_rejectsNonUrls() {
        assertFalse(OnboardingModels.looksLikeUrl(""))
        assertFalse(OnboardingModels.looksLikeUrl("api.example.com"))
        assertFalse(OnboardingModels.looksLikeUrl("ftp://api.example.com"))
        assertFalse(OnboardingModels.looksLikeUrl("ht"))
    }

    @Test
    fun normalizeEndpoint_trimsAndDropsTrailingSlash() {
        assertEquals("https://api.example.com/v1", OnboardingModels.normalizeEndpoint("  https://api.example.com/v1/  "))
        assertEquals("https://api.example.com", OnboardingModels.normalizeEndpoint("https://api.example.com"))
    }

    @Test
    fun shouldAutoLoad_requiresBothUrlEndpointAndNonBlankKey() {
        assertTrue(OnboardingModels.shouldAutoLoad("https://api.example.com", "sk-test"))
        assertTrue(OnboardingModels.shouldAutoLoad("http://127.0.0.1:8080", "  sk-test  "))
    }

    @Test
    fun shouldAutoLoad_isFalseWithoutKeyOrWithoutUrl() {
        assertFalse(OnboardingModels.shouldAutoLoad("https://api.example.com", ""))
        assertFalse(OnboardingModels.shouldAutoLoad("https://api.example.com", "   "))
        assertFalse(OnboardingModels.shouldAutoLoad("api.example.com", "sk-test"))
        assertFalse(OnboardingModels.shouldAutoLoad("", "sk-test"))
    }

    @Test
    fun responseStillRelevant_whenEndpointAndKeyUnchanged() {
        val key = "sk-test"
        assertTrue(
            OnboardingModels.responseStillRelevant(
                requestEndpoint = "https://api.example.com/",
                requestKeyFingerprint = Hashing.keyFingerprint(key),
                currentEndpoint = "https://api.example.com",
                currentKey = key,
            )
        )
    }

    @Test
    fun responseStillRelevant_isFalseWhenEndpointChanged() {
        val key = "sk-test"
        assertFalse(
            OnboardingModels.responseStillRelevant(
                requestEndpoint = "https://api.example.com",
                requestKeyFingerprint = Hashing.keyFingerprint(key),
                currentEndpoint = "https://other.example.com",
                currentKey = key,
            )
        )
    }

    @Test
    fun responseStillRelevant_isFalseWhenKeyChanged() {
        assertFalse(
            OnboardingModels.responseStillRelevant(
                requestEndpoint = "https://api.example.com",
                requestKeyFingerprint = Hashing.keyFingerprint("sk-old"),
                currentEndpoint = "https://api.example.com",
                currentKey = "sk-new",
            )
        )
    }

    @Test
    fun responseStillRelevant_matchesKeylessRequests() {
        assertTrue(
            OnboardingModels.responseStillRelevant(
                requestEndpoint = "http://127.0.0.1:8080",
                requestKeyFingerprint = Hashing.keyFingerprint(""),
                currentEndpoint = "http://127.0.0.1:8080/",
                currentKey = "",
            )
        )
    }
}
