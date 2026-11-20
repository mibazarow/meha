package com.andharness.ru.domain.objective

/** Report of a `objective_run` request: why it was or was not accepted, and the queue afterwards. */
data class ObjectiveRunReport(
    val outcome: ObjectiveRunOutcome,
    val objectiveId: String?,
    val status: ObjectiveWorkersStatus
)

/** Report of a `objective_stop` request: which objectives were stopped, and the queue afterwards. */
data class ObjectiveStopReport(
    val stoppedIds: List<String>,
    val status: ObjectiveWorkersStatus
) {
    /** True when at least one objective was actually stopped. */
    val stoppedSomething: Boolean get() = stoppedIds.isNotEmpty()
}
