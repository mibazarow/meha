package com.andharness.ru.domain.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitReasonCatalogTest {

    @Test
    fun everyDocumentedReasonCodeHasARealLabel() {
        val codes = 0..16
        for (code in codes) {
            val label = ExitReasonCatalog.label(code)
            assertFalse("code $code must not fall back to the unknown branch", label.startsWith("неизвестная причина"))
            assertTrue("label for code $code must not be blank", label.isNotBlank())
        }
    }

    @Test
    fun documentedCodeValuesMatchTheFramework() {
        // Values from https://developer.android.com/reference/android/app/ApplicationExitInfo#constants_1
        assertEquals(0, ExitReasonCatalog.REASON_UNKNOWN)
        assertEquals(1, ExitReasonCatalog.REASON_EXIT_SELF)
        assertEquals(2, ExitReasonCatalog.REASON_SIGNALED)
        assertEquals(3, ExitReasonCatalog.REASON_LOW_MEMORY)
        assertEquals(4, ExitReasonCatalog.REASON_CRASH)
        assertEquals(5, ExitReasonCatalog.REASON_CRASH_NATIVE)
        assertEquals(6, ExitReasonCatalog.REASON_ANR)
        assertEquals(7, ExitReasonCatalog.REASON_INITIALIZATION_FAILURE)
        assertEquals(8, ExitReasonCatalog.REASON_PERMISSION_CHANGE)
        assertEquals(9, ExitReasonCatalog.REASON_EXCESSIVE_RESOURCE_USAGE)
        assertEquals(10, ExitReasonCatalog.REASON_USER_REQUESTED)
        assertEquals(11, ExitReasonCatalog.REASON_USER_STOPPED)
        assertEquals(12, ExitReasonCatalog.REASON_DEPENDENCY_DIED)
        assertEquals(13, ExitReasonCatalog.REASON_OTHER)
        assertEquals(14, ExitReasonCatalog.REASON_FREEZER)
        assertEquals(15, ExitReasonCatalog.REASON_PACKAGE_STATE_CHANGE)
        assertEquals(16, ExitReasonCatalog.REASON_PACKAGE_UPDATED)
    }

    @Test
    fun crashAndAnrAreFailures() {
        assertTrue(ExitReasonCatalog.isFailure(ExitReasonCatalog.REASON_CRASH))
        assertTrue(ExitReasonCatalog.isFailure(ExitReasonCatalog.REASON_CRASH_NATIVE))
        assertTrue(ExitReasonCatalog.isFailure(ExitReasonCatalog.REASON_ANR))
        assertTrue(ExitReasonCatalog.isFailure(ExitReasonCatalog.REASON_LOW_MEMORY))
        assertTrue(ExitReasonCatalog.isFailure(ExitReasonCatalog.REASON_INITIALIZATION_FAILURE))
        assertTrue(ExitReasonCatalog.isFailure(ExitReasonCatalog.REASON_EXCESSIVE_RESOURCE_USAGE))
        assertEquals("сбой", ExitReasonCatalog.category(ExitReasonCatalog.REASON_CRASH))
    }

    @Test
    fun deliberateExitsAreNotFailures() {
        assertFalse(ExitReasonCatalog.isFailure(ExitReasonCatalog.REASON_EXIT_SELF))
        assertFalse(ExitReasonCatalog.isFailure(ExitReasonCatalog.REASON_USER_REQUESTED))
        assertFalse(ExitReasonCatalog.isFailure(ExitReasonCatalog.REASON_USER_STOPPED))
        assertFalse(ExitReasonCatalog.isFailure(ExitReasonCatalog.REASON_OTHER))
        assertFalse(ExitReasonCatalog.isFailure(ExitReasonCatalog.REASON_FREEZER))
        assertTrue(ExitReasonCatalog.isUserInitiated(ExitReasonCatalog.REASON_USER_REQUESTED))
        assertTrue(ExitReasonCatalog.isUserInitiated(ExitReasonCatalog.REASON_USER_STOPPED))
        assertTrue(ExitReasonCatalog.isUserInitiated(ExitReasonCatalog.REASON_EXIT_SELF))
        assertEquals("по воле пользователя", ExitReasonCatalog.category(ExitReasonCatalog.REASON_USER_STOPPED))
    }

    @Test
    fun systemHousekeepingIsNeitherFailureNorUserInitiated() {
        assertFalse(ExitReasonCatalog.isFailure(ExitReasonCatalog.REASON_SIGNALED))
        assertFalse(ExitReasonCatalog.isUserInitiated(ExitReasonCatalog.REASON_SIGNALED))
        assertFalse(ExitReasonCatalog.isFailure(ExitReasonCatalog.REASON_PACKAGE_UPDATED))
        assertEquals("системное событие", ExitReasonCatalog.category(ExitReasonCatalog.REASON_PACKAGE_UPDATED))
    }

    @Test
    fun unknownCodesAreReportedHonestly() {
        val label = ExitReasonCatalog.label(999)
        assertTrue(label, label.contains("999"))
        assertFalse(ExitReasonCatalog.isFailure(999))
        assertEquals("системное событие", ExitReasonCatalog.category(999))
    }
}
