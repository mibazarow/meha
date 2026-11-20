package com.andharness.ru.domain.calls

import com.andharness.ru.data.calls.CallRulesStore
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the applied half of the call/SMS rules: the pure [SmsAutoReplyPolicy] and
 * [CallBackReminderPolicy], plus the cooldown-continuity contract of [CallRulesStore]. Everything is
 * Android-free, so the SMS-reply decision, the "busy" signal, the call-back eligibility and the
 * default-off no-op are exercised without an Android runtime.
 */
class CallRuleApplicationTest {

    private val dirs = mutableListOf<File>()

    private fun newDir(): File =
        Files.createTempDirectory("call-rule-application-test").toFile().also { dirs += it }

    @After
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    private fun signals(
        allowlisted: Boolean = false,
        numberKnown: Boolean = false,
        inCall: Boolean = false,
        dndActive: Boolean = false,
        replyEligible: Boolean = true,
        coolingDown: Boolean = false,
        photoCommand: Boolean = false,
    ) = SmsAutoReplyPolicy.Signals(
        allowlisted = allowlisted,
        numberKnown = numberKnown,
        inCall = inCall,
        dndActive = dndActive,
        replyEligible = replyEligible,
        coolingDown = coolingDown,
        photoCommand = photoCommand,
    )

    // ---- default OFF is a strict no-op ----

    @Test
    fun disabledRulesNeverReplyEvenWithEverySignalOn() {
        val rules = CallRules(enabled = false, autoReplySms = true, autoReplyWhenBusy = true)
        val plan = SmsAutoReplyPolicy.decide(rules, signals(inCall = true, dndActive = true))
        assertEquals(SmsReplyDecision.NONE, plan.decision)
    }

    @Test
    fun enabledButNoFlagNeverReplies() {
        val rules = CallRules(enabled = true)
        assertEquals(SmsReplyDecision.NONE, SmsAutoReplyPolicy.decide(rules, signals()).decision)
    }

    // ---- unknown-sender auto-reply ----

    @Test
    fun autoReplySmsRepliesToUnknownEligibleSender() {
        val rules = CallRules(enabled = true, autoReplySms = true)
        assertEquals(SmsReplyDecision.REPLY, SmsAutoReplyPolicy.decide(rules, signals()).decision)
    }

    @Test
    fun autoReplySmsNeverRepliesToKnownOrAllowlisted() {
        val rules = CallRules(enabled = true, autoReplySms = true)
        assertEquals(
            SmsReplyDecision.NONE,
            SmsAutoReplyPolicy.decide(rules, signals(numberKnown = true)).decision,
        )
        assertEquals(
            SmsReplyDecision.NONE,
            SmsAutoReplyPolicy.decide(rules, signals(allowlisted = true)).decision,
        )
    }

    // ---- busy-signal decision ----

    @Test
    fun busyReplyFiresDuringCallOrDndButNotWhenIdle() {
        val rules = CallRules(enabled = true, autoReplyWhenBusy = true)
        assertEquals(SmsReplyDecision.NONE, SmsAutoReplyPolicy.decide(rules, signals()).decision)
        assertEquals(
            SmsReplyDecision.REPLY,
            SmsAutoReplyPolicy.decide(rules, signals(inCall = true)).decision,
        )
        assertEquals(
            SmsReplyDecision.REPLY,
            SmsAutoReplyPolicy.decide(rules, signals(dndActive = true)).decision,
        )
    }

    @Test
    fun busyFlagRepliesEvenToAKnownContact() {
        val rules = CallRules(enabled = true, autoReplyWhenBusy = true)
        val plan = SmsAutoReplyPolicy.decide(rules, signals(numberKnown = true, inCall = true))
        assertEquals(SmsReplyDecision.REPLY, plan.decision)
    }

    @Test
    fun busySignalIsTheOrOfInCallAndDnd() {
        assertTrue(signals(inCall = true).busy)
        assertTrue(signals(dndActive = true).busy)
        assertFalse(signals().busy)
    }

    // ---- safety guards ----

    @Test
    fun photoCommandIsNeverAutoRepliedTo() {
        val rules = CallRules(enabled = true, autoReplySms = true, autoReplyWhenBusy = true)
        val plan = SmsAutoReplyPolicy.decide(rules, signals(photoCommand = true, inCall = true))
        assertEquals(SmsReplyDecision.NONE, plan.decision)
    }

    @Test
    fun ineligibleTargetIsNeverAutoRepliedTo() {
        val rules = CallRules(enabled = true, autoReplySms = true)
        assertEquals(
            SmsReplyDecision.NONE,
            SmsAutoReplyPolicy.decide(rules, signals(replyEligible = false)).decision,
        )
    }

    @Test
    fun coolingDownSuppressesTheReply() {
        val rules = CallRules(enabled = true, autoReplySms = true)
        assertEquals(
            SmsReplyDecision.NONE,
            SmsAutoReplyPolicy.decide(rules, signals(coolingDown = true)).decision,
        )
    }

    @Test
    fun blankReplyTextDisablesTheReply() {
        val rules = CallRules(enabled = true, autoReplySms = true, autoReplyText = "   ")
        assertEquals(SmsReplyDecision.NONE, SmsAutoReplyPolicy.decide(rules, signals()).decision)
    }

    // ---- call-back reminder eligibility ----

    @Test
    fun callBackReminderRequiresEnabledFlagKnownAndRejection() {
        val on = CallRules(enabled = true, callBackReminder = true)
        assertTrue(CallBackReminderPolicy.shouldCreate(on, contactKnown = true, rejectedOrMissed = true))
        assertFalse(CallBackReminderPolicy.shouldCreate(on, contactKnown = false, rejectedOrMissed = true))
        assertFalse(CallBackReminderPolicy.shouldCreate(on, contactKnown = true, rejectedOrMissed = false))
        assertFalse(
            CallBackReminderPolicy.shouldCreate(
                CallRules(enabled = false, callBackReminder = true),
                contactKnown = true,
                rejectedOrMissed = true,
            ),
        )
        assertFalse(
            CallBackReminderPolicy.shouldCreate(
                CallRules(enabled = true, callBackReminder = false),
                contactKnown = true,
                rejectedOrMissed = true,
            ),
        )
    }

    @Test
    fun callBackReminderTextAndTime() {
        assertEquals("Перезвонить: Анна", CallBackReminderPolicy.reminderText(" Анна ", "+79001234567"))
        assertEquals("Перезвонить: +79001234567", CallBackReminderPolicy.reminderText(null, "+79001234567"))
        assertEquals(1_000L + CallBackReminderPolicy.DEFAULT_DELAY_MS, CallBackReminderPolicy.reminderAtMs(1_000L))
        assertEquals(1_000L, CallBackReminderPolicy.reminderAtMs(1_000L, delayMs = -5L))
    }

    // ---- cooldown continuity through the bounded store ----

    @Test
    fun storeCooldownRecordsOnlyConfirmedRepliesAndExpiresAfterSixHours() {
        val store = CallRulesStore(newDir())
        val t0 = 1_700_000_000_000L
        assertFalse(store.isAutoReplyCoolingDown("+79001234567", t0))

        store.noteAutoReply("+7 900 123-45-67", t0)
        // The stored key is normalized, so a differently formatted lookup still hits the same cooldown.
        assertTrue(store.isAutoReplyCoolingDown("+79001234567", t0))
        assertTrue(
            store.isAutoReplyCoolingDown(
                "+79001234567",
                t0 + AutoReplyCooldown.DEFAULT_COOLDOWN_MS - 1,
            ),
        )
        assertFalse(
            store.isAutoReplyCoolingDown(
                "+79001234567",
                t0 + AutoReplyCooldown.DEFAULT_COOLDOWN_MS,
            ),
        )
    }
}
