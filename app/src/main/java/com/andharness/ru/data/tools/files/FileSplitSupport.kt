package com.andharness.ru.data.tools.files

import java.util.Locale

/**
 * Pure (Android-free) split/join math and part-name helpers used by `files_split` / `files_join`,
 * so the arithmetic and the naming/ordering can be unit-tested on the JVM without a device.
 *
 * A split file is a numbered sequence `«name».partNN` (two digits minimum, zero-padded, so the
 * parts sort lexicographically AND numerically in the same order until 100+ parts). A join
 * recovers the base name by stripping the `.partNN` suffix.
 *
 * References (pure `java.lang` / Kotlin stdlib):
 * - `String.padStart` — https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/pad-start.html
 * - `String.lowercase(Locale)` — https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.text/lowercase.html
 */
internal object FileSplitSupport {

    /** Separator between the base file name and the numeric part index. */
    const val PART_INFIX = ".part"

    /** Minimum number of digits in the part suffix, so `.part01` … `.part99`. */
    const val MIN_PART_DIGITS = 2

    /**
     * Hard ceiling on how many parts one split may produce; keeps the part list and the per-part
     * file handles bounded (a 4 GB file split at 1 byte would otherwise try to create billions).
     */
    const val MAX_PARTS = 100_000

    private const val KIB = 1024L
    private const val MIB = KIB * 1024L
    private const val GIB = MIB * 1024L
    private const val TIB = GIB * 1024L

    /** Zero-padding width for the part suffix when splitting into [partCount] parts. */
    fun digitsFor(partCount: Int): Int =
        maxOf(MIN_PART_DIGITS, partCount.toString().length)

    /** The file name of part number [index] (1-based) out of [partCount]. */
    fun partName(baseName: String, index: Int, partCount: Int): String =
        "$baseName$PART_INFIX${index.toString().padStart(digitsFor(partCount), '0')}"

    /** All part names for [baseName] split into [partCount] parts, in order. */
    fun partNames(baseName: String, partCount: Int): List<String> =
        (1..partCount).map { partName(baseName, it, partCount) }

    /**
     * Equal-size plan: [partCount] part sizes that differ by at most one byte; the earliest parts
     * absorb the remainder so `sum(result) == totalBytes` exactly.
     */
    fun equalPartSizes(totalBytes: Long, partCount: Int): List<Long> {
        require(partCount >= 1) { "partCount must be >= 1" }
        require(totalBytes >= 0L) { "totalBytes must be >= 0" }
        if (totalBytes == 0L) return emptyList()
        val base = totalBytes / partCount
        val remainder = (totalBytes % partCount).toInt()
        return List(partCount) { index -> base + if (index < remainder) 1L else 0L }
    }

    /**
     * Size-based plan: as many full [partSizeBytes] parts as fit, plus a final smaller remainder
     * part (omitted when the file divides exactly).
     */
    fun sizedPartSizes(totalBytes: Long, partSizeBytes: Long): List<Long> {
        require(partSizeBytes > 0L) { "partSizeBytes must be > 0" }
        require(totalBytes >= 0L) { "totalBytes must be >= 0" }
        if (totalBytes == 0L) return emptyList()
        val fullParts = totalBytes / partSizeBytes
        val sizes = ArrayList<Long>()
        repeat(fullParts.toInt()) { sizes += partSizeBytes }
        val remainder = totalBytes % partSizeBytes
        if (remainder > 0L) sizes += remainder
        return sizes
    }

    /** Number of parts a size-based split would produce (>= 1 for a non-empty file). */
    fun partCountForSize(totalBytes: Long, partSizeBytes: Long): Long {
        require(partSizeBytes > 0L) { "partSizeBytes must be > 0" }
        if (totalBytes <= 0L) return 0L
        return (totalBytes + partSizeBytes - 1L) / partSizeBytes
    }

    private val PART_SUFFIX = Regex("""\.part(\d+)$""")

    /** The 1-based part index encoded in [name] (`«name».partNN`), or `null` when absent. */
    fun partIndex(name: String): Int? =
        PART_SUFFIX.find(name)?.groupValues?.get(1)?.toIntOrNull()

    /** The base name of a part file (the name with `.partNN` stripped), or `null` when not a part. */
    fun baseNameOfPart(name: String): String? {
        val match = PART_SUFFIX.find(name) ?: return null
        return name.substring(0, match.range.first).ifEmpty { null }
    }

    /** Natural ordering: by numeric part index, then by name; non-part names sort last. */
    fun orderParts(names: List<String>): List<String> =
        names.sortedWith(compareBy({ partIndex(it) ?: Int.MAX_VALUE }, { it }))

    /**
     * Parses a human byte count: plain digits or digits with an optional binary unit suffix
     * (`B`, `KB`, `MB`, `GB`, `TB`, and the Cyrillic `КБ`, `МБ`, `ГБ`, `ТБ`), case-insensitive,
     * spaces allowed. Returns `null` for anything else (including zero).
     */
    fun parseByteCount(raw: String): Long? {
        val compact = raw.trim().lowercase(Locale.ROOT).replace(" ", "")
        if (compact.isEmpty()) return null
        val match = BYTE_COUNT.find(compact) ?: return null
        val value = match.groupValues[1].toLongOrNull() ?: return null
        if (value <= 0L) return null
        val multiplier = when (match.groupValues[2]) {
            "", "b" -> 1L
            "kb", "кб" -> KIB
            "mb", "мб" -> MIB
            "gb", "гб" -> GIB
            "tb", "тб" -> TIB
            else -> return null
        }
        return if (value > Long.MAX_VALUE / multiplier) null else value * multiplier
    }

    private val BYTE_COUNT = Regex("""^(\d+)(b|kb|mb|gb|tb|кб|мб|гб|тб)?$""")
}
