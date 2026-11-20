package com.andharness.ru.data.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the Vision detection policy (update.md §3): allowlist matching, cache keying
 * and expiry, the allowlist-vs-cache-vs-probe decision, and the probe-response classification.
 * No network and no Android runtime.
 */
class VisionPolicyTest {

    // ---- allowlist matching ----

    @Test
    fun knownVisionModelsMatchTheAllowlist() {
        val visionModels = listOf(
            "gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini", "gpt-5", "gpt-5-mini",
            "o3", "o3-mini", "o4-mini",
            "claude-3-5-sonnet-20241022", "claude-4-sonnet", "claude-sonnet-4-20250514",
            "gemini-1.5-pro", "gemini-2.0-flash",
            "qwen-vl-max", "qwen2-vl-7b", "qwen2.5-vl-72b",
            "llava-1.5-7b", "moondream2", "pixtral-12b", "internvl2-8b", "minicpm-v-2_6",
        )
        for (model in visionModels) {
            assertTrue("«$model» должна считаться vision-моделью", VisionPolicy.allowlistHit(model) != null)
        }
    }

    @Test
    fun nonVisionModelsDoNotMatch() {
        val textOnly = listOf(
            "gpt-3.5-turbo", "gpt-4", "claude-2.1", "mistral-7b-instruct", "llama-3-70b",
            "o1", "o1-mini", "text-embedding-3-small", "codex-mini", "deepseek-chat", "phi-3-mini",
        )
        for (model in textOnly) {
            assertNull("«$model» не должна считаться vision-моделью", VisionPolicy.allowlistHit(model))
        }
    }

    @Test
    fun allowlistMatchingIsCaseInsensitive() {
        assertEquals("gpt-4o", VisionPolicy.allowlistHit("GPT-4O"))
        assertEquals("claude-3", VisionPolicy.allowlistHit("Claude-3-Opus"))
    }

    @Test
    fun shortPatternsRequireATokenBoundary() {
        // `o3` must match a real o3 id, but not the digits inside an unrelated id.
        assertEquals("o3", VisionPolicy.allowlistHit("o3-mini"))
        assertNull(VisionPolicy.allowlistHit("noto3model"))
        assertNull(VisionPolicy.allowlistHit("foo o3bar"))
    }

    @Test
    fun blankModelNeverMatches() {
        assertNull(VisionPolicy.allowlistHit(""))
        assertNull(VisionPolicy.allowlistHit("   "))
    }

    // ---- cache keying / expiry ----

    @Test
    fun cacheKeyIgnoresCaseAndTrailingSlash() {
        assertEquals(
            VisionPolicy.cacheKey("gpt-4o", "https://api.example.com/v1"),
            VisionPolicy.cacheKey("GPT-4O", "https://api.example.com/v1/"),
        )
    }

    @Test
    fun cacheKeySeparatesDifferentModelsAndEndpoints() {
        val base = VisionPolicy.cacheKey("mystery-model", "https://a.example.com/v1")
        assertNotEquals(base, VisionPolicy.cacheKey("other-model", "https://a.example.com/v1"))
        assertNotEquals(base, VisionPolicy.cacheKey("mystery-model", "https://b.example.com/v1"))
    }

    @Test
    fun freshnessIsBoundedByTtl() {
        val now = 1_000_000L
        assertTrue(VisionPolicy.isFresh(checkedAt = now - 1, now = now, ttlMs = 100))
        assertTrue(VisionPolicy.isFresh(checkedAt = now - 99, now = now, ttlMs = 100))
        assertFalse("ровно на границе TTL запись устарела",
            VisionPolicy.isFresh(checkedAt = now - 100, now = now, ttlMs = 100))
        assertFalse("запись из будущего не считается свежей",
            VisionPolicy.isFresh(checkedAt = now + 1, now = now, ttlMs = 100))
        assertFalse("нулевая метка времени не считается свежей",
            VisionPolicy.isFresh(checkedAt = 0, now = now, ttlMs = 100))
    }

    // ---- allowlist vs cache vs probe ----

    @Test
    fun allowlistWinsOverCacheAndProbe() {
        assertEquals(VisionDecision.Allowlisted("gpt-4o"), VisionPolicy.decide("gpt-4o", null, false))
        assertEquals(VisionDecision.Allowlisted("gpt-4o"), VisionPolicy.decide("gpt-4o", false, true))
    }

    @Test
    fun freshCacheAnswerIsUsedForUnknownModels() {
        assertEquals(VisionDecision.Cached(true), VisionPolicy.decide("mystery", true, true))
        assertEquals(VisionDecision.Cached(false), VisionPolicy.decide("mystery", false, true))
    }

    @Test
    fun missingOrStaleCacheForcesAProbe() {
        assertEquals(VisionDecision.Probe, VisionPolicy.decide("mystery", null, true))
        assertEquals(VisionDecision.Probe, VisionPolicy.decide("mystery", true, false))
        assertEquals(VisionDecision.Probe, VisionPolicy.decide("mystery", false, false))
    }

    // ---- probe URL / body ----

    @Test
    fun probeUrlUsesChatCompletionsAndTrimsSlash() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            VisionPolicy.probeUrl("https://api.example.com/v1/"),
        )
    }

    @Test
    fun probeBodyCarriesAnInlinePng() {
        val body = VisionPolicy.probeRequestBody("mystery", "AAAA")
        assertTrue(body.contains("\"model\":\"mystery\""))
        assertTrue(body.contains("\"image_url\""))
        assertTrue(body.contains("data:image/png;base64,AAAA"))
        assertTrue(body.contains("\"stream\":false"))
    }

    // ---- probe-response classification ----

    @Test
    fun successfulProbeMeansSupported() {
        assertEquals(VisionSupport.SUPPORTED, VisionPolicy.classifyProbeResponse(200, "{}"))
        assertEquals(VisionSupport.SUPPORTED, VisionPolicy.classifyProbeResponse(201, "{}"))
    }

    @Test
    fun clientErrorMentioningVisionMeansUnsupported() {
        assertEquals(
            VisionSupport.UNSUPPORTED,
            VisionPolicy.classifyProbeResponse(
                400,
                "{\"error\":{\"message\":\"This model does not support image inputs\"}}",
            ),
        )
        assertEquals(
            VisionSupport.UNSUPPORTED,
            VisionPolicy.classifyProbeResponse(422, "unsupported content modality: image_url"),
        )
    }

    @Test
    fun ambiguousOrServerErrorsStayUnknown() {
        assertEquals(VisionSupport.UNKNOWN, VisionPolicy.classifyProbeResponse(401, "invalid api key"))
        assertEquals(VisionSupport.UNKNOWN, VisionPolicy.classifyProbeResponse(404, "model not found"))
        assertEquals("5xx не должен превращаться в отказ", VisionSupport.UNKNOWN,
            VisionPolicy.classifyProbeResponse(500, "internal error mentioning image"))
        assertEquals("400 без упоминания картинки неоднозначен", VisionSupport.UNKNOWN,
            VisionPolicy.classifyProbeResponse(400, "bad request"))
    }

    // ---- status text ----

    @Test
    fun describeIsHonestAboutUnknown() {
        val unknown = VisionPolicy.describe(
            VisionState(
                support = VisionSupport.UNKNOWN,
                source = VisionSource.UNKNOWN,
                model = "mystery",
                endpoint = "https://api.example.com/v1",
                detail = "эндпоинт недоступен",
            )
        )
        assertTrue(unknown.contains("Vision: неизвестно"))
        assertTrue(unknown.contains("нет данных"))
        assertTrue(unknown.contains("эндпоинт недоступен"))
    }

    @Test
    fun describeReportsAllowlistSource() {
        val state = VisionPolicy.describe(
            VisionState(
                support = VisionSupport.SUPPORTED,
                source = VisionSource.ALLOWLIST,
                model = "gpt-4o",
                endpoint = "",
                detail = "совпала с известной vision-моделью",
            )
        )
        assertTrue(state.contains("Vision: поддерживается"))
        assertTrue(state.contains("Источник: allowlist"))
    }
}
