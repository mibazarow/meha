package com.andharness.ru.data.profile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * Persistent global profile of explicitly saved user facts, backed by a single JSON file
 * `<rootDir>/profile.json` written atomically (temp file + rename) — the same pattern as
 * [com.andharness.ru.data.skill.SkillStore] and [com.andharness.ru.data.calls.CallRulesStore].
 *
 * It is deliberately **Android-free** (it only takes a directory [File]), so CRUD, bounds, dedupe and
 * atomic persistence are exercised on the JVM (`GlobalProfileStoreTest`); the DI module supplies
 * `profile` under `context.filesDir`.
 *
 * All access is `@Synchronized`; a corrupt/foreign file degrades to an empty list instead of
 * throwing, and a failed disk write leaves the in-memory state unchanged (so a failed save is
 * visible rather than silently reported as success).
 *
 * References: https://kotlinlang.org/api/kotlinx.serialization/ (JSON codec) and
 * https://developer.android.com/training/data-storage/app-specific (the DI binding stores it under
 * the app-private `context.filesDir`, so no storage permission is ever needed).
 */
class GlobalProfileStore(private val rootDir: File) {

    private val file = File(rootDir, FILE_NAME)

    private val _entries = MutableStateFlow(load())

    /** Current facts as an observable list (storage order, oldest written first). */
    val entries: StateFlow<List<ProfileFact>> = _entries.asStateFlow()

    /** All saved facts, oldest written first. Never throws. */
    @Synchronized
    fun list(): List<ProfileFact> = _entries.value

    /** Number of saved facts. */
    @Synchronized
    fun count(): Int = _entries.value.size

    /** Finds a fact by exact id, or null. */
    @Synchronized
    fun get(id: String): ProfileFact? = _entries.value.firstOrNull { it.id == id }

    /**
     * Saves or updates one fact.
     *
     * - `id == null` → append a new fact; if the same text (case-insensitive) is already saved, the
     *   existing entry is returned as [ProfileWriteOutcome.DUPLICATE] and nothing is written;
     * - `id != null` → update that fact's text (bumping its position to the end), or
     *   [ProfileWriteOutcome.NOT_FOUND] when no such id exists; updating to a text that already
     *   belongs to another fact also reports [ProfileWriteOutcome.DUPLICATE].
     *
     * Blank or over-long text is rejected as [ProfileWriteOutcome.INVALID].
     */
    @Synchronized
    fun put(id: String?, text: String, now: Long = System.currentTimeMillis()): ProfileWriteResult {
        val clean = ProfileRules.normalizeText(text)
            ?: return ProfileWriteResult(null, ProfileWriteOutcome.INVALID)
        val current = _entries.value

        if (id.isNullOrBlank()) {
            current.firstOrNull { ProfileRules.sameText(it.text, clean) }?.let {
                return ProfileWriteResult(it, ProfileWriteOutcome.DUPLICATE)
            }
            val fact = ProfileFact(nextId(current), clean, now, now)
            persist(current + fact)
            return ProfileWriteResult(fact, ProfileWriteOutcome.CREATED)
        }

        val existing = current.firstOrNull { it.id == id }
            ?: return ProfileWriteResult(null, ProfileWriteOutcome.NOT_FOUND)
        if (ProfileRules.sameText(existing.text, clean)) {
            return ProfileWriteResult(existing, ProfileWriteOutcome.DUPLICATE)
        }
        current.firstOrNull { it.id != id && ProfileRules.sameText(it.text, clean) }?.let {
            return ProfileWriteResult(it, ProfileWriteOutcome.DUPLICATE)
        }
        val updated = existing.copy(text = clean, updatedAt = now)
        // Move the updated fact to the end, so storage order = least recently written first and the
        // bounded drop removes the stalest fact.
        persist(current.filterNot { it.id == id } + updated)
        return ProfileWriteResult(updated, ProfileWriteOutcome.UPDATED)
    }

    /** Removes one fact by exact id; returns true only when something was removed. */
    @Synchronized
    fun delete(id: String): Boolean {
        val current = _entries.value
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return false
        persist(next)
        return true
    }

    /** Removes every saved fact; returns true only when there was something to clear. */
    @Synchronized
    fun clear(): Boolean {
        if (_entries.value.isEmpty()) return false
        persist(emptyList())
        return true
    }

    /**
     * The bounded prompt-context block for the current profile ("" when empty). Never throws.
     */
    @Synchronized
    fun renderBlock(limit: Int = ProfileRules.MAX_BLOCK_CHARS): String =
        ProfileRules.renderBlock(_entries.value, limit)

    private fun load(): List<ProfileFact> = try {
        if (file.isFile) GlobalProfileCodec.decode(file.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /** Appends [fact] while keeping at most [ProfileRules.MAX_ENTRIES] newest facts. */
    private fun persist(next: List<ProfileFact>) {
        val bounded = if (next.size <= ProfileRules.MAX_ENTRIES) {
            next
        } else {
            next.takeLast(ProfileRules.MAX_ENTRIES)
        }
        try {
            rootDir.mkdirs()
            val temp = File(rootDir, "$FILE_NAME.tmp")
            val encoded = GlobalProfileCodec.encode(bounded)
            temp.writeText(encoded)
            if (!temp.renameTo(file)) {
                // Rename can fail on some filesystems; fall back to a direct write so the save still
                // lands, and clean up the temp file.
                file.writeText(encoded)
                temp.delete()
            }
            _entries.value = bounded
        } catch (_: Exception) {
            // Best-effort: keep the in-memory state unchanged so a failed disk write is visible
            // (the list simply does not change) rather than silently reporting success.
        }
    }

    private fun nextId(existing: List<ProfileFact>): String {
        val taken = existing.mapTo(HashSet()) { it.id }
        while (true) {
            val candidate = "p" + UUID.randomUUID().toString().take(8)
            if (candidate !in taken) return candidate
        }
    }

    companion object {
        /** Subdirectory of `filesDir` that holds the profile file. */
        const val DIR_NAME = "profile"

        /** Single JSON file inside [DIR_NAME]. */
        const val FILE_NAME = "profile.json"
    }
}
