package com.andharness.ru.domain.ui


/** Rectangle in screen pixels; [EMPTY] is the zero-size fallback when no bounds were read. */
data class UiBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    companion object {
        val EMPTY = UiBounds(0, 0, 0, 0)
    }
}
