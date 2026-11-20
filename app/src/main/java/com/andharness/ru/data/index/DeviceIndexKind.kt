package com.andharness.ru.data.index

/**
 * What one `device_index_vectors` row represents.
 *
 * - [IMAGE] — a whole image, embedded once with the image encoder (`chunkIndex` = 0, empty text).
 * - [DOC_CHUNK] — one text chunk of a document (PDF/DOCX/TXT), embedded as text.
 *
 * The name is what the row's `kind` column stores (Room has no enum converter in this project, so the
 * entity keeps a `String` and this type is the typed view over it). [fromStorage] is the tolerant
 * reverse mapping: an unrecognised value is treated as `null` so a single corrupt row can never
 * crash a scan.
 *
 * Reference (Room row/column typing): https://developer.android.com/training/data-storage/room/defining-data
 */
enum class DeviceIndexKind {
    IMAGE,
    DOC_CHUNK;

    companion object {
        /** Parses a stored `kind` value, or `null` when it is not one of the known kinds. */
        fun fromStorage(value: String): DeviceIndexKind? =
            entries.firstOrNull { it.name == value }
    }
}
