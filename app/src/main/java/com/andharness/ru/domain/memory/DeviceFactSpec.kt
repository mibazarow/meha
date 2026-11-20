package com.andharness.ru.domain.memory

/**
 * Static description of one cacheable device fact (TZ §12.4): its stable [key], the [category] used
 * for bulk invalidation, the freshness budget [ttlMs] and whether it takes part in the compact
 * prompt summary.
 *
 * @param ttlMs `0` means the fact never expires (STATIC class, invalidated only by events).
 */
data class DeviceFactSpec(
    val key: String,
    val category: String,
    val ttlMs: Long,
    val summary: Boolean
)
