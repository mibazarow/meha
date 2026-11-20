package com.andharness.ru.data.db.viewer

import android.database.Cursor
import androidx.room.RoomDatabase
import com.andharness.ru.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only access to the app's Room database for the viewer screen. **By construction** it only
 * ever runs `SELECT` statements through [RoomDatabase.query]; there is no insert/update/delete path
 * here. Every table name is validated against the hard allow-list in [DatabaseViewerSpec] and then
 * interpolated from that allow-listed constant — never from caller-supplied text — so the SQL is
 * not injectable.
 */

@Singleton
class DatabaseViewerRepository @Inject constructor(
    private val database: AppDatabase
) {

    /** `COUNT(*)` per allow-listed table, for the section overview. */
    suspend fun counts(): List<DbTableCount> = withContext(Dispatchers.IO) {
        DatabaseViewerSpec.allTables
            .sorted()
            .map { table ->
                DbTableCount(
                    table = table,
                    label = DatabaseViewerSpec.label(table),
                    isBig = DatabaseViewerSpec.isBig(table),
                    count = countOf(table)
                )
            }
    }

    /** One page of `SELECT * FROM <allow-listed table>`; `page` is 0-based. */
    suspend fun page(
        table: String,
        page: Int,
        pageSize: Int = DatabaseViewerSpec.PAGE_SIZE
    ): DbPage = withContext(Dispatchers.IO) {
        require(DatabaseViewerSpec.isAllowed(table)) { "unknown table: $table" }
        val total = countOf(table)
        val pageCount = if (total == 0) 0 else (total + pageSize - 1) / pageSize
        val safePage = if (pageCount == 0) 0 else page.coerceIn(0, pageCount - 1)
        val offset = safePage * pageSize

        // Newest-first for the unbounded tables; stable rowid order otherwise.
        val order = if (DatabaseViewerSpec.isBig(table)) " ORDER BY rowid DESC" else ""
        val sql = "SELECT * FROM `$table`$order LIMIT ? OFFSET ?"

        val columns: List<String>
        val rows: List<DbRow>
        database.query(sql, arrayOf<Any?>(pageSize, offset)).use { cursor ->
            columns = cursor.columnNames.toList()
            rows = buildList {
                while (cursor.moveToNext()) {
                    add(DbRow(readRow(cursor, columns)))
                }
            }
        }

        DbPage(
            table = table,
            label = DatabaseViewerSpec.label(table),
            columns = columns,
            rows = rows,
            totalCount = total,
            page = safePage,
            pageCount = pageCount
        )
    }

    private fun countOf(table: String): Int {
        require(DatabaseViewerSpec.isAllowed(table)) { "unknown table: $table" }
        return database.query("SELECT COUNT(*) FROM `$table`", emptyArray<Any?>()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun readRow(cursor: Cursor, columns: List<String>): List<DbCell> =
        columns.mapIndexed { index, column ->
            DbCell(column = column, value = readCell(cursor, index, column))
        }

    private fun readCell(cursor: Cursor, index: Int, column: String): String? {
        if (cursor.isNull(index)) return null
        return when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_INTEGER ->
                DatabaseViewerSpec.formatInteger(column, cursor.getLong(index))

            Cursor.FIELD_TYPE_FLOAT ->
                cursor.getDouble(index).toString()

            Cursor.FIELD_TYPE_BLOB ->
                "‹blob ${cursor.getBlob(index).size} байт›"

            else ->
                cursor.getString(index)?.let(DatabaseViewerSpec::truncate)
        }
    }
}
