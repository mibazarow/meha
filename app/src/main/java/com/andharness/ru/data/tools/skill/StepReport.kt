package com.andharness.ru.data.tools.skill

/** One executed step's honest outcome. */
data class StepReport(
    val index: Int,
    val action: String,
    val success: Boolean,
    val detail: String,
)
