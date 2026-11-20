package com.andharness.ru.domain.skill


/** Result of converting a recording: the replayable [steps] plus honest per-action notes. */
data class SkillConversion(
    val steps: List<SkillStep>,
    /** Human notes for the actions that became a step, in order. */
    val converted: List<String>,
    /** Human notes for the actions that could not be expressed as a step, in order. */
    val skipped: List<String>,
) {
    val hasSteps: Boolean get() = steps.isNotEmpty()
}
