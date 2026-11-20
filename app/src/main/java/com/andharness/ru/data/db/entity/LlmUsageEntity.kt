package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One token-usage row of the ledger (update.md «Идеи следующих волн», idea #2). Kept registered in
 * the Room schema even though the token/cost accounting feature was removed, so the existing
 * `llm_usage` table and its migration stay valid for upgrades.
 *
 * Token columns are NULLABLE on purpose: `null` means the endpoint did not report that number, and
 * the ledger says "unknown" instead of fabricating a value. [source] records how the row got its
 * numbers (`api` when a `usage` object was returned, `unknown` otherwise).
 *
 * Index on [at] matches the newest-first reads and the day-range reporting query. Column names must
 * match [com.andharness.ru.data.db.LlmUsageSchemaSql] used by the Room migration.
 *
 * Official reference: https://developer.android.com/training/data-storage/room/defining-data
 */
@Entity(
    tableName = "llm_usage",
    indices = [Index("at")]
)
data class LlmUsageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val at: Long,
    val chatId: Long?,
    val model: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val source: String,
    val routed: Boolean
)
