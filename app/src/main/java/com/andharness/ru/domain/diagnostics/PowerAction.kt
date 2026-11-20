package com.andharness.ru.domain.diagnostics


/**
 * Pure Kotlin decision layer for the power guard (update.md idea #19).
 *
 * The guard has exactly two actions: [PowerAction.ALLOW] and [PowerAction.DEFER]. It deliberately
 * does NOT stop anything itself — callers apply the verdict, and only to **non-essential periodic**
 * background work. A run the user explicitly asked for is passed with `essential = true` and is
 * never deferred.
 */
enum class PowerAction { ALLOW, DEFER }
