package com.andharness.ru.domain.calls

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [CallLogNumberMatcher]: the pure, Android-free phone matching used by
 * `call_log_delete` so that `8 911 ...`, `+7 911 ...` and separator-laden input all resolve to the
 * same subscriber.
 */
class CallLogNumberMatcherTest {

    @Test
    fun spacedEightPrefixedInputMatchesPlusSevenStored() {
        assertTrue(CallLogNumberMatcher.matches("8 911 961 79 98", "+79119617998"))
    }

    @Test
    fun formattedPlusSevenInputMatchesEightPrefixedStored() {
        assertTrue(CallLogNumberMatcher.matches("+7 (911) 961-79-98", "89119617998"))
    }

    @Test
    fun differentLastDigitsDoNotMatch() {
        assertFalse(CallLogNumberMatcher.matches("+79119617998", "+79119617999"))
    }

    @Test
    fun blankStoredDoesNotMatch() {
        assertFalse(CallLogNumberMatcher.matches("+79119617998", "   "))
    }

    @Test
    fun nullStoredDoesNotMatch() {
        assertFalse(CallLogNumberMatcher.matches("+79119617998", null))
    }

    @Test
    fun formattingOnlyInputDoesNotMatch() {
        assertFalse(CallLogNumberMatcher.matches("()+ -()", "+79119617998"))
    }

    @Test
    fun unparseableStoredDoesNotMatch() {
        assertFalse(CallLogNumberMatcher.matches("+79119617998", "Private number"))
    }
}
