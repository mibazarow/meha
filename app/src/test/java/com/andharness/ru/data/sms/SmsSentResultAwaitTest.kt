package com.andharness.ru.data.sms

import android.app.Activity
import android.telephony.SmsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * JVM tests for the PURE parts extracted from the formerly duplicated SMS sent-result awaiting:
 * the per-prefix action-name builder and the result-code → outcome mapping.
 *
 * These are Android-free by construction. `Activity.RESULT_OK` and the `SmsManager.RESULT_ERROR_*`
 * codes are Java `static final int` compile-time constants (see
 * https://developer.android.com/reference/android/telephony/SmsManager), so they are inlined and no
 * Android runtime is touched.
 */
class SmsSentResultAwaitTest {

    @Test
    fun sentActionKeepsEachCallSitesHistoricActionName() {
        assertEquals(
            "com.andharness.ru.COMPOSE_SMS_SENT.123_abc",
            SmsSentResultAwait.sentAction("com.andharness.ru", SmsSentResultAwait.PREFIX_COMPOSE_SMS_SENT, "123_abc"),
        )
        assertEquals(
            "com.andharness.ru.SMS_SENT.123_abc",
            SmsSentResultAwait.sentAction("com.andharness.ru", SmsSentResultAwait.PREFIX_SMS_SENT, "123_abc"),
        )
    }

    @Test
    fun theTwoPrefixesStayDistinct() {
        assertNotEquals(SmsSentResultAwait.PREFIX_COMPOSE_SMS_SENT, SmsSentResultAwait.PREFIX_SMS_SENT)
        assertNotEquals(
            SmsSentResultAwait.sentAction("pkg", SmsSentResultAwait.PREFIX_COMPOSE_SMS_SENT, "n"),
            SmsSentResultAwait.sentAction("pkg", SmsSentResultAwait.PREFIX_SMS_SENT, "n"),
        )
    }

    @Test
    fun outcomeForMapsTheKnownResultCodes() {
        assertEquals(SmsSentOutcome.Timeout, SmsSentResultAwait.outcomeFor(null))
        assertEquals(SmsSentOutcome.Ok, SmsSentResultAwait.outcomeFor(Activity.RESULT_OK))
        assertEquals(SmsSentOutcome.RadioOff, SmsSentResultAwait.outcomeFor(SmsManager.RESULT_ERROR_RADIO_OFF))
        assertEquals(SmsSentOutcome.NoService, SmsSentResultAwait.outcomeFor(SmsManager.RESULT_ERROR_NO_SERVICE))
        assertEquals(SmsSentOutcome.NullPdu, SmsSentResultAwait.outcomeFor(SmsManager.RESULT_ERROR_NULL_PDU))
    }

    @Test
    fun outcomeForFallsBackToGenericFailureCarryingTheCode() {
        assertEquals(
            SmsSentOutcome.GenericFailure(SmsManager.RESULT_ERROR_GENERIC_FAILURE),
            SmsSentResultAwait.outcomeFor(SmsManager.RESULT_ERROR_GENERIC_FAILURE),
        )
        assertEquals(SmsSentOutcome.GenericFailure(99), SmsSentResultAwait.outcomeFor(99))
    }
}
