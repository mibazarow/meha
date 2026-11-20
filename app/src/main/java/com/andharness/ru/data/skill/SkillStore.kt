package com.andharness.ru.data.skill

import com.andharness.ru.domain.skill.Skill
import com.andharness.ru.domain.skill.SkillCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Persistent library of app skills (M2 slice 1), backed by a single JSON file
 * `<rootDir>/skills.json` written atomically (temp file + rename). It is deliberately **Android-free**
 * — it only takes a directory [File] — so the whole store is exercised on the JVM with a temp dir
 * (`SkillStoreTest`); the DI module supplies `skills` under `context.filesDir`.
 *
 * A file-backed (not Room) store is a deliberate choice for this slice: the schema is young and this
 * avoids a Room migration per iteration, mirroring `UiMacroStore`/`TrustedSourceStore`. The count is
 * bounded by [SkillCodec.MAX_SKILLS] (oldest dropped) so the file/context can never grow unchecked.
 *
 * All access is `@Synchronized`; a corrupt/foreign file degrades to an empty list instead of throwing.
 */
class SkillStore(private val rootDir: File) {

    private val file = File(rootDir, FILE_NAME)

    private val _skills = MutableStateFlow(load())

    /** Current library as an observable list (insertion order, oldest first). */
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    /** All skills, oldest first. Never throws. */
    @Synchronized
    fun list(): List<Skill> = _skills.value

    fun count(): Int = list().size

    /** Finds a skill by exact id first, then by exact name (case-insensitive fallback). */
    @Synchronized
    fun get(idOrName: String): Skill? = SkillCodec.find(_skills.value, idOrName)

    /**
     * Adds or replaces a skill by [Skill.id], keeping the newest [SkillCodec.MAX_SKILLS]. Returns the
     * stored skill. A blank id/name is rejected (returns null) — an anonymous skill is never saved.
     */
    @Synchronized
    fun save(skill: Skill): Skill? {
        if (skill.id.isBlank() || skill.name.isBlank()) return null
        val next = (_skills.value.filterNot { it.id == skill.id } + skill).takeLast(SkillCodec.MAX_SKILLS)
        persist(next)
        return skill
    }

    /** Removes a skill by id or name; returns true only when something was removed. */
    @Synchronized
    fun delete(idOrName: String): Boolean {
        val skill = SkillCodec.find(_skills.value, idOrName) ?: return false
        persist(_skills.value.filterNot { it.id == skill.id })
        return true
    }

    /**
     * Records a successful real run of a skill by stamping its [Skill.lastUsedAt] with [now] and
     * persisting. Only touches the timestamp — [Skill.updatedAt] (content change) is left alone.
     * Returns true only when a skill matched; a no-op (false) for an unknown id/name.
     */
    @Synchronized
    fun touch(idOrName: String, now: Long = System.currentTimeMillis()): Boolean {
        val skill = SkillCodec.find(_skills.value, idOrName) ?: return false
        persist(_skills.value.map { if (it.id == skill.id) it.copy(lastUsedAt = now) else it })
        return true
    }

    private fun load(): List<Skill> = try {
        if (file.isFile) SkillCodec.decodeSkills(file.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun persist(skills: List<Skill>) {
        try {
            rootDir.mkdirs()
            val temp = File(rootDir, "$FILE_NAME.tmp")
            temp.writeText(SkillCodec.encodeSkills(skills))
            if (!temp.renameTo(file)) {
                // Rename can fail on some filesystems; fall back to a direct write so the save still
                // lands, and clean up the temp file.
                file.writeText(SkillCodec.encodeSkills(skills))
                temp.delete()
            }
            _skills.value = skills
        } catch (_: Exception) {
            // Best-effort: keep the in-memory state unchanged so a failed disk write is visible
            // (the list simply does not change) rather than silently reporting success.
        }
    }

    companion object {
        /** Subdirectory of `filesDir` that holds the skill library. */
        const val DIR_NAME = "skills"

        /** Single JSON file inside [DIR_NAME]. */
        const val FILE_NAME = "skills.json"
    }
}
