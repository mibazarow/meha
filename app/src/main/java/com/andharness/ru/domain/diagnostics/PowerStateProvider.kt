package com.andharness.ru.domain.diagnostics

/**
 * Read-only, failure-tolerant source of the current power state. [PowerGuard] is the Android
 * implementation; depending on this interface (instead of the Android class) lets the objective
 * coordinator be unit-tested on the JVM with a fake snapshot — the same trick `PowerGuardPolicy`
 * uses by being pure.
 */
interface PowerStateProvider {
    /** Point-in-time snapshot; implementations must never throw. */
    fun snapshot(): PowerSnapshot
}
