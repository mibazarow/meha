package com.andharness.ru.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM checks of the actionable hints attached to provider failures. */
class LlmErrorHintsTest {

    @Test
    fun unauthorizedSuggestsCheckingTheKey() {
        assertEquals(LlmErrorHints.KEY_HINT, LlmErrorHints.forHttpStatus(401, ""))
        assertEquals(LlmErrorHints.KEY_HINT, LlmErrorHints.forHttpStatus(403, ""))
    }

    @Test
    fun paymentRequiredSuggestsCheckingTheBalance() {
        assertEquals(LlmErrorHints.BALANCE_HINT, LlmErrorHints.forHttpStatus(402, ""))
    }

    @Test
    fun rateLimitSuggestsCheckingLimits() {
        assertEquals(LlmErrorHints.LIMIT_HINT, LlmErrorHints.forHttpStatus(429, ""))
    }

    @Test
    fun serverErrorSuggestsProviderAvailability() {
        assertEquals(LlmErrorHints.PROVIDER_HINT, LlmErrorHints.forHttpStatus(500, ""))
        assertEquals(LlmErrorHints.PROVIDER_HINT, LlmErrorHints.forHttpStatus(503, ""))
    }

    @Test
    fun billingWordingInA400SuggestsTheBalance() {
        listOf(
            "Insufficient balance",
            "You exceeded your current quota",
            "no credit left",
            "billing hard limit reached",
            "Недостаточно средств на балансе",
        ).forEach { body ->
            assertEquals(
                "body should be recognised as billing: $body",
                LlmErrorHints.BALANCE_HINT,
                LlmErrorHints.forHttpStatus(400, body)
            )
        }
    }

    @Test
    fun unrelatedValidationErrorsStayUnannotated() {
        assertNull(LlmErrorHints.forHttpStatus(400, "messages: field required"))
        assertNull(LlmErrorHints.forHttpStatus(404, "not found"))
        assertNull(LlmErrorHints.forHttpStatus(422, "invalid model"))
    }

    @Test
    fun hintIsAppendedAsItsOwnParagraph() {
        assertEquals(
            "Ошибка API (401): bad key\n\n${LlmErrorHints.KEY_HINT}",
            LlmErrorHints.withHint("Ошибка API (401): bad key", LlmErrorHints.KEY_HINT)
        )
        assertEquals("plain", LlmErrorHints.withHint("plain", null))
        assertEquals("plain", LlmErrorHints.withHint("plain", "  "))
    }

    @Test
    fun actionableConstantsAreNotEmpty() {
        listOf(
            LlmErrorHints.KEY_HINT,
            LlmErrorHints.BALANCE_HINT,
            LlmErrorHints.LIMIT_HINT,
            LlmErrorHints.PROVIDER_HINT,
            LlmErrorHints.TIMEOUT_HINT,
            LlmErrorHints.CONNECTION_HINT,
        ).forEach { assertTrue(it.isNotBlank()) }
    }
}
