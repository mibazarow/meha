package com.andharness.ru.domain.print


/**
 * Outcome of the system print-dialog flow, as observed by the UI bridge and reduced to a
 * bridge-agnostic value so the decision below stays pure and unit-testable on the JVM.
 */
enum class PrintUiOutcome {
    /** `PrintManager.print()` returned and the system print UI was shown. */
    DIALOG_SHOWN,

    /** The UI bridge could not run the flow (no foreground Activity, no print service, error). */
    FAILED,

    /** The flow was cancelled before the print dialog could be shown. */
    CANCELLED,

    /** Any other/unexpected bridge answer. */
    UNEXPECTED,
}
