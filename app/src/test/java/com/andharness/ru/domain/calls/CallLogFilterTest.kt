package com.andharness.ru.domain.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [CallLogFilter]: each filter on its own, their AND combination, the default
 * (no filter) case, and the normalised `number` matching delegated to [CallLogNumberMatcher].
 */
class CallLogFilterTest {

    private val now = 1_700_000_000_000L
    private val oneDay = 24L * 60L * 60L * 1000L

    @Test
    fun noFiltersYieldsNoClause() {
        val selection = CallLogFilter.build(callType = null, days = null, nowMillis = now)

        assertNull(selection.clause)
        assertTrue(selection.args.isEmpty())
    }

    @Test
    fun typeFilterOnly() {
        val selection = CallLogFilter.build(callType = 3, days = null, nowMillis = now)

        assertEquals("type = ?", selection.clause)
        assertEquals(listOf("3"), selection.args)
    }

    @Test
    fun daysFilterOnlyUsesEpochWindow() {
        val selection = CallLogFilter.build(callType = null, days = 7, nowMillis = now)

        assertEquals("date >= ?", selection.clause)
        assertEquals(listOf((now - 7 * oneDay).toString()), selection.args)
    }

    @Test
    fun combinedFiltersAreAnded() {
        val selection = CallLogFilter.build(callType = 2, days = 3, nowMillis = now)

        assertEquals("type = ? AND date >= ?", selection.clause)
        assertEquals(listOf("2", (now - 3 * oneDay).toString()), selection.args)
    }

    @Test
    fun nonPositiveDaysIsNotAFilter() {
        assertNull(CallLogFilter.build(null, days = 0, nowMillis = now).clause)
        assertNull(CallLogFilter.build(null, days = -5, nowMillis = now).clause)
    }

    @Test
    fun typeNameNormalisesKnownValues() {
        assertEquals(CallLogFilter.TYPE_INCOMING, CallLogFilter.typeName("Incoming"))
        assertEquals(CallLogFilter.TYPE_OUTGOING, CallLogFilter.typeName(" OUTGOING "))
        assertEquals(CallLogFilter.TYPE_MISSED, CallLogFilter.typeName("missed"))
        assertEquals(CallLogFilter.TYPE_REJECTED, CallLogFilter.typeName("rejected"))
    }

    @Test
    fun typeNameRejectsBlankAndUnknown() {
        assertNull(CallLogFilter.typeName(null))
        assertNull(CallLogFilter.typeName(""))
        assertNull(CallLogFilter.typeName("voicemail"))
    }

    @Test
    fun blankNumberFilterMatchesEverything() {
        assertTrue(CallLogFilter.matchesNumber(null, "+79119617998"))
        assertTrue(CallLogFilter.matchesNumber("   ", "+79119617998"))
    }

    @Test
    fun numberFilterMatchesNormalisedForms() {
        assertTrue(CallLogFilter.matchesNumber("8 911 961 79 98", "+79119617998"))
        assertFalse(CallLogFilter.matchesNumber("+79119617998", "Private number"))
    }
}
