package com.andharness.ru.data.clipboard

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * Persistent clipboard history, backed by a single JSON file
 * `<rootDir>/clipboard_history.json` written atomically (temp file + rename), exactly like
 * `SkillStore` / `UndoStore`.
 *
 * It is deliberately **Android-free** — it only takes a directory [File] — so CRUD, the
 * [ClipboardHistoryRules.MAX_ENTRIES] bound, the length cap and consecutive dedupe are exercised on
 * the JVM (`ClipboardHistoryStoreTest`); the DI module supplies `clipboard` under
 * `context.filesDir`.
 *
 * Ordering contract: entries are stored oldest-first; [list] returns the newest first (a "recent
 * items" feed) and [latest] returns the newest entry. All access is `@Synchronized`; a
 * corrupt/foreign file degrades to an empty list, and a failed disk write keeps the in-memory state
 * unchanged so a lost write is visible rather than silently reported as success.
 */
class ClipboardHistoryStore(private val rootDir: File) {

    private val file = File(rootDir, FILE_NAME)

    private val _entries = MutableStateFlow(load())

    /** Current history, oldest first (bounded to [ClipboardHistoryRules.MAX_ENTRIES]). */
    val entries: StateFlow<List<ClipboardEntry>> = _entries.asStateFlow()

    /** All entries, oldest first. Never throws. */
    @Synchronized
    fun all(): List<ClipboardEntry> = _entries.value

    fun count(): Int = all().size

    /** Recent entries, newest first, capped to [limit]. */
    @Synchronized
    fun list(limit: Int = ClipboardHistoryRules.MAX_ENTRIES): List<ClipboardEntry> =
        all().asReversed().take(limit.coerceAtLeast(0))

    /** The newest entry, or null when the history is empty. */
    @Synchronized
    fun latest(): ClipboardEntry? = all().lastOrNull()

    /**
     * Remembers [text] (trimmed, length-capped). Returns null for blank text (nothing is stored) or
     * when the text equals the newest entry (consecutive duplicate — the existing entry is returned
     * and the file is not rewritten). Otherwise appends a new entry and returns it.
     */
    @Synchronized
    fun record(
        text: String,
        sourcePackage: String? = null,
        now: Long = System.currentTimeMillis(),
    ): ClipboardEntry? {
        val normalized = ClipboardHistoryRules.normalizeText(text) ?: return null
        val current = all()
        if (ClipboardHistoryRules.isDuplicateOfLast(current, normalized)) return current.lastOrNull()
        val entry = ClipboardEntry(
            id = UUID.randomUUID().toString(),
            text = normalized,
            sourcePackage = sourcePackage,
            timestamp = now,
        )
        persist(current + entry)
        return entry
    }

    /** Removes an entry by id; returns true only when something was removed. */
    @Synchronized
    fun delete(id: String): Boolean {
        val current = all()
        if (current.none { it.id == id }) return false
        persist(current.filterNot { it.id == id })
        return true
    }

    /** Empties the history (the system clipboard is untouched). */
    @Synchronized
    fun clear() {
        persist(emptyList())
    }

    private fun load(): List<ClipboardEntry> = try {
        if (file.isFile) ClipboardHistoryCodec.decode(file.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun persist(entries: List<ClipboardEntry>) {
        try {
            rootDir.mkdirs()
            val bounded = ClipboardHistoryRules.bound(entries)
            val json = ClipboardHistoryCodec.encode(bounded)
            val temp = File(rootDir, "$FILE_NAME.tmp")
            temp.writeText(json)
            if (!temp.renameTo(file)) {
                // Rename can fail on some filesystems; fall back to a direct write so the save still
                // lands, and clean up the temp file.
                file.writeText(json)
                temp.delete()
            }
            _entries.value = bounded
        } catch (_: Exception) {
            // Best-effort: keep the in-memory state unchanged so a failed disk write is visible.
        }
    }

    companion object {
        /** Subdirectory of `filesDir` that holds the clipboard history. */
        const val DIR_NAME = "clipboard"

        /** Single JSON file inside [DIR_NAME]. */
        const val FILE_NAME = "clipboard_history.json"
    }
}
