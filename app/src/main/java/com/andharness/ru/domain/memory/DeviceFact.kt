package com.andharness.ru.domain.memory


/**
 * A device fact (TZ §12): a piece of knowledge about this phone and its environment that the
 * agent may cache and reuse instead of re-scanning every turn.
 *
 * @param key stable identifier of the fact, e.g. `device.model`.
 * @param category fact family used for bulk invalidation, e.g. `apps`, `storage`, `network`.
 * @param valueJson serialized payload of the fact (shape is defined by the producer).
 * @param updatedAt epoch millis when the value was last produced/refreshed.
 * @param ttlMs freshness budget in millis; `0` means the fact is never cached (always volatile).
 * @param source logical producer of the fact, e.g. a tool name.
 * @param sourceApi concrete Android API used to produce the fact, when known.
 * @param invalidated set when an event hook invalidated the fact and it must be re-produced.
 */
data class DeviceFact(
    val key: String,
    val category: String,
    val valueJson: String,
    val updatedAt: Long,
    val ttlMs: Long,
    val source: String,
    val sourceApi: String?,
    val invalidated: Boolean
)
