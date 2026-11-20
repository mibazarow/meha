package com.andharness.ru.data.util

import android.database.Cursor
import java.util.WeakHashMap

/**
 * Small helpers that remove the repeated
 * `query(...)?.use { c -> ...; while (c.moveToNext()) { ... } }` boilerplate around the nullable
 * cursor returned by `ContentResolver.query`.
 *
 * API references (developer.android.com):
 * - `Cursor` (it `implements Closeable`; `getColumnIndex` returns `-1` when the column is absent;
 *   `moveToNext` returns `false` once the cursor is past the last row) —
 *   https://developer.android.com/reference/android/database/Cursor
 * - `Cursor.moveToNext` — https://developer.android.com/reference/android/database/Cursor#moveToNext()
 * - `ContentResolver.query` (returns `Cursor?`) —
 *   https://developer.android.com/reference/android/content/ContentResolver#query(android.net.Uri,%20java.lang.String%5B%5D,%20java.lang.String,%20java.lang.String%5B%5D,%20java.lang.String)
 */

/**
 * Iterates the rows of this cursor and closes it afterwards ([Cursor.close]). The body runs once per
 * row; `return@useRows` inside [block] acts like `continue` (the loop keeps going).
 *
 * Because the receiver is a non-null [Cursor], a nullable query result is handled at the call site
 * with `contentResolver.query(...)?.useRows { ... }`.
 */
inline fun Cursor.useRows(block: (Cursor) -> Unit) {
    use { cursor ->
        while (cursor.moveToNext()) {
            block(cursor)
        }
    }
}

/**
 * Like [useRows], but [block] decides whether to keep iterating: `true` continues, `false` stops
 * (equivalent to `break`). Use `return@useRowsWhile true` to skip a row and
 * `return@useRowsWhile false` to stop early.
 */
inline fun Cursor.useRowsWhile(block: (Cursor) -> Boolean) {
    use { cursor ->
        while (cursor.moveToNext()) {
            if (!block(cursor)) break
        }
    }
}

/**
 * Per-cursor column-index cache. `AbstractCursor.getColumnIndex` resolves a name by scanning the
 * column names on every call, so a loop that reads several columns would repeat that scan per row;
 * this memoises the result (including the `-1` "absent" result) per cursor instance.
 *
 * The key is the cursor identity and entries are held weakly, so a closed/collected cursor is not
 * leaked. Access is synchronized because providers are queried from background threads.
 */
private val columnIndexCache = WeakHashMap<Cursor, MutableMap<String, Int>>()

/** The column index of [columnName] (see [Cursor.getColumnIndex]), memoised per cursor. */
fun Cursor.columnIndex(columnName: String): Int = synchronized(columnIndexCache) {
    columnIndexCache.getOrPut(this) { HashMap() }.getOrPut(columnName) { getColumnIndex(columnName) }
}

/**
 * [Cursor.getString] for a hoisted [index], or `null` when the column is absent ([index] `< 0`).
 */
fun Cursor.getStringOrNull(index: Int): String? = if (index >= 0) getString(index) else null

/**
 * [Cursor.getLong] for a hoisted [index], or `null` when the column is absent ([index] `< 0`). A
 * present column with a SQL `NULL` value yields the raw getter result, exactly like calling
 * `getLong` directly.
 */
fun Cursor.getLongOrNull(index: Int): Long? = if (index >= 0) getLong(index) else null

/**
 * [Cursor.getInt] for a hoisted [index], or `null` when the column is absent ([index] `< 0`). A
 * present column with a SQL `NULL` value yields the raw getter result, exactly like calling
 * `getInt` directly.
 */
fun Cursor.getIntOrNull(index: Int): Int? = if (index >= 0) getInt(index) else null
