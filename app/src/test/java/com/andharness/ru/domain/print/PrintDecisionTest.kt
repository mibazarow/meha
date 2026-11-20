package com.andharness.ru.domain.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [PrintDecision] (BUG-023): `print_file` may report success only when the system
 * print dialog was actually shown from an Activity, and it must state plainly that the file is not
 * printed yet. Pure logic, no Android runtime needed.
 */
class PrintDecisionTest {

    @Test
    fun dialogShownIsSuccessAndSaysTheFileIsNotPrintedYet() {
        val result = PrintDecision.from(
            outcome = PrintUiOutcome.DIALOG_SHOWN,
            fileName = "hello.txt",
            mime = "text/plain",
        )
        assertTrue(result.success)
        assertEquals(PrintDecision.dialogShown("hello.txt", "text/plain"), result)
    }

    @Test
    fun failedKeepsTheBridgeFailureMessageHonest() {
        val noUi = "Системный диалог можно показать только когда приложение на переднем плане."
        val result = PrintDecision.from(
            outcome = PrintUiOutcome.FAILED,
            fileName = "hello.txt",
            mime = "text/plain",
            failureMessage = noUi,
        )
        assertFalse(result.success)
        assertEquals(noUi, result.message)
    }

    @Test
    fun failedWithoutAMessageFallsBackToNoDialog() {
        val result = PrintDecision.from(
            outcome = PrintUiOutcome.FAILED,
            fileName = "hello.txt",
            mime = "text/plain",
        )
        assertFalse(result.success)
        assertEquals(PrintDecision.NO_DIALOG, result.message)
    }

    @Test
    fun cancelledIsFailure() {
        val result = PrintDecision.from(
            outcome = PrintUiOutcome.CANCELLED,
            fileName = "hello.txt",
            mime = "text/plain",
        )
        assertFalse(result.success)
        assertEquals(PrintDecision.CANCELLED, result.message)
    }

    @Test
    fun unexpectedIsFailure() {
        val result = PrintDecision.from(
            outcome = PrintUiOutcome.UNEXPECTED,
            fileName = "hello.txt",
            mime = "text/plain",
        )
        assertFalse(result.success)
        assertEquals(PrintDecision.UNEXPECTED, result.message)
    }
}
