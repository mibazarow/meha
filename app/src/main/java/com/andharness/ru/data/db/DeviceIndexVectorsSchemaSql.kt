package com.andharness.ru.data.db

/**
 * Raw SQL of the whole-device semantic index `device_index_vectors`, added in the Room migration
 * 39 -> 40: one row per indexed unit — either an image (by image embedding) or one text chunk of a
 * document (PDF/DOCX/TXT), each carrying a 768-float, L2-normalised EmbeddingGemma 2 vector.
 *
 * Unlike `chat_message_vectors` (keyed by `messages.id`), a device file is not a Room row, so the key
 * is an auto-generated `id` and a file is identified by its `sourceUri` (a `content://` URI or a file
 * path). One file yields many rows: images have a single row (`chunkIndex` = 0) while a document
 * yields one row per chunk. The unique index on (`sourceUri`, `chunkIndex`) makes a re-index replace
 * exactly the chunks it produces, and the plain `sourceUri` / `kind` indices back the incremental
 * "what changed since last time" scan and the type filter of `device_search`.
 *
 * `vector` is the little-endian float32 `BLOB` produced by
 * [com.andharness.ru.domain.retrieval.VectorCodec] — the same codec the message/memory vector stores
 * use — and `dim` is its element count. `modelId` records which embedder produced it, so an embedder
 * change marks the row stale and the indexer re-embeds it.
 *
 * Kept as named constants (like [StoredMessageSchemaSql] / [ChatMessageVectorSchemaSql]) so a pure
 * JVM test can cross-check them without a device; Room re-validates the migrated database at runtime
 * (https://developer.android.com/training/data-storage/room/migrating-db-versions).
 *
 * Every statement is non-destructive: it only creates a new table and its indices. The index names
 * follow Room's convention `index_<table>_<col>[_<col>...]`
 * (https://developer.android.com/training/data-storage/room/defining-data#indices). The DDL is
 * ordinary SQLite (no vendor extensions), so it is SQLCipher-compatible.
 */
object DeviceIndexVectorsSchemaSql {

    const val CREATE_DEVICE_INDEX_VECTORS_TABLE =
        "CREATE TABLE IF NOT EXISTS `device_index_vectors` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`kind` TEXT NOT NULL, " +
            "`sourceUri` TEXT NOT NULL, " +
            "`displayName` TEXT NOT NULL, " +
            "`mimeType` TEXT NOT NULL, " +
            "`sizeBytes` INTEGER NOT NULL, " +
            "`lastModified` INTEGER NOT NULL, " +
            "`chunkIndex` INTEGER NOT NULL, " +
            "`text` TEXT NOT NULL, " +
            "`vector` BLOB NOT NULL, " +
            "`dim` INTEGER NOT NULL, " +
            "`modelId` TEXT NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL)"

    /**
     * One row per (file, chunk); a re-index replaces exactly the chunks of that file. Also the lookup
     * the searcher uses to delete a file's old chunks before writing the new ones.
     */
    const val CREATE_DEVICE_INDEX_VECTORS_SOURCE_CHUNK_UNIQUE_INDEX =
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_device_index_vectors_sourceUri_chunkIndex` " +
            "ON `device_index_vectors` (`sourceUri`, `chunkIndex`)"

    /** Every file's indexed metadata, for the incremental "what changed" scan. */
    const val CREATE_DEVICE_INDEX_VECTORS_SOURCE_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_device_index_vectors_sourceUri` " +
            "ON `device_index_vectors` (`sourceUri`)"

    /** The `device_search` type filter (IMAGE vs DOC_CHUNK). */
    const val CREATE_DEVICE_INDEX_VECTORS_KIND_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_device_index_vectors_kind` " +
            "ON `device_index_vectors` (`kind`)"

    /** Every statement of the v39 -> v40 migration, in execution order (test aid). */
    val all: List<String> = listOf(
        CREATE_DEVICE_INDEX_VECTORS_TABLE,
        CREATE_DEVICE_INDEX_VECTORS_SOURCE_CHUNK_UNIQUE_INDEX,
        CREATE_DEVICE_INDEX_VECTORS_SOURCE_INDEX,
        CREATE_DEVICE_INDEX_VECTORS_KIND_INDEX,
    )
}
