package com.andharness.ru.domain.memory

import kotlin.math.pow

/**
 * Time-decay of an opinion's confidence (stored as `notes.confidence`, kind = "opinion").
 *
 * An opinion holds a stance with a strength in `0..1`; that strength fades exponentially, halving
 * every [DEFAULT_HALF_LIFE_DAYS] days, so an old stance is still remembered but ranks lower than a
 * fresh one. Pure Kotlin (no Room/Android) so it is unit-testable on the JVM.
 *
 * Reference: https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.math/pow.html
 */
object OpinionConfidence {

    /** Default half-life of the confidence decay: 90 days. */
    const val DEFAULT_HALF_LIFE_DAYS = 90

    private const val MILLIS_PER_DAY = 86_400_000.0

    /**
     * Effective (decay-adjusted) confidence at [now]: `confidence * 0.5^(ageDays / halfLife)`,
     * where `ageDays` is the age of [createdAt] in days. The result is clamped to `0..1` and a
     * negative age (a future [createdAt], e.g. clock skew) is treated as age 0, so it never
     * amplifies above the stored [confidence].
     *
     * @param confidence stored stance strength, normally in `0..1`.
     * @param createdAt  epoch millis the opinion was created (the decay reference).
     * @param now        epoch millis "now".
     * @param halfLifeDays days after which confidence halves; values below 1 are treated as 1.
     */
    fun effective(
        confidence: Float,
        createdAt: Long,
        now: Long,
        halfLifeDays: Int = DEFAULT_HALF_LIFE_DAYS
    ): Float {
        val ageDays = (now - createdAt).coerceAtLeast(0L) / MILLIS_PER_DAY
        val halfLife = halfLifeDays.coerceAtLeast(1).toDouble()
        val decayed = confidence.toDouble() * 0.5.pow(ageDays / halfLife)
        if (!decayed.isFinite()) return 0.0f
        return decayed.coerceIn(0.0, 1.0).toFloat()
    }
}
