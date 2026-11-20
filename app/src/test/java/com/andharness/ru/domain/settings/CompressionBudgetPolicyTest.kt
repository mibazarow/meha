package com.andharness.ru.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The budget rule must be honest: the compression budget is ALWAYS 80 % of the model context (there
 * is no user-facing budget setting), so a model-context change always updates the budget.
 */
class CompressionBudgetPolicyTest {

    @Test
    fun deriveIsEightyPercentOfAValidContext() {
        assertEquals(96_000L, CompressionBudgetPolicy.derive(120_000L))
        assertEquals(8L, CompressionBudgetPolicy.derive(10L))
    }

    @Test
    fun deriveIsNullWhenContextIsUnknownOrNonPositive() {
        assertNull(CompressionBudgetPolicy.derive(null))
        assertNull(CompressionBudgetPolicy.derive(0L))
        assertNull(CompressionBudgetPolicy.derive(-5L))
    }

    @Test
    fun percentConstantIsEighty() {
        assertEquals(80L, CompressionBudgetPolicy.PERCENT)
    }
}
