package com.andharness.ru.domain.communications.speech

import java.io.File

/**
 * Pure helper for the on-device SPEECH model FILES — the single, auditable place that deletes them.
 *
 * Speech-model files are removed ONLY by an explicit user action (the «Связи» delete control), so any
 * caller other than the explicit-delete path would be a regression. Deliberately pure JVM (no
 * Android imports) so the "delete removes files / never the dir" logic is unit-testable on the JVM.
 */
object SpeechModelFiles {

    /**
     * Removes the verified model file and the in-flight `.part` file, returning the number of bytes
     * actually freed (only files whose `File.delete()` returned `true` are counted). Only these two
     * files are touched — the model DIRECTORY and any sibling files are left intact.
     *
     * `File.delete()` — https://developer.android.com/reference/java/io/File#delete()
     */
    fun removeFiles(target: File, partial: File): Long {
        var freed = 0L
        listOf(target, partial).forEach { file ->
            runCatching {
                if (file.isFile) {
                    val length = file.length()
                    if (file.delete()) freed += length
                }
            }
        }
        return freed
    }
}
