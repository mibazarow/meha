package com.andharness.ru.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Locks the pure device-fact rules (TZ §12.3): freshness (TTL / invalidation) and the compact
 * prompt summary. No Android dependency — runs on the JVM.
 */
class DeviceFactSummaryTest {

    private val now = 1_700_000_000_000L
    private val utc = ZoneId.of("UTC")

    private fun fact(
        key: String,
        value: String,
        updatedAt: Long = now,
        ttlMs: Long = DeviceFactSpecs.TTL_24_H,
        invalidated: Boolean = false
    ): DeviceFact {
        val spec = DeviceFactSpecs.byKey(key)!!
        return DeviceFact(
            key = key,
            category = spec.category,
            valueJson = value,
            updatedAt = updatedAt,
            ttlMs = ttlMs,
            source = "device_facts_refresh",
            sourceApi = "test",
            invalidated = invalidated
        )
    }

    @Test
    fun specsAreUniqueAndCoverTheCatalogue() {
        val keys = DeviceFactSpecs.keys
        assertEquals("fact keys must be unique", keys.size, keys.toSet().size)
        assertTrue(DeviceFactSpecs.keys.size >= 15)
        assertTrue(DeviceFactSpecs.isCacheable(DeviceFactSpecs.INSTALLED_APPS))
        assertFalse(DeviceFactSpecs.isCacheable("no_such_fact"))
        assertTrue("summary facts must exist", DeviceFactSpecs.all.any { it.summary })
    }

    @Test
    fun ttlZeroNeverExpiresAndPositiveTtlExpires() {
        val infinite = fact(DeviceFactSpecs.DEVICE_MODEL, "{}", ttlMs = DeviceFactSpecs.TTL_INFINITE)
        assertTrue(DeviceFactSpecs.isFresh(infinite, now))
        assertTrue(DeviceFactSpecs.isFresh(infinite.copy(updatedAt = now - 10 * 365L * 24 * 3600 * 1000), now))

        val shortLived = fact(DeviceFactSpecs.CURRENT_NETWORK, "{}", ttlMs = DeviceFactSpecs.TTL_5_MIN)
        assertTrue(DeviceFactSpecs.isFresh(shortLived, now))
        assertFalse(DeviceFactSpecs.isFresh(shortLived.copy(updatedAt = now - DeviceFactSpecs.TTL_5_MIN - 1), now))

        val invalidated = shortLived.copy(invalidated = true)
        assertFalse("an invalidated fact is never fresh", DeviceFactSpecs.isFresh(invalidated, now))
    }

    @Test
    fun renderIsEmptyWithoutFreshSummaryFacts() {
        assertEquals("", DeviceFactSummary.render(emptyList(), now, utc))
        assertEquals(
            "",
            DeviceFactSummary.render(
                listOf(
                    fact(DeviceFactSpecs.SCREEN, "{\"widthPx\":1080}"),
                    fact(DeviceFactSpecs.INSTALLED_APPS, "{\"count\":5}", invalidated = true)
                ),
                now,
                utc
            )
        )
    }

    @Test
    fun renderIncludesSummaryLinesAndInstruction() {
        val block = DeviceFactSummary.render(
            listOf(
                fact(
                    DeviceFactSpecs.DEVICE_MODEL,
                    "{\"manufacturer\":\"Samsung\",\"model\":\"SM-A075F\",\"device\":\"a07\"}",
                    ttlMs = DeviceFactSpecs.TTL_INFINITE
                ),
                fact(
                    DeviceFactSpecs.ANDROID_VERSION,
                    "{\"release\":\"16\",\"sdk\":36,\"securityPatch\":\"2026-01-01\"}",
                    ttlMs = DeviceFactSpecs.TTL_INFINITE
                ),
                fact(DeviceFactSpecs.INSTALLED_APPS, "{\"count\":312}"),
                fact(
                    DeviceFactSpecs.DEFAULT_ROLES,
                    "{\"held\":[\"android.app.role.HOME\"]}"
                ),
                fact(
                    DeviceFactSpecs.SPECIAL_ACCESS,
                    "{\"accessibility\":true,\"notification_listener\":false}",
                    ttlMs = DeviceFactSpecs.TTL_5_MIN
                ),
                // stale: must not appear
                fact(
                    DeviceFactSpecs.STORAGE_VOLUMES,
                    "{\"count\":9,\"volumes\":[]}",
                    updatedAt = now - DeviceFactSpecs.TTL_1_H - 1,
                    ttlMs = DeviceFactSpecs.TTL_1_H
                )
            ),
            now,
            utc
        )

        assertTrue(block.startsWith("DEVICE FACTS (обновлено "))
        assertTrue(block.contains("- модель: Samsung SM-A075F (device a07)"))
        assertTrue(block.contains("- Android: 16 (API 36), патч 2026-01-01"))
        assertTrue(block.contains("- приложений: 312"))
        assertTrue(block.contains("- роли: HOME"))
        assertTrue(block.contains("accessibility"))
        assertFalse("stale facts must be omitted", block.contains("тома:"))
        assertTrue(block.endsWith(DeviceFactSummary.PROMPT_INSTRUCTION))
    }

    @Test
    fun shortRoleStripsTheAndroidPrefix() {
        assertEquals("HOME", DeviceFactSummary.shortRole("android.app.role.HOME"))
        assertEquals("CUSTOM", DeviceFactSummary.shortRole("CUSTOM"))
    }
}
