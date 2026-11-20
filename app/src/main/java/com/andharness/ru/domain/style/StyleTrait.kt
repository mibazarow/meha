package com.andharness.ru.domain.style

import kotlinx.serialization.Serializable

/**
 * One learned style trait with the evidence behind it.
 *
 * [value] is a smoothed share (0..1) of messages where the trait was present, [observations] counts
 * the messages where it was measurable, and [dayKeys] keeps the distinct epoch days it was seen on
 * (bounded by the reducer). The activation gates in `StyleActivation` require both enough
 * observations and recurrence across days, so one intense conversation can never flip a trait.
 * [lastSeenAt] is the last message where the trait was measurable; [lastHitAt] is the last message
 * where it was actually present, and drives decay (a user who stops swearing stops being mirrored).
 */
@Serializable
data class StyleTrait(
    val key: StyleTraitKey,
    val value: Double = 0.0,
    val observations: Int = 0,
    val hitMessages: Int = 0,
    val dayKeys: List<Long> = emptyList(),
    val lastSeenAt: Long = 0L,
    val lastHitAt: Long = 0L,
)
