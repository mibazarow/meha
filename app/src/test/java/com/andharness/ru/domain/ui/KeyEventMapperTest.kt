package com.andharness.ru.domain.ui

import com.andharness.ru.domain.ui.KeyEventMapper.GlobalAction
import com.andharness.ru.domain.ui.KeyEventMapper.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM test for the `key_event` mapping added alongside `lock`/`screenshot`: every documented
 * key maps to the right global action, and the screenshot API-level guard rejects honestly below
 * Android 14 (API 34).
 */
class KeyEventMapperTest {

    private fun send(key: String, sdkInt: Int = 31): GlobalAction {
        val resolution = KeyEventMapper.resolve(key, sdkInt)
        assertTrue("expected Send for «$key» but got $resolution", resolution is Resolution.Send)
        return (resolution as Resolution.Send).action
    }

    private fun reject(key: String, sdkInt: Int): String {
        val resolution = KeyEventMapper.resolve(key, sdkInt)
        assertTrue("expected Reject for «$key» but got $resolution", resolution is Resolution.Reject)
        return (resolution as Resolution.Reject).message
    }

    @Test
    fun existingKeysKeepTheirMappings() {
        assertEquals(GlobalAction.BACK, send("back"))
        assertEquals(GlobalAction.HOME, send("home"))
        assertEquals(GlobalAction.RECENTS, send("recents"))
        assertEquals(GlobalAction.NOTIFICATIONS, send("notifications"))
        assertEquals(GlobalAction.QUICK_SETTINGS, send("quick_settings"))
    }

    @Test
    fun lockMapsToLockScreen() {
        assertEquals(GlobalAction.LOCK_SCREEN, send("lock"))
        assertEquals(GlobalAction.LOCK_SCREEN, send("блокировка"))
    }

    @Test
    fun keysAreCaseInsensitiveAndTrimmed() {
        assertEquals(GlobalAction.BACK, send("  BACK  "))
        assertEquals(GlobalAction.LOCK_SCREEN, send("Lock"))
    }

    @Test
    fun screenshotMapsToScreenshotActionOnAndroid14AndAbove() {
        assertEquals(GlobalAction.SCREENSHOT, send("screenshot", KeyEventMapper.SCREENSHOT_MIN_SDK))
        assertEquals(GlobalAction.SCREENSHOT, send("screenshot", 35))
        assertEquals(GlobalAction.SCREENSHOT, send("скриншот", 34))
    }

    @Test
    fun screenshotIsRejectedBelowAndroid14() {
        val message = reject("screenshot", KeyEventMapper.SCREENSHOT_MIN_SDK - 1)
        assertTrue("сообщение должно называть API 34: $message", message.contains("API 34"))
        assertTrue("сообщение должно называть уровень устройства: $message", message.contains("33"))
        reject("screenshot", 31)
    }

    @Test
    fun unknownKeyIsRejectedWithTheSupportedList() {
        val message = reject("volume_up", 34)
        assertTrue(message.startsWith("key: "))
        assertTrue(message.contains("lock"))
        assertTrue(message.contains("screenshot"))
    }
}
