package com.andharness.ru.data.tools.documents

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * ZIP entry access and repackaging for the documents tooling.
 *
 * References:
 * - `java.util.zip.ZipFile` — https://developer.android.com/reference/java/util/zip/ZipFile
 */
internal object ZipEntrySupport {

    /** Upper bound for a single ZIP entry read into memory (8 MiB). */
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024

    /** Returns the UTF-8 text of [entry] inside [zip], or `null` when the entry is absent/unreadable. */
    fun readZipEntryText(zip: File, entry: String): String? = try {
        ZipFile(zip).use { zipFile ->
            val zipEntry = zipFile.getEntry(entry) ?: return null
            zipFile.getInputStream(zipEntry).use { input ->
                String(BoundedStreamReader.read(input, MAX_ENTRY_BYTES), Charsets.UTF_8)
            }
        }
    } catch (e: IOException) {
        null
    }

    /** Lists the entry names of [zip] in archive order; an empty list when the archive is unreadable. */
    fun zipEntryNames(zip: File): List<String> = try {
        ZipFile(zip).use { zipFile -> zipFile.entries().asSequence().map { it.name }.toList() }
    } catch (e: IOException) {
        emptyList()
    }

    /**
     * Rewrites [file] as a new ZIP archive with every entry from [replacements] substituted and all
     * other entries copied verbatim, preserving their original order and compression method. A
     * stored (uncompressed) entry stays stored — ODF requires `mimetype` to be the first, STORED
     * entry — so repackaging an OpenDocument/OOXML container keeps it valid. Entries in [additions]
     * that do not already exist are appended at the end (so a new slide/sheet part can be added).
     *
     * Returns the new archive bytes, or `null` when the source cannot be read/rewritten.
     */
    fun rewriteZip(
        file: File,
        replacements: Map<String, ByteArray>,
        additions: Map<String, ByteArray> = emptyMap(),
    ): ByteArray? = try {
        val out = ByteArrayOutputStream()
        val written = mutableSetOf<String>()
        ZipFile(file).use { zip ->
            ZipOutputStream(out).use { target ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    written += entry.name
                    val replacement = replacements[entry.name]
                    when {
                        replacement != null ->
                            writeZipEntry(target, entry.name, replacement, entry.method == ZipEntry.STORED)
                        entry.method == ZipEntry.STORED -> {
                            val bytes = zip.getInputStream(entry).use { BoundedStreamReader.read(it, MAX_ENTRY_BYTES) }
                            writeZipEntry(target, entry.name, bytes, stored = true)
                        }
                        else -> {
                            target.putNextEntry(ZipEntry(entry.name))
                            zip.getInputStream(entry).use { it.copyTo(target) }
                            target.closeEntry()
                        }
                    }
                }
                for ((name, bytes) in additions) {
                    if (name !in written) writeZipEntry(target, name, bytes, stored = false)
                }
            }
        }
        out.toByteArray()
    } catch (e: Exception) {
        null
    }

    private fun writeZipEntry(target: ZipOutputStream, name: String, bytes: ByteArray, stored: Boolean) {
        val entry = ZipEntry(name)
        if (stored) {
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            entry.crc = CRC32().apply { update(bytes) }.value
        }
        target.putNextEntry(entry)
        target.write(bytes)
        target.closeEntry()
    }
}
