package com.andharness.ru.domain.diagnostics


/** Verdict of [PowerGuardPolicy.evaluate]: the action plus an honest, human-readable reason. */
data class PowerVerdict(
    val action: PowerAction,
    val reason: String?,
    val snapshot: PowerSnapshot
) {
    val defer: Boolean get() = action == PowerAction.DEFER
}
