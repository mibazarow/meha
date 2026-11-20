package com.andharness.ru.domain.calls


/**
 * Pure policy for the automatic SMS reply (checklist §1). All functions are side-effect free.
 *
 * Precedence (most specific first), mirroring the safety model of [CallRuleEngine]:
 * 1. rules disabled → NONE (strict opt-in no-op);
 * 2. blank reply text → NONE (nothing to send);
 * 3. body is an SMS-photo command → NONE (that is a separate feature and must not be answered here);
 * 4. sender allowlisted → NONE (the allowlist always passes and is never auto-replied to);
 * 5. reply condition: [CallRules.autoReplySms] for an **unknown** sender, or
 *    [CallRules.autoReplyWhenBusy] while the device is **busy** (in a call or DND/quiet). A known
 *    contact is replied to only via the busy flag;
 * 6. unsafe target (short code/service/emergency) → NONE ([CallRuleEngine.isReplyEligible]);
 * 7. per-number cooldown active → NONE (default 6 h, see [AutoReplyCooldown]);
 * 8. otherwise → REPLY.
 */
object SmsAutoReplyPolicy {

    /**
     * The signals the Android layer supplies. Kept as a plain data class so the whole decision is
     * reproducible and testable without Android.
     *
     * `busy` is `inCall || dndActive`: "the user cannot respond right now". Reading it is documented
     * honestly in [com.andharness.ru.data.intercept.BusySignal] — an active/ringing call (needs
     * `READ_PHONE_STATE`) or an active DND/quiet filter.
     */
    data class Signals(
        val allowlisted: Boolean,
        val numberKnown: Boolean,
        val inCall: Boolean,
        val dndActive: Boolean,
        val replyEligible: Boolean,
        val coolingDown: Boolean,
        val photoCommand: Boolean,
    ) {
        /** `true` when the user is unavailable: in a call or in DND/quiet mode. */
        val busy: Boolean get() = inCall || dndActive
    }

    /** The full decision for one inbound SMS. Never throws. */
    fun decide(rules: CallRules, signals: Signals): SmsReplyPlan {
        if (!rules.enabled) return plan(false, "авто-обработка выключена (enabled=false)")
        if (rules.autoReplyText.isBlank()) return plan(false, "текст авто-ответа пуст")
        if (signals.photoCommand) return plan(false, "команда фото — обрабатывается отдельным правилом")

        if (signals.allowlisted) return plan(false, "номер в белом списке — авто-ответ не отправляется")

        val replyUnknown = rules.autoReplySms && !signals.numberKnown
        val replyBusy = rules.autoReplyWhenBusy && signals.busy
        if (!replyUnknown && !replyBusy) {
            return plan(
                false,
                if (signals.numberKnown) {
                    "известный контакт, авто-ответ незнакомым выключен и режим занятости не активен"
                } else {
                    "условия авто-ответа не выполнены"
                },
            )
        }

        if (!signals.replyEligible) return plan(false, "служебный/короткий/экстренный номер — ответ запрещён")
        if (signals.coolingDown) return plan(false, "кулдаун на номер ещё не истёк")

        val reason = when {
            replyUnknown -> "незнакомый номер, включён авто-ответ (autoReplySms)"
            signals.inCall -> "идёт звонок — ответ по флагу autoReplyWhenBusy"
            else -> "тихий режим (DND) — ответ по флагу autoReplyWhenBusy"
        }
        return plan(true, reason)
    }

    private fun plan(reply: Boolean, reason: String): SmsReplyPlan =
        SmsReplyPlan(if (reply) SmsReplyDecision.REPLY else SmsReplyDecision.NONE, reason)
}
