package com.andharness.ru.domain.localserver

import java.io.File

/**
 * Pure, Android-free readiness check for the on-device model artifacts ([LocalModelSpec.FILES]):
 * an artifact is "complete" only when it exists AND its size on disk equals the exact expected
 * `content-length` from the server ([LocalModelSpec.ModelFile.sizeBytes]).
 *
 * Why the size check matters: a missing file, a zero-byte file or a TRUNCATED file (e.g. an
 * interrupted rename, or a file removed/overwritten outside the app) must never be reported as a
 * ready model. Checking `File.length()` against the known size is the cheapest honest signal; the
 * expensive SHA-256 is verified once at download time and on demand (tool `local_model_status`),
 * never on every screen open.
 *
 * Kept pure JVM so the rule is unit-testable with real temp files (`LocalModelReadinessTest`).
 * `File.isFile()` / `File.length()` — https://developer.android.com/reference/java/io/File#length()
 */
object LocalModelReadiness {

    /** True when [file] exists and its size is exactly [expectedBytes] (a truncated file is not ready). */
    fun isComplete(file: File, expectedBytes: Long): Boolean =
        file.isFile && file.length() == expectedBytes

    /**
     * True only when the list is non-empty and EVERY `(file, expectedBytes)` pair is [isComplete].
     * An empty list is `false`: there is nothing to load, so "ready" would be a lie.
     */
    fun allComplete(files: List<Pair<File, Long>>): Boolean =
        files.isNotEmpty() && files.all { isComplete(it.first, it.second) }
}
