package com.andharness.ru.domain.skill


/** The identifying fields of one on-screen node captured while recording. */
data class RecordedNode(
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
)
