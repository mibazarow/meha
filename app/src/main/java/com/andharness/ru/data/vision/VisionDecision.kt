package com.andharness.ru.data.vision


/** What [VisionPolicy.decide] concluded before any network call is made. */
sealed interface VisionDecision {
    /** The model id is on the built-in allowlist; no probe needed. */
    data class Allowlisted(val pattern: String) : VisionDecision

    /** A fresh cached probe result exists. */
    data class Cached(val supported: Boolean) : VisionDecision

    /** Nothing is known yet — a one-shot probe is required. */
    data object Probe : VisionDecision
}
