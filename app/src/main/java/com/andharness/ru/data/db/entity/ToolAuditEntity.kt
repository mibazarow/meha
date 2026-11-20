package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Append-only tool-call audit row (update.md «Идеи следующих волн», idea #6). Written by
 * [com.andharness.ru.data.audit.ToolAuditRepositoryImpl] from the single tool-dispatch point, so
 * every tool call leaves one immutable row. Rows form a SHA-256 hash chain via [prevHash]/[hash].
 *
 * Privacy: [argsPreview] is already redacted/truncated and [resultHash] is only a SHA-256 of the
 * result — the raw arguments and result text are never stored here.
 *
 * Index on [at] matches the newest-first reads. Schema/column names must match [ToolAuditSchemaSql]
 * used by the Room migration.
 *
 * Official reference: https://developer.android.com/training/data-storage/room/defining-data
 */
@Entity(
    tableName = "tool_audit",
    indices = [Index("at")]
)
data class ToolAuditEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val at: Long,
    val chatId: Long?,
    val tool: String,
    val argsPreview: String,
    val success: Boolean,
    val resultHash: String,
    val durationMs: Long,
    val model: String?,
    val prevHash: String,
    val hash: String
)
