package com.andharness.ru.domain.calls


/**
 * The **application** layer of the call/SMS auto-handling rules: the pure decisions that turn the
 * stored [CallRules] plus a few real-world signals into "reply / don't reply" and "create a call-back
 * reminder / don't". Everything here is Android-free and unit-tested on the JVM; the Android layer
 * ([com.andharness.ru.data.intercept.CallSmsRuleApplier], the call-screening service and the SMS
 * receiver) only supplies the signals and performs the side effects.
 *
 * This is the missing half of [CallRuleEngine]: that object decides **calls**, this one decides
 * **SMS replies** and the **call-back reminder**, so the stored rules actually take effect.
 */

/** What the SMS path concluded for one inbound message. */
enum class SmsReplyDecision {
    /** Do nothing (disabled, nothing to react to, allowlisted, unsafe target or cooling down). */
    NONE,

    /** Send the configured auto-reply text to the sender. */
    REPLY,
}
