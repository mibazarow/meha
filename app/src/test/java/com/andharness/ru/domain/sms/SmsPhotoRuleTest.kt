package com.andharness.ru.domain.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the trusted-SMS photo report policy (no Android APIs): the photo-command
 * matcher, the trusted/eligibility/cooldown decision, the reply builder and the codec bounds.
 */
class SmsPhotoRuleTest {

    @Test
    fun `every listed photo command matches case-insensitively`() {
        val commands = listOf(
            "фото",
            "Сделай фото",
            "ЧТО НА ФОТО",
            "снимок",
            "photo",
            "Picture",
            "Take a photo please",
            "What do you see?",
        )
        commands.forEach { body ->
            assertTrue("должно совпасть: «$body»", SmsPhotoRuleEngine.matchesPhotoCommand(body))
        }
    }

    @Test
    fun `non-commands do not match`() {
        val nonCommands = listOf(
            "привет",
            "позвони маме",
            "как дела",
            "send money",
            "  ",
            "",
        )
        nonCommands.forEach { body ->
            assertFalse("не должно совпасть: «$body»", SmsPhotoRuleEngine.matchesPhotoCommand(body))
        }
        assertFalse(SmsPhotoRuleEngine.matchesPhotoCommand(null))
    }

    @Test
    fun `decide requires enabled trusted eligible matching and not cooling`() {
        val rule = SmsPhotoRule(enabled = true)
        assertEquals(
            SmsPhotoDecision.IGNORE,
            SmsPhotoRuleEngine.decide(SmsPhotoRule(enabled = false), true, true, "фото", false).decision,
        )
        assertEquals(
            SmsPhotoDecision.IGNORE,
            SmsPhotoRuleEngine.decide(rule, senderTrusted = false, true, "фото", false).decision,
        )
        assertEquals(
            SmsPhotoDecision.IGNORE,
            SmsPhotoRuleEngine.decide(rule, true, senderEligible = false, "фото", false).decision,
        )
        assertEquals(
            SmsPhotoDecision.IGNORE,
            SmsPhotoRuleEngine.decide(rule, true, true, "привет", false).decision,
        )
        assertEquals(
            SmsPhotoDecision.IGNORE,
            SmsPhotoRuleEngine.decide(rule, true, true, "фото", coolingDown = true).decision,
        )
        assertEquals(
            SmsPhotoDecision.REPORT,
            SmsPhotoRuleEngine.decide(rule, true, true, "фото", false).decision,
        )
    }

    @Test
    fun `sender eligibility rejects short service alphanumeric and emergency numbers`() {
        assertTrue(SmsPhotoRuleEngine.isEligibleSender("+7 911 961-79-98"))
        assertTrue(SmsPhotoRuleEngine.isEligibleSender("79119617998"))
        assertFalse(SmsPhotoRuleEngine.isEligibleSender("112"))
        assertFalse(SmsPhotoRuleEngine.isEligibleSender("911"))
        assertFalse(SmsPhotoRuleEngine.isEligibleSender("8005553535"))
        assertFalse(SmsPhotoRuleEngine.isEligibleSender("88005553535"))
        assertFalse(SmsPhotoRuleEngine.isEligibleSender("SBERBANK"))
        assertFalse(SmsPhotoRuleEngine.isEligibleSender("12345"))
        assertFalse(SmsPhotoRuleEngine.isEligibleSender(null))
    }

    @Test
    fun `cooldown respects the window and disables at zero or with clock skew`() {
        assertFalse(SmsPhotoCooldown.isCoolingDown(null, 10_000, 60))
        assertTrue(SmsPhotoCooldown.isCoolingDown(1_000, 2_000, 60))
        assertFalse(SmsPhotoCooldown.isCoolingDown(1_000, 1_000 + 60_000, 60))
        assertFalse(SmsPhotoCooldown.isCoolingDown(1_000, 2_000, 0))
        assertFalse(SmsPhotoCooldown.isCoolingDown(5_000, 1_000, 60))
    }

    @Test
    fun `reply text uses the prefix and never goes blank`() {
        assertEquals("Фото: кот", SmsPhotoRuleEngine.replyText("Фото: ", "кот"))
        assertEquals("Фото: не удалось получить описание", SmsPhotoRuleEngine.replyText("Фото:", "  "))
    }

    @Test
    fun `codec bounds the prefix cooldown and reply log`() {
        val longPrefix = "x".repeat(SmsPhotoRuleCodec.MAX_PREFIX_CHARS + 50)
        val state = SmsPhotoRuleState(
            rule = SmsPhotoRule(enabled = true, replyTextPrefix = longPrefix, cooldownSeconds = 999_999),
            replyLog = mapOf("+7 900 123-45-67" to 100L, "bad" to 0L),
        )
        val sanitized = SmsPhotoRuleCodec.sanitize(state)
        assertEquals(SmsPhotoRuleCodec.MAX_PREFIX_CHARS, sanitized.rule.replyTextPrefix.length)
        assertEquals(SmsPhotoRuleCodec.MAX_COOLDOWN_SECONDS, sanitized.rule.cooldownSeconds)
        assertEquals(mapOf("+79001234567" to 100L), sanitized.replyLog)
        assertTrue(SmsPhotoRuleCodec.decode("not json").rule == SmsPhotoRule())
    }
}
