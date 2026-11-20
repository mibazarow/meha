package com.andharness.ru.domain.permission


/**
 * Outcome of the user-facing device-admin activation screen, as observed by the UI bridge and
 * reduced to a bridge-agnostic value so the decision below stays pure and unit-testable on the JVM.
 */
enum class DeviceAdminUiOutcome {
    /** The system activation screen returned a result (whether or not the admin is active). */
    COMPLETED,

    /** The screen was dismissed without confirming. */
    CANCELLED,

    /** The UI bridge could not run the flow (no foreground Activity, timeout, launch error). */
    FAILED,

    /** Any other/unexpected bridge answer. */
    UNEXPECTED,
}
