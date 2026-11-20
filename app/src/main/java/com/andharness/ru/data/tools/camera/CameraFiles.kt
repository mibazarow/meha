package com.andharness.ru.data.tools.camera

import java.io.File

/** Atomic file writing for camera output, so a reader never sees a partially written capture. */
internal object CameraFiles {

    /** Writes [bytes] to [target] through a `.tmp` sibling so a reader never sees a partial file. */
    fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeBytes(bytes)
        if (temporary.length() <= 0L) {
            temporary.delete()
            throw IllegalStateException("Кадр записан пустым: ${target.name}")
        }
        if (!temporary.renameTo(target)) {
            target.writeBytes(bytes)
            temporary.delete()
        }
        if (!target.exists() || target.length() <= 0L) {
            throw IllegalStateException("Файл не создан: ${target.name}")
        }
    }
}
