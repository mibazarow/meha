package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved text note or long-term memory. [kind] is "note" or "memory".
 */
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val kind: String,
    val text: String,
    /** Space/comma-separated keywords & synonyms for lexical recall (mainly for kind="memory"). */
    val tags: String = "",
    /** 0 = normal, 1 = important, 2 = critical. Used to rank recall results. */
    val importance: Int = 0,
    /** Last time this memory was surfaced by recall (for recency ranking). */
    val lastUsedAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Number of times recall surfaced this row (recall-frequency prior; 0 for never-recalled).
     * Bumped together with [lastUsedAt] on every recall.
     */
    val accessCount: Int = 0,
    /**
     * Stance strength (0..1) for `kind = "opinion"` rows. It decays exponentially over time
     * (see `OpinionConfidence.effective`); always 1.0 for notes/memory so non-opinion rows keep
     * their exact previous ranking. Defaulted so existing constructions compile unchanged.
     */
    val confidence: Float = 1.0f,
    /**
     * For `kind = "opinion"`: the `notes.id` of the opinion this row replaces, or 0 for none.
     * The superseded row is kept for history and is hidden from recall / `opinion_list` by default.
     */
    val supersedesId: Long = 0
)
