package com.andharness.ru.domain.calls


/** The decision plus an honest, log-readable reason (Russian, shown in logs / notes). */
data class SmsReplyPlan(val decision: SmsReplyDecision, val reason: String)
