package com.andharness.ru.domain.style

import kotlin.math.pow

/**
 * Folds per-message [StyleSignal]s into a [StyleProfile] and ages stale traits out.
 *
 * The reducer is pure: the caller supplies `now` (epoch millis) and `day` (epoch day), so tests are
 * deterministic and the profile is independent of the device timezone. Smoothing uses an EMA that
 * warms up quickly (1/n for the first observations) and then settles at [EMA_ALPHA], which makes
 * the first change noticeable without letting one message flip a trait.
 *
 * Decay is applied to the *hit* timestamp of most traits, so a user who stops swearing or using
 * emoji gradually stops being mirrored even while they keep chatting. Address is the exception:
 * its value is the «ты» share, so a formal user has no «ты» hits at all and must decay from the
 * last time any address form was observed.
 */
object StyleProfileReducer {

    /** A pause longer than this starts a new session (used by activation/telemetry only). */
    const val SESSION_GAP_MS = 2 * 60 * 60 * 1000L

    /** Distinct days retained per trait; enough for every activation gate. */
    const val MAX_DAY_KEYS = 14

    /** Smoothing factor after the warm-up observations. */
    const val EMA_ALPHA = 0.15

    /** Observations during which the EMA uses 1/n so the first signals are not ignored. */
    const val WARMUP_OBSERVATIONS = 10

    /** Weekly retention factor for a trait that stopped appearing. */
    const val DECAY_PER_WEEK = 0.95

    /** Below this value (after [DROP_AFTER_WEEKS]) the trait is forgotten entirely. */
    const val DECAY_MIN_VALUE = 0.2

    /** A trait must be stale for at least this many weeks before it may be dropped. */
    const val DROP_AFTER_WEEKS = 2.0

    private const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L

    /** Applies one meaningful message to [profile]. Blank signals are ignored. */
    fun apply(
        profile: StyleProfile,
        signal: StyleSignal,
        now: Long,
        day: Long,
    ): StyleProfile {
        if (!signal.hasContent) return profile

        var updated = decay(profile, now)
        val newSession = updated.lastMessageAt == 0L || now - updated.lastMessageAt > SESSION_GAP_MS
        updated = updated.copy(
            samples = updated.samples + 1,
            sessions = updated.sessions + if (newSession) 1 else 0,
            firstDay = updated.firstDay ?: day,
            lastDay = day,
            lastMessageAt = now,
        )

        fun observe(key: StyleTraitKey, applicable: Boolean, hit: Boolean) {
            if (!applicable) return
            val current = updated.trait(key) ?: StyleTrait(key = key)
            val observations = current.observations + 1
            val alpha = if (observations <= WARMUP_OBSERVATIONS) {
                1.0 / observations
            } else {
                EMA_ALPHA
            }
            val value = (current.value + alpha * ((if (hit) 1.0 else 0.0) - current.value))
                .coerceIn(0.0, 1.0)
            val days = (current.dayKeys + day).distinct().sorted().takeLast(MAX_DAY_KEYS)
            updated = updated.withTrait(
                current.copy(
                    value = value,
                    observations = observations,
                    hitMessages = current.hitMessages + if (hit) 1 else 0,
                    dayKeys = days,
                    lastSeenAt = now,
                    lastHitAt = if (hit) now else current.lastHitAt,
                )
            )
        }

        observe(StyleTraitKey.ADDRESS, signal.address != null, signal.address == AddressForm.TY)
        observe(
            StyleTraitKey.BREVITY,
            applicable = true,
            hit = signal.length <= StyleAnalyzer.SHORT_MESSAGE_CHARS,
        )
        observe(StyleTraitKey.SLANG, applicable = true, hit = signal.hasSlang)
        observe(StyleTraitKey.PROFANITY, applicable = true, hit = signal.profanityHits > 0)
        observe(StyleTraitKey.EMOJI, applicable = true, hit = signal.hasEmoji)
        observe(StyleTraitKey.PUNCTUATION, applicable = true, hit = signal.informalPunctuation)
        observe(StyleTraitKey.DIRECTNESS, applicable = true, hit = signal.isDirective)

        return updated
    }

    /**
     * Ages traits that stopped appearing. Returns the same instance when nothing changed. Traits
     * below [DECAY_MIN_VALUE] after [DROP_AFTER_WEEKS] are removed so learning can start over.
     */
    fun decay(profile: StyleProfile, now: Long): StyleProfile {
        if (profile.traits.isEmpty()) return profile
        var changed = false
        val kept = ArrayList<StyleTrait>(profile.traits.size)
        for (trait in profile.traits) {
            val reference = if (trait.key == StyleTraitKey.ADDRESS) {
                trait.lastSeenAt
            } else {
                trait.lastHitAt
            }
            if (reference <= 0L || now <= reference) {
                kept += trait
                continue
            }
            val weeks = (now - reference).toDouble() / WEEK_MS
            if (weeks < 1.0) {
                kept += trait
                continue
            }
            val value = trait.value * DECAY_PER_WEEK.pow(weeks)
            changed = true
            if (value < DECAY_MIN_VALUE && weeks >= DROP_AFTER_WEEKS) {
                continue
            }
            kept += trait.copy(value = value)
        }
        return if (changed) profile.copy(traits = kept) else profile
    }
}
