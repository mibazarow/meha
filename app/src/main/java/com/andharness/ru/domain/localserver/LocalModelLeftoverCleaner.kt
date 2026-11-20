package com.andharness.ru.domain.localserver

import java.io.File

/**
 * Pure policy for reclaiming the ORPHANED files of the removed on-device LOCAL CHAT MODEL.
 *
 * The feature is gone, but its downloads and KV slots survive an in-place app update (Android keeps
 * app data only when the package name and signing certificate match), so they must be cleaned up once
 * by the new build:
 * - `<filesDir>/models/meha-qwen3vl-v2-iq3m.gguf` (weights) and
 * - `<filesDir>/models/meha-qwen3vl-v2-mmproj.gguf` (multimodal projector), plus any other
 *   `meha-qwen3vl-v2-*` leftover under `<filesDir>/models/`;
 * - `<filesDir>/slots/` (llama.cpp KV-cache slots).
 *
 * It deliberately does NOT touch the whole-device SEARCH embedder (`<filesDir>/models/embedder/`)
 * or the speech models (`<filesDir>/speech_models/`): those features remain in the app. The
 * embedder lives under the same `models/` directory, so exclusion is by NAME — only the
 * `meha-qwen3vl-v2-` prefix is targeted, never `models/embedder/`.
 *
 * Kept pure JVM (only `java.io.File`, no Android imports) so the "which paths to clean" rule is
 * unit-testable without a device (`LocalModelLeftoverCleanerTest`). Deletion itself is injected as a
 * lambda so the caller owns the actual `File` API and this object owns only the policy.
 */
object LocalModelLeftoverCleaner {

    /** Directory, relative to `filesDir`, that holds downloaded model artifacts. */
    const val MODELS_DIR = "models"

    /** Directory, relative to `filesDir`, that held the removed feature's KV slots. */
    const val SLOTS_DIR = "slots"

    /** Name prefix shared by every file of the removed local chat model. */
    const val LEGACY_FILE_PREFIX = "meha-qwen3vl-v2-"

    /** The removed model's weights file name. */
    const val LEGACY_WEIGHTS_FILE = "meha-qwen3vl-v2-iq3m.gguf"

    /** The removed model's multimodal projector file name. */
    const val LEGACY_MMPROJ_FILE = "meha-qwen3vl-v2-mmproj.gguf"

    /**
     * The paths to delete for a one-time cleanup. `modelsDir` is normally
     * `File(filesDir, [MODELS_DIR])`; it is passed separately so the policy is trivially testable.
     *
     * Includes the two known legacy files, every sibling whose name starts with
     * [LEGACY_FILE_PREFIX], and the `slots/` directory. Never includes `modelsDir` itself or
     * unrelated siblings such as `models/embedder/`.
     */
    fun pathsToDelete(modelsDir: File, filesDir: File): List<File> {
        val paths = LinkedHashSet<File>()
        paths += File(modelsDir, LEGACY_WEIGHTS_FILE)
        paths += File(modelsDir, LEGACY_MMPROJ_FILE)
        // Catch any other leftover of the removed model that is not one of the two known names.
        modelsDir.listFiles()?.forEach { child ->
            if (child.name.startsWith(LEGACY_FILE_PREFIX)) paths += child
        }
        paths += File(filesDir, SLOTS_DIR)
        return paths.toList()
    }

    /**
     * Best-effort cleanup: invokes [delete] for every path from [pathsToDelete] and returns how many
     * calls reported success. Each call is wrapped in `runCatching`, so a failure is swallowed and
     * never propagates — a cleanup must never crash the caller.
     *
     * @param delete the actual deletion (e.g. `{ it.deleteRecursively() }`); returns `true` on success.
     */
    fun clean(filesDir: File, modelsDir: File, delete: (File) -> Boolean): Int {
        var removed = 0
        pathsToDelete(modelsDir, filesDir).forEach { path ->
            val ok = runCatching { delete(path) }.getOrDefault(false)
            if (ok) removed++
        }
        return removed
    }
}
