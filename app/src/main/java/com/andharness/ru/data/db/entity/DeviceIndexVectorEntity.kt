package com.andharness.ru.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One indexed unit of the whole-device semantic search index (`device_index_vectors`): either an image
 * (embedded with the image encoder) or one text chunk of a document (PDF/DOCX/TXT), each storing a
 * 768-float EmbeddingGemma 2 vector.
 *
 * A device file is not a Room row, so the key is an auto-generated [id] and a file is identified by
 * [sourceUri] (a `content://` URI or a file path). One file produces many rows: images have a single
 * row ([chunkIndex] = 0) while a document yields one row per chunk. The unique index on
 * ([sourceUri], [chunkIndex]) makes a re-index replace exactly the chunks it produces; the [sourceUri]
 * and [kind] indices back the incremental scan and the `device_search` type filter.
 *
 * [kind] stores a [com.andharness.ru.data.index.DeviceIndexKind] name (`IMAGE` / `DOC_CHUNK`); the
 * project keeps no enum `@TypeConverter`, so the column is a plain `TEXT` and the typed view is
 * applied in the repository. [vector] is the little-endian float32 `BLOB` produced by
 * [com.andharness.ru.domain.retrieval.VectorCodec] (the same codec the message/memory stores use) and
 * [dim] is its element count. [modelId] records the embedder, so an embedder change marks the row
 * stale and the indexer re-embeds it.
 *
 * Schema/column names must match [com.andharness.ru.data.db.DeviceIndexVectorsSchemaSql]. Official
 * reference (@Entity / @PrimaryKey(autoGenerate) / indices incl. unique / ByteArray -> BLOB):
 * https://developer.android.com/training/data-storage/room/defining-data
 */
@Entity(
    tableName = "device_index_vectors",
    indices = [
        Index(value = ["sourceUri", "chunkIndex"], unique = true),
        Index("sourceUri"),
        Index("kind"),
    ]
)
data class DeviceIndexVectorEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val kind: String,
    val sourceUri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val chunkIndex: Int,
    val text: String,
    val vector: ByteArray,
    val dim: Int,
    val modelId: String,
    val updatedAt: Long
)
