package com.andharness.ru.domain.tools


sealed interface CallStartPlan {
    /** The `phone` argument was missing or blank. */
    data object BlankNumber : CallStartPlan

    /** `CALL_PHONE` is not granted — the caller must surface `PERMISSION_REQUIRED` honestly. */
    data object NeedsPermission : CallStartPlan

    /** Ready to place. [emergency] means the platform may still add its own confirmation. */
    data class Place(val number: String, val emergency: Boolean) : CallStartPlan
}
