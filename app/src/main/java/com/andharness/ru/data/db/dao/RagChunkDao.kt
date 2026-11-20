package com.andharness.ru.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.andharness.ru.data.db.entity.RagChunkEntity

/**
 * DAO for the local RAG chunk index (update.md idea #4). Re-indexing a source replaces its rows
 * ([deleteBySource] then [insertAll]) so a shorter new version never leaves stale chunks behind.
 */
@Dao
interface RagChunkDao {

    @Insert
    suspend fun insertAll(chunks: List<RagChunkEntity>)

    /** Atomically replaces every chunk of one source, so a crash cannot leave a half-indexed file. */
    @Transaction
    suspend fun replaceSource(path: String, chunks: List<RagChunkEntity>) {
        deleteBySource(path)
        insertAll(chunks)
    }

    @Query("DELETE FROM rag_chunks WHERE sourcePath = :path")
    suspend fun deleteBySource(path: String)

    @Query("DELETE FROM rag_chunks")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM rag_chunks")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM rag_chunks WHERE sourcePath = :path")
    suspend fun countBySource(path: String): Int

    /** Freshness key of an indexed source: the file mtime recorded when it was indexed, or null. */
    @Query("SELECT MAX(updatedAt) FROM rag_chunks WHERE sourcePath = :path")
    suspend fun sourceMtime(path: String): Long?

    @Query("SELECT * FROM rag_chunks")
    suspend fun all(): List<RagChunkEntity>

    @Query("SELECT * FROM rag_chunks WHERE sourcePath LIKE :prefix || '%'")
    suspend fun byPathPrefix(prefix: String): List<RagChunkEntity>

    @Query("SELECT DISTINCT sourcePath FROM rag_chunks ORDER BY sourcePath ASC")
    suspend fun sources(): List<String>

    @Query(
        "SELECT sourcePath, COUNT(*) AS chunkCount, MAX(updatedAt) AS mtime " +
            "FROM rag_chunks GROUP BY sourcePath ORDER BY sourcePath ASC"
    )
    suspend fun sourceSummary(): List<RagSourceSummary>
}
