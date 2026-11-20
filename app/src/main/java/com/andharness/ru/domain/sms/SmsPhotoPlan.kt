package com.andharness.ru.domain.sms


/** The decision plus an honest, log-readable reason. */
data class SmsPhotoPlan(val decision: SmsPhotoDecision, val reason: String)
