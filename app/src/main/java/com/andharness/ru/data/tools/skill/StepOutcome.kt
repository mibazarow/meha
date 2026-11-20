package com.andharness.ru.data.tools.skill

/** Internal result of executing one [PlannedStep]. */
internal data class StepOutcome(val success: Boolean, val detail: String)
