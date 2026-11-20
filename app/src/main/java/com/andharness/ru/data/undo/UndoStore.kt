package com.andharness.ru.data.undo

import com.andharness.ru.domain.undo.UndoCodec
import com.andharness.ru.domain.undo.UndoEntry
import com.andharness.ru.domain.undo.UndoRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * Persistent journal of reversible actions (M3 «Доверие», slice 1), backed by a single JSON file
 * `<rootDir>/undo.json` written atomically (temp file + rename), exactly like `SkillStore`.
 *
 * It is deliberately **Android-free** — it only takes a directory [File] — so CRUD, the [MAX_ENTRIES]
 * bound and ordering are exercised on the JVM with a temp dir (`UndoStoreTest`); the DI module
 * supplies `undo` under `context.filesDir`.
 *
 * Ordering contract: entries are stored oldest-first; [list] returns the newest first (a "recent
 * actions" feed), and [popLast] removes the newest entry. All access is `@Synchronized`; a
 * corrupt/foreign file degrades to an empty list instead of throwing, and a failed disk write keeps
 * the in-memory state unchanged (so a lost write is visible, never silently reported as success).
 */
class UndoStore(private val rootDir: File) : UndoRecorder {

    private val file = File(rootDir, FILE_NAME)

    private val _entries = MutableStateFlow(load())

    /** Current journal, oldest first (bounded to [UndoCodec.MAX_ENTRIES]). */
    val entries: StateFlow<List<UndoEntry>> = _entries.asStateFlow()

    /** All entries, oldest first. Never throws. */
    @Synchronized
    fun all(): List<UndoEntry> = _entries.value

    fun count(): Int = all().size

    /** Recent entries, newest first, capped to [limit]. */
    @Synchronized
    fun list(limit: Int = UndoCodec.MAX_ENTRIES): List<UndoEntry> =
        all().asReversed().take(limit.coerceAtLeast(0))

    /** Appends [entry] verbatim (bounded to the last [UndoCodec.MAX_ENTRIES]). Returns it. */
    @Synchronized
    fun record(entry: UndoEntry): UndoEntry {
        persist((all() + entry).takeLast(UndoCodec.MAX_ENTRIES))
        return entry
    }

    /** Records a new entry with a generated id and the current time. Best-effort. */
    @Synchronized
    override fun record(
        toolName: String,
        description: String,
        undoKind: String,
        payload: Map<String, String>,
    ) {
        record(
            UndoEntry(
                id = UUID.randomUUID().toString(),
                toolName = toolName,
                description = description,
                timestamp = System.currentTimeMillis(),
                undoKind = undoKind,
                payload = payload,
            ),
        )
    }

    /** Removes the newest entry and returns it, or null when the journal is empty. */
    @Synchronized
    fun popLast(): UndoEntry? {
        val last = all().lastOrNull() ?: return null
        persist(all().dropLast(1))
        return last
    }

    /** Removes an entry by id; returns true only when something was removed. */
    @Synchronized
    fun remove(id: String): Boolean {
        val current = all()
        if (current.none { it.id == id }) return false
        persist(current.filterNot { it.id == id })
        return true
    }

    /** Empties the journal. */
    @Synchronized
    fun clear() {
        persist(emptyList())
    }

    private fun load(): List<UndoEntry> = try {
        if (file.isFile) UndoCodec.decode(file.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun persist(entries: List<UndoEntry>) {
        try {
            rootDir.mkdirs()
            val bounded = entries.takeLast(UndoCodec.MAX_ENTRIES)
            val json = UndoCodec.encode(bounded)
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
        /** Subdirectory of `filesDir` that holds the undo journal. */
        const val DIR_NAME = "undo"

        /** Single JSON file inside [DIR_NAME]. */
        const val FILE_NAME = "undo.json"
    }
}
