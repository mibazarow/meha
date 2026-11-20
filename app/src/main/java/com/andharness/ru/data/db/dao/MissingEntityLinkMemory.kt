package com.andharness.ru.data.db.dao


/** One memory that has no `memory_entities` link yet (backfill candidate). */
data class MissingEntityLinkMemory(val noteId: Long, val text: String, val tags: String)
