package com.andharness.ru.data.vision


/** The resolved answer about the currently configured model. */
data class VisionState(
    val support: VisionSupport,
    val source: VisionSource,
    val model: String,
    val endpoint: String,
    val detail: String,
) {
    /** True only for a definitive "yes"; UNKNOWN is never treated as support (§3: honest). */
    val supportsVision: Boolean get() = support == VisionSupport.SUPPORTED
}
