package com.andharness.ru.data.intercept

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

/**
 * Resolves a phone number to a contact display name (update.md §2.4 «незнакомый» = not resolvable
 * in the user's contacts).
 *
 * Uses `ContactsContract.PhoneLookup.CONTENT_FILTER_URI`, the platform's purpose-built "number →
 * contact" lookup, which matches on the last digits and normalizes formats. Requires `READ_CONTACTS`;
 * when the permission is missing it returns `null` and the caller treats the number as unknown while
 * reporting the missing permission honestly.
 *
 * References:
 * - `ContactsContract.PhoneLookup`, `CONTENT_FILTER_URI`:
 *   https://developer.android.com/reference/android/provider/ContactsContract.PhoneLookup
 * - `ContactsContract.PhoneLookup#DISPLAY_NAME`:
 *   https://developer.android.com/reference/android/provider/ContactsContract.PhoneLookup#DISPLAY_NAME
 * - `ContentResolver.query(...): https://developer.android.com/reference/android/content/ContentResolver#query(android.net.Uri,%20java.lang.String[],%20java.lang.String,%20java.lang.String[],%20java.lang.String)
 */
object ContactLookup {

    private const val TAG = "Andharness"

    /** Contact name for [rawNumber], or `null` when unknown / `READ_CONTACTS` is not granted. */
    fun displayName(context: Context, rawNumber: String): String? {
        if (rawNumber.isBlank()) return null
        return try {
            val lookupUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(rawNumber),
            )
            context.contentResolver.query(
                lookupUri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index)?.takeIf { it.isNotBlank() } else null
                } else {
                    null
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CONTACTS missing; treating $rawNumber as unknown", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "contact lookup failed for $rawNumber", e)
            null
        }
    }

    /** True when [rawNumber] resolves to a real contact. */
    fun isKnown(context: Context, rawNumber: String): Boolean = displayName(context, rawNumber) != null
}
