package com.andharness.ru.data.tools.sms

import android.provider.Telephony
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [SmsDeleteSelection]. Pure Kotlin only, no Android runtime: `Telephony.Sms.*` column
 * names are compile-time String constants, so they are inlined.
 * Reference: https://developer.android.com/reference/android/provider/Telephony.Sms
 */
class SmsDeleteSelectionTest {

    @Test
    fun scopeAllYieldsNullSelection() {
        val result = SmsDeleteSelection.build(scope = "all", threadId = null, number = null)
        assertTrue(result.isSuccess)
        assertNull(result.getOrThrow().first)
        assertNull(result.getOrThrow().second)
    }

    @Test
    fun scopeThreadYieldsThreadIdSelection() {
        val result = SmsDeleteSelection.build(scope = "thread", threadId = "42", number = null)
        assertTrue(result.isSuccess)
        val (selection, args) = result.getOrThrow()
        assertEquals("${Telephony.Sms.THREAD_ID} = ?", selection)
        assertEquals(listOf("42"), args?.toList())
    }

    @Test
    fun scopeThreadTrimsTheId() {
        val result = SmsDeleteSelection.build(scope = "thread", threadId = "  7 ", number = null)
        assertEquals(listOf("7"), result.getOrThrow().second?.toList())
    }

    @Test
    fun scopeThreadRejectsNonNumericId() {
        val result = SmsDeleteSelection.build(scope = "thread", threadId = "abc", number = null)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("числом"))
    }

    @Test
    fun scopeThreadRejectsMissingId() {
        assertTrue(
            SmsDeleteSelection.build(scope = "thread", threadId = null, number = null).isFailure,
        )
        assertTrue(
            SmsDeleteSelection.build(scope = "thread", threadId = "   ", number = null).isFailure,
        )
    }

    @Test
    fun scopeNumberYieldsAddressSelection() {
        val result = SmsDeleteSelection.build(scope = "number", threadId = null, number = " +79991234567 ")
        assertTrue(result.isSuccess)
        val (selection, args) = result.getOrThrow()
        assertEquals("${Telephony.Sms.ADDRESS} = ?", selection)
        assertEquals(listOf("+79991234567"), args?.toList())
    }

    @Test
    fun scopeNumberRejectsBlankNumber() {
        assertTrue(
            SmsDeleteSelection.build(scope = "number", threadId = null, number = "  ").isFailure,
        )
        assertTrue(
            SmsDeleteSelection.build(scope = "number", threadId = null, number = null).isFailure,
        )
    }

    @Test
    fun unknownScopeFails() {
        assertTrue(
            SmsDeleteSelection.build(scope = "everything", threadId = null, number = null).isFailure,
        )
    }

    @Test
    fun missingScopeFails() {
        assertTrue(
            SmsDeleteSelection.build(scope = null, threadId = null, number = null).isFailure,
        )
        assertTrue(
            SmsDeleteSelection.build(scope = "  ", threadId = null, number = null).isFailure,
        )
    }
}
