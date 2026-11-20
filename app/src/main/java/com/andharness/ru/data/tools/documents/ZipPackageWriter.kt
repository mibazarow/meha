package com.andharness.ru.data.tools.documents

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Byte-level ZIP packaging shared by the OOXML and OpenDocument writers. */
internal object ZipPackageWriter {

    /** Packs text [entries] as UTF-8 parts. */
    fun zip(vararg entries: Pair<String, String>): ByteArray =
        zipBytes(entries.map { (name, xml) -> name to xml.toByteArray(Charsets.UTF_8) })

    /**
     * Packs [entries] into a ZIP; [stored] (when present) is written first with no DEFLATE, as
     * OpenDocument requires for its `mimetype` entry.
     */
    fun zipBytes(
        entries: List<Pair<String, ByteArray>>,
        stored: Pair<String, ByteArray>? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            if (stored != null) {
                val bytes = stored.second
                val entry = ZipEntry(stored.first).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    crc = crc32(bytes)
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
}
