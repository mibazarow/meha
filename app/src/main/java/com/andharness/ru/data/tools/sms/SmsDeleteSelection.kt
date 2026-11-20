package com.andharness.ru.data.tools.sms

import android.provider.Telephony

/**
 * Builds the `selection` / `selectionArgs` pair for `sms_delete` from the tool's `scope` plus its
 * scope-specific `thread_id` / `number`. Pure Kotlin (no [android.content.ContentResolver], no
 * `Context`), so it is unit-testable on the JVM.
 *
 * References:
 * - `Telephony.Sms.THREAD_ID`, `Telephony.Sms.ADDRESS`:
 *   https://developer.android.com/reference/android/provider/Telephony.Sms
 * - `ContentResolver.delete(uri, selection, selectionArgs)`:
 *   https://developer.android.com/reference/android/content/ContentResolver#delete(android.net.Uri,%20java.lang.String,%20java.lang.String[])
 */
internal object SmsDeleteSelection {

    /**
     * Maps `scope` to a where-clause:
     * - `all` → `(null, null)` (every SMS row — the most destructive scope);
     * - `thread` → `THREAD_ID = ?` with the numeric id;
     * - `number` → `ADDRESS = ?` with the trimmed number.
     *
     * A blank/missing/invalid argument or an unknown scope is a [Result.failure] carrying a Russian
     * message. Exact address forms may differ between senders, so `number` matching is exact — kept
     * deliberately simple and honest.
     */
    fun build(
        scope: String?,
        threadId: String?,
        number: String?,
    ): Result<Pair<String?, Array<String>?>> = when (scope?.trim()?.lowercase()) {
        "all" -> Result.success(null to null)
        "thread" -> {
            val id = threadId?.trim().orEmpty()
            when {
                id.isEmpty() ->
                    Result.failure(IllegalArgumentException("Для scope=thread укажите thread_id."))
                id.toLongOrNull() == null ->
                    Result.failure(IllegalArgumentException("thread_id должен быть числом: «$id»."))
                else -> Result.success("${Telephony.Sms.THREAD_ID} = ?" to arrayOf(id))
            }
        }
        "number" -> {
            val value = number?.trim().orEmpty()
            if (value.isEmpty()) {
                Result.failure(IllegalArgumentException("Для scope=number укажите number."))
            } else {
                Result.success("${Telephony.Sms.ADDRESS} = ?" to arrayOf(value))
            }
        }
        null, "" ->
            Result.failure(IllegalArgumentException("Укажите scope: all, thread или number."))
        else ->
            Result.failure(
                IllegalArgumentException("Неизвестный scope «$scope»: допустимо all, thread, number."),
            )
    }
}
