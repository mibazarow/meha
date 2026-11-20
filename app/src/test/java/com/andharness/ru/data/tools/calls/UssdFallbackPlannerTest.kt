package com.andharness.ru.data.tools.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [UssdFallbackPlanner] — the pure "should we even attempt USSD directly?" decision
 * behind the honest `ussd_request` fix (BUG-056). No Android runtime.
 */
class UssdFallbackPlannerTest {

    @Test
    fun defaultDialerGetsADirectPlan() {
        val plan = UssdFallbackPlanner.plan("*100#", isDefaultDialer = true)
        assertTrue(plan is UssdFallbackPlanner.Plan.Direct)
        assertEquals("*100#", (plan as UssdFallbackPlanner.Plan.Direct).code)
    }

    @Test
    fun nonDefaultDialerGetsTheDialFallbackInsteadOfADirectAttempt() {
        val plan = UssdFallbackPlanner.plan("*100#", isDefaultDialer = false)
        assertTrue(plan is UssdFallbackPlanner.Plan.DialFallback)
        val fallback = plan as UssdFallbackPlanner.Plan.DialFallback
        assertEquals("tel:*100%23", fallback.dialUri)
        assertTrue(fallback.message.contains("дозвонщику по умолчанию"))
    }

    @Test
    fun hashIsPercentEncodedSoTheDialerReceivesTheWholeCode() {
        assertEquals("*100%23", UssdFallbackPlanner.encode("*100#"))
        assertEquals("tel:*100%23", UssdFallbackPlanner.dialUri("*100#"))
        assertEquals("a%25b%23", UssdFallbackPlanner.encode("a%b#"))
    }

    @Test
    fun fallbackNeverPlacesTheCallItself() {
        val fallback = UssdFallbackPlanner.fallback("*100#", UssdFallbackPlanner.Reason.NOT_DEFAULT_DIALER)
        // ACTION_DIAL semantics: the user must press the call button; nothing dials automatically.
        assertTrue(fallback.message.contains("не совершается автоматически"))
        assertTrue(fallback.message.contains("наберите"))
    }

    @Test
    fun timeoutReasonExplainsThatTheNetworkDidNotAnswer() {
        val fallback = UssdFallbackPlanner.fallback("*100#", UssdFallbackPlanner.Reason.TIMEOUT)
        assertTrue(fallback.message.contains("не получен за отведённое время"))
        assertTrue(fallback.message.contains("tel:*100%23"))
    }

    @Test
    fun failedReasonIsHonestAboutTheError() {
        val message = UssdFallbackPlanner.message("*100#", UssdFallbackPlanner.Reason.FAILED)
        assertTrue(message.contains("вернули ошибку"))
        assertFalse(message.contains("успешно"))
    }
}
