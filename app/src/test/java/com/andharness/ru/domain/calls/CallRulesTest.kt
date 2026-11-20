package com.andharness.ru.domain.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Android-free call/SMS policy [CallRuleEngine], the [AutoReplyCooldown], the
 * [CallRulesCodec] and the [CallRuleTokens] vocabulary. Everything here is pure Kotlin, so the whole
 * decision precedence and the safety predicates are exercised without an Android runtime.
 */
class CallRulesTest {

    private val enabled = CallRules(enabled = true)

    // ---- decideCall precedence ----

    @Test
    fun disabledRulesAlwaysAllowEvenBlockedNumbers() {
        val rules = CallRules(enabled = false, blockedNumbers = listOf("+79001234567"))
        assertEquals(CallDecision.ALLOW, CallRuleEngine.decideCall("+79001234567", false, rules))
    }

    @Test
    fun allowlistWinsOverBlocklist() {
        val rules = enabled.copy(
            allowNumbers = listOf("+79001234567"),
            blockedNumbers = listOf("+79001234567"),
        )
        assertEquals(CallDecision.ALLOW, CallRuleEngine.decideCall("+7 900 123-45-67", false, rules))
    }

    @Test
    fun explicitlyBlockedNumberIsRejected() {
        val rules = enabled.copy(blockedNumbers = listOf("+79001234567"))
        assertEquals(CallDecision.REJECT, CallRuleEngine.decideCall("+79001234567", true, rules))
    }

    @Test
    fun blockUnknownRejectsUnknownButNotKnownContact() {
        val rules = enabled.copy(blockUnknown = true)
        assertEquals(CallDecision.REJECT, CallRuleEngine.decideCall("+79001234567", false, rules))
        assertEquals(CallDecision.ALLOW, CallRuleEngine.decideCall("+79001234567", true, rules))
    }

    @Test
    fun unknownCallerWithAutoReplyGetsAllowAndReply() {
        val rules = enabled.copy(autoReplySms = true)
        assertEquals(
            CallDecision.ALLOW_AND_REPLY,
            CallRuleEngine.decideCall("+79001234567", false, rules),
        )
    }

    @Test
    fun knownContactIsNeverAutoRepliedTo() {
        val rules = enabled.copy(autoReplySms = true)
        assertEquals(CallDecision.ALLOW, CallRuleEngine.decideCall("+79001234567", true, rules))
    }

    @Test
    fun shortCodeIsNotReplyEligibleSoCallIsOnlyAllowed() {
        val rules = enabled.copy(autoReplySms = true)
        assertEquals(CallDecision.ALLOW, CallRuleEngine.decideCall("12345", false, rules))
    }

    @Test
    fun blankAutoReplyTextDisablesTheReply() {
        val rules = enabled.copy(autoReplySms = true, autoReplyText = "   ")
        assertEquals(CallDecision.ALLOW, CallRuleEngine.decideCall("+79001234567", false, rules))
    }

    @Test
    fun blankNumberIsTreatedAsUnknownNonAllowlisted() {
        val rules = enabled.copy(blockUnknown = true)
        assertEquals(CallDecision.REJECT, CallRuleEngine.decideCall(null, false, rules))
        assertEquals(CallDecision.REJECT, CallRuleEngine.decideCall("   ", false, rules))
    }

    // ---- shouldAutoReply / shouldAutoReplyWhenBusy ----

    @Test
    fun shouldAutoReplyRequiresEnabledAndUnknown() {
        assertFalse(CallRuleEngine.shouldAutoReply(false, CallRules(autoReplySms = true)))
        assertFalse(CallRuleEngine.shouldAutoReply(true, enabled.copy(autoReplySms = true)))
        assertTrue(CallRuleEngine.shouldAutoReply(false, enabled.copy(autoReplySms = true)))
    }

    @Test
    fun shouldAutoReplyWhenBusyRequiresEnabledAndInCall() {
        assertFalse(CallRuleEngine.shouldAutoReplyWhenBusy(true, enabled))
        assertFalse(CallRuleEngine.shouldAutoReplyWhenBusy(false, enabled.copy(autoReplyWhenBusy = true)))
        assertTrue(
            CallRuleEngine.shouldAutoReplyWhenBusy(
                true,
                enabled.copy(autoReplyWhenBusy = true),
            ),
        )
    }

    // ---- isReplyEligible ----

    @Test
    fun replyEligibleRejectsUnsafeTargets() {
        assertFalse(CallRuleEngine.isReplyEligible(null))
        assertFalse(CallRuleEngine.isReplyEligible(""))
        assertFalse(CallRuleEngine.isReplyEligible("BANK"))
        assertFalse(CallRuleEngine.isReplyEligible("12345"))
        assertFalse(CallRuleEngine.isReplyEligible("112"))
        assertFalse(CallRuleEngine.isReplyEligible("+911"))
        assertFalse(CallRuleEngine.isReplyEligible("88001234567"))
        assertFalse(CallRuleEngine.isReplyEligible("+8001234567"))
    }

    @Test
    fun replyEligibleAcceptsRealNumbers() {
        assertTrue(CallRuleEngine.isReplyEligible("+79001234567"))
        assertTrue(CallRuleEngine.isReplyEligible("8 (900) 123-45-67"))
    }

    // ---- normalization / lists ----

    @Test
    fun normalizeKeepsDigitsAndLeadingPlus() {
        assertNull(CallRuleEngine.normalizeNumber(null))
        assertNull(CallRuleEngine.normalizeNumber("   "))
        assertNull(CallRuleEngine.normalizeNumber("---"))
        assertEquals("+79001234567", CallRuleEngine.normalizeNumber("+7 (900) 123-45-67"))
        assertEquals("79001234567", CallRuleEngine.normalizeNumber(" 7 900 123 45 67 "))
        assertEquals("bank", CallRuleEngine.normalizeNumber("BANK"))
    }

    @Test
    fun parseNumberListSplitsDedupesAndNormalizes() {
        val parsed = CallRuleEngine.parseNumberList("+79001234567, 79001234567; +79007654321\n+79001234567")
        assertEquals(listOf("+79001234567", "79001234567", "+79007654321"), parsed)
        assertTrue(CallRuleEngine.parseNumberList(null).isEmpty())
        assertTrue(CallRuleEngine.parseNumberList("  ").isEmpty())
    }

    @Test
    fun formatNumberListJoinsNormalizedDistinct() {
        assertEquals("+79001234567, +79007654321", CallRuleEngine.formatNumberList(listOf("+7 900 123 45 67", "+79001234567", "+79007654321")))
        assertEquals("", CallRuleEngine.formatNumberList(emptyList()))
    }

    // ---- cooldown ----

    @Test
    fun cooldownSuppressesUntilWindowElapses() {
        assertFalse(AutoReplyCooldown.isCoolingDown(null, 1_000L))
        assertTrue(AutoReplyCooldown.isCoolingDown(1_000L, 1_000L))
        assertTrue(AutoReplyCooldown.isCoolingDown(1_000L, 1_000L + AutoReplyCooldown.DEFAULT_COOLDOWN_MS - 1))
        assertFalse(AutoReplyCooldown.isCoolingDown(1_000L, 1_000L + AutoReplyCooldown.DEFAULT_COOLDOWN_MS))
        assertFalse(AutoReplyCooldown.isCoolingDown(1_000L, 0L))
        assertFalse(AutoReplyCooldown.isCoolingDown(1_000L, 2_000L, cooldownMs = 0L))
    }

    @Test
    fun nextAllowedAtAddsCooldown() {
        assertEquals(1_000L + AutoReplyCooldown.DEFAULT_COOLDOWN_MS, AutoReplyCooldown.nextAllowedAt(1_000L))
        assertEquals(1_500L, AutoReplyCooldown.nextAllowedAt(1_000L, cooldownMs = 500L))
    }

    // ---- codec ----

    @Test
    fun encodeDecodeRoundTrips() {
        val state = CallRulesState(
            rules = CallRules(enabled = true, blockUnknown = true, autoReplyText = "привет"),
            autoReplyLog = mapOf("+79001234567" to 42L),
        )
        assertEquals(state, CallRulesCodec.decode(CallRulesCodec.encode(state)))
    }

    @Test
    fun corruptBlobDegradesToOptInDefaults() {
        assertEquals(CallRulesState(), CallRulesCodec.decode("{ not json"))
        assertEquals(CallRulesState(), CallRulesCodec.decode(null))
        assertEquals(CallRulesState(), CallRulesCodec.decode(""))
    }

    @Test
    fun sanitizeBoundsNumbersReplyTextAndLog() {
        val raw = CallRulesState(
            rules = CallRules(
                enabled = true,
                blockedNumbers = (0..CallRulesCodec.MAX_NUMBERS + 10).map { "+7900123${it.toString().padStart(4, '0')}" },
                allowNumbers = listOf("+79007654321", "+7 900 765-43-21", "---"),
                autoReplyText = "  " + "x".repeat(CallRulesCodec.MAX_REPLY_CHARS + 50) + "  ",
            ),
            autoReplyLog = (1..CallRulesCodec.MAX_REPLY_LOG + 20).associate { "+7900${it.toString().padStart(6, '0')}" to it.toLong() } + mapOf("bad" to 0L),
        )
        val sanitized = CallRulesCodec.sanitize(raw)
        assertEquals(CallRulesCodec.MAX_NUMBERS, sanitized.rules.blockedNumbers.size)
        assertEquals(listOf("+79007654321"), sanitized.rules.allowNumbers)
        assertEquals(CallRulesCodec.MAX_REPLY_CHARS, sanitized.rules.autoReplyText.length)
        assertFalse(sanitized.rules.autoReplyText.endsWith(" "))
        assertEquals(CallRulesCodec.MAX_REPLY_LOG, sanitized.autoReplyLog.size)
        assertTrue(sanitized.autoReplyLog.containsKey("+7900000220"))
    }

    // ---- tokens ----

    @Test
    fun booleanTokensAcceptRuAndEnAliases() {
        for (token in listOf("true", "1", "on", "yes", "да", "вкл", "включить", "включено")) {
            assertEquals(true, CallRuleTokens.bool(token))
        }
        for (token in listOf("false", "0", "off", "no", "нет", "выкл", "выключить", "выключено")) {
            assertEquals(false, CallRuleTokens.bool(token))
        }
        assertNull(CallRuleTokens.bool(null))
        assertNull(CallRuleTokens.bool("  "))
        assertNull(CallRuleTokens.bool("maybe"))
    }
}
