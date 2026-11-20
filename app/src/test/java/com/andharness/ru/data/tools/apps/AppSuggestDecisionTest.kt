package com.andharness.ru.data.tools.apps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [AppSuggestDecision]: the installed-vs-not branch and the honest unknown-format
 * answer. The decision is pure (it never touches the PackageManager), so it runs off-device.
 */
class AppSuggestDecisionTest {

    @Test
    fun installedHandlerIsReportedAsAlreadyAvailable() {
        val advice = AppSuggestDecision.advice("application/pdf", installed = true)
        assertTrue(advice.success)
        assertTrue(advice.text.contains("УЖЕ"))
        assertFalse("must not suggest installs when a handler exists", advice.text.contains("market://"))
    }

    @Test
    fun missingHandlerListsInstallableAppsWithDeepLinks() {
        val advice = AppSuggestDecision.advice("rar", installed = false)
        assertTrue(advice.success)
        assertTrue(advice.text.contains("application/vnd.rar"))
        assertTrue("expected a Play deep link", advice.text.contains("market://search?q="))
        assertTrue("expected a web Play fallback", advice.text.contains("play.google.com"))
    }

    @Test
    fun unknownFormatIsHonestAndNotSuccess() {
        val advice = AppSuggestDecision.advice("qwerty-not-a-format", installed = false)
        assertFalse(advice.success)
        assertTrue(advice.text.contains("Не удалось определить"))
    }
}
