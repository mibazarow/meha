package com.andharness.ru.data.db.dao


/** One memory that has no `memory_persons` link yet (backfill candidate). */
data class MissingLinkMemory(val noteId: Long, val text: String)
