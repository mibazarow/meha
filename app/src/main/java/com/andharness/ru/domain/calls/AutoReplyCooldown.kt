package com.andharness.ru.domain.calls


/**
 * The auto-reply cooldown. At most one automatic reply per number per [DEFAULT_COOLDOWN_MS] (6 h), so
 * a looping/duplicated SMS or a repeated call can never spam the same number.
 */
object AutoReplyCooldown {

    /** Default per-number cooldown: 6 hours. */
    const val DEFAULT_COOLDOWN_MS: Long = 6L * 60L * 60L * 1000L

    /**
     * `true` when a reply to a number whose last automatic reply was [lastAtMs] must be suppressed at
     * [nowMs]. A `null` last-reply (never replied) is never cooling down; [cooldownMs] `<= 0` disables
     * the cooldown. A clock going backwards (now < last) is treated as "not cooling down" so a reply
     * is never permanently blocked by wall-clock skew.
     */
    fun isCoolingDown(
        lastAtMs: Long?,
        nowMs: Long,
        cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    ): Boolean {
        if (lastAtMs == null || cooldownMs <= 0) return false
        val elapsed = nowMs - lastAtMs
        return elapsed in 0 until cooldownMs
    }

    /** Epoch-millis at which the next auto-reply to a number last replied to at [lastAtMs] is allowed. */
    fun nextAllowedAt(lastAtMs: Long, cooldownMs: Long = DEFAULT_COOLDOWN_MS): Long =
        lastAtMs + cooldownMs
}
