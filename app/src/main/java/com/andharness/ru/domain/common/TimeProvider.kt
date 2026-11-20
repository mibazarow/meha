package com.andharness.ru.domain.common

import java.time.ZoneId
import java.util.Locale

/**
 * Supplies the CURRENT wall-clock instant, timezone and locale.
 *
 * This is injected instead of calling `System.currentTimeMillis()` / `ZoneId.systemDefault()`
 * directly inside prompt assembly, for two reasons:
 *  - the system prompt must embed a FRESH timestamp on EVERY request (never a value cached once at
 *    startup or at the beginning of a long session) — [nowMillis] is read at compile time;
 *  - the JVM unit tests drive a deterministic pseudo-clock (see `ContextManagerImplTest`).
 *
 * `java.time`/`java.util` only: no Android dependency, so this interface is unit-testable on the JVM.
 */
interface TimeProvider {
    /** Current instant, milliseconds since the Unix epoch. */
    fun nowMillis(): Long

    /** The device's current timezone (re-read on every call so a runtime timezone change is seen). */
    fun zoneId(): ZoneId

    /** The user's current locale (re-read on every call). */
    fun locale(): Locale
}
