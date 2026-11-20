package com.andharness.ru.domain.sms


/**
 * Per-number cooldown for the photo report. Unlike the call auto-reply (hours), the default here is
 * a short 60 s so a trusted person can request several photos in a row while a duplicated broadcast
 * still cannot cause a loop.
 */
object SmsPhotoCooldown {

    /**
     * `true` when a reply to a number whose last automatic reply was [lastAtMs] must be suppressed at
     * [nowMs]. A `null` last-reply (never replied) is never cooling down; [cooldownSeconds] `<= 0`
     * disables the cooldown. A clock going backwards is treated as "not cooling down" so a reply is
     * never permanently blocked by wall-clock skew.
     */
    fun isCoolingDown(lastAtMs: Long?, nowMs: Long, cooldownSeconds: Int): Boolean {
        if (lastAtMs == null || cooldownSeconds <= 0) return false
        val elapsed = nowMs - lastAtMs
        return elapsed in 0 until cooldownSeconds * 1000L
    }
}
