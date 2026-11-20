package com.andharness.ru.domain.print

/**
 * Pure decision for `print_file` (BUG-023): the tool may report success only when the system print
 * dialog was actually shown from an Activity. `PrintManager.print()` throws
 * `IllegalStateException("Can print only from an activity")` for any non-Activity context, and it
 * only hands the job to the print service — the user still has to confirm — so success means
 * "dialog shown", never "printed".
 *
 * References:
 * - `PrintManager.print()` (callable only from an Activity; throws `IllegalStateException`):
 *   https://developer.android.com/reference/android/print/PrintManager#print(java.lang.String,%20android.print.PrintDocumentAdapter,%20android.print.PrintAttributes)
 * - Printing custom documents (obtains `PrintManager` from the Activity context):
 *   https://developer.android.com/training/printing/custom-docs
 *
 * Kept free of Android APIs so every branch is covered by a JVM unit test.
 */
object PrintDecision {

    const val CANCELLED = "Печать отменена: системный диалог печати не был показан."
    const val UNEXPECTED = "Неожиданный ответ интерфейса при открытии диалога печати."
    const val NO_DIALOG = "Не удалось открыть системный диалог печати."

    /** The system print dialog was shown; the file is not printed until the user confirms. */
    fun dialogShown(fileName: String, mime: String): PrintFileResult = PrintFileResult(
        true,
        "Открыт системный диалог печати для «$fileName» (тип $mime). " +
            "Файл пока не напечатан — подтвердите печать в диалоге.",
    )

    /**
     * @param outcome what the UI bridge reported for the print dialog.
     * @param fileName the file name (used only on the [PrintUiOutcome.DIALOG_SHOWN] branch).
     * @param mime the resolved mime (used only on the [PrintUiOutcome.DIALOG_SHOWN] branch).
     * @param failureMessage the bridge's honest failure text (e.g. no foreground Activity), if any.
     */
    fun from(
        outcome: PrintUiOutcome,
        fileName: String,
        mime: String,
        failureMessage: String? = null,
    ): PrintFileResult = when (outcome) {
        PrintUiOutcome.DIALOG_SHOWN -> dialogShown(fileName, mime)
        PrintUiOutcome.FAILED -> PrintFileResult(false, failureMessage ?: NO_DIALOG)
        PrintUiOutcome.CANCELLED -> PrintFileResult(false, CANCELLED)
        PrintUiOutcome.UNEXPECTED -> PrintFileResult(false, UNEXPECTED)
    }
}
