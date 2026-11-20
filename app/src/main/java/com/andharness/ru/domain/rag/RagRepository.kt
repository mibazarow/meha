package com.andharness.ru.domain.rag

import java.io.File

interface RagRepository {

    /**
     * Indexes [path] (resolved safely against [root]). A directory is indexed one level deep unless
     * [recursive]. A source whose recorded mtime equals the current file mtime is skipped unless
     * [force]. Never throws for a bad path: it is reported in [RagIndexReport.errors].
     */
    suspend fun index(root: File, path: String, recursive: Boolean, force: Boolean): RagIndexReport

    /**
     * Ranks indexed chunks against [query] and returns the best [limit]. [pathPrefix] filters by a
     * root-relative path prefix when given. An empty index/zero matches is reported in
     * [RagQueryResult.note], never as a fake hit.
     */
    suspend fun query(query: String, limit: Int, pathPrefix: String? = null): RagQueryResult

    /** Per-source summary of the index. */
    suspend fun stats(): List<RagSourceInfo>

    /** Removes every chunk of one root-relative source. */
    suspend fun clearSource(sourcePath: String)

    /** Empties the whole index. */
    suspend fun clearAll()
}
