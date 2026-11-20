package com.andharness.ru.data.objective

import com.andharness.ru.domain.objective.Objective
import com.andharness.ru.domain.objective.ObjectiveOutcome
import com.andharness.ru.domain.objective.ObjectiveResult
import com.andharness.ru.domain.objective.ObjectiveRules
import com.andharness.ru.domain.objective.ObjectiveStatus
import com.andharness.ru.domain.objective.ObjectiveStep
import com.andharness.ru.domain.objective.ObjectiveStepResult
import com.andharness.ru.domain.objective.ObjectiveStepStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import com.andharness.ru.domain.serialization.AppJson
import java.io.File
import java.util.UUID

/**
 * Persistent, file-backed store of [Objective]s, written atomically (temp file + rename) to
 * `<rootDir>/objectives.json` — the same pattern as [com.andharness.ru.data.profile.GlobalProfileStore]
 * and [com.andharness.ru.data.skill.SkillStore].
 *
 * It is deliberately **Android-free** (it only takes a directory [File]), so CRUD, bounds, atomic
 * persistence, step transitions, next-step selection and progress are exercised on the JVM
 * (`ObjectiveStoreTest`); the DI module supplies `objective` under `context.filesDir`.
 *
 * All access is `@Synchronized`. A corrupt/foreign file degrades to an empty list instead of
 * throwing, and a failed disk write leaves the in-memory state unchanged (so a failed save is
 * visible rather than silently reported as success). This is what makes an objective survive an
 * app/process restart and be RESUMABLE in a later session.
 *
 * References: https://kotlinlang.org/api/kotlinx.serialization/ (JSON codec),
 * https://developer.android.com/training/data-storage/app-specific (the DI binding stores it under
 * the app-private `context.filesDir`, so no storage permission is ever needed).
 */
class ObjectiveStore(private val rootDir: File) {

    private val file = File(rootDir, FILE_NAME)

    private val _objectives = MutableStateFlow(load())

    /** All objectives, newest-updated first, observable. */
    val objectives: StateFlow<List<Objective>> = _objectives.asStateFlow()

    /** All objectives, newest-updated first. Never throws. */
    @Synchronized
    fun list(): List<Objective> = _objectives.value

    /** Number of stored objectives. */
    @Synchronized
    fun count(): Int = _objectives.value.size

    /** Finds an objective by exact id first, then by case-insensitive title. */
    @Synchronized
    fun get(idOrTitle: String): Objective? {
        val needle = idOrTitle.trim()
        if (needle.isEmpty()) return null
        return _objectives.value.firstOrNull { it.id == needle }
            ?: _objectives.value.firstOrNull { it.title.equals(needle, ignoreCase = true) }
    }

    /** The most recently updated ACTIVE objective bound to [chatId], or null. */
    @Synchronized
    fun activeForChat(chatId: Long?): Objective? =
        ObjectiveRules.activeForChat(_objectives.value, chatId)

    /**
     * Creates a new ACTIVE objective from [steps] (plain step texts), bound to [chatId] when given.
     * Blank/over-long input is normalized by [ObjectiveRules]; a blank title or goal yields null so
     * the caller can report [ObjectiveOutcome.INVALID] instead of storing an unusable objective.
     */
    @Synchronized
    fun create(
        title: String?,
        goal: String?,
        steps: List<String>,
        chatId: Long?,
        now: Long = System.currentTimeMillis()
    ): Objective? {
        val cleanTitle = ObjectiveRules.normalize(title, ObjectiveRules.MAX_TITLE_CHARS) ?: return null
        val cleanGoal = ObjectiveRules.normalize(goal, ObjectiveRules.MAX_GOAL_CHARS) ?: return null
        val cleanSteps = steps.asSequence()
            .mapNotNull { ObjectiveRules.normalize(it, ObjectiveRules.MAX_STEP_CHARS) }
            .take(ObjectiveRules.MAX_STEPS)
            .toList()
        val objective = Objective(
            id = nextId(),
            title = cleanTitle,
            goal = cleanGoal,
            status = ObjectiveStatus.ACTIVE,
            steps = cleanSteps.mapIndexed { index, text ->
                ObjectiveStep(id = stepId(index), text = text)
            },
            chatId = chatId,
            createdAt = now,
            updatedAt = now,
            lastWorkedAt = now,
            iterations = 0
        )
        persist(_objectives.value + objective)
        return objective
    }

    /**
     * Inserts or replaces [objective] by id. A fresh id appends; an existing id replaces in place
     * (its `updatedAt` is bumped to now so ordering and bounds stay truthful).
     */
    @Synchronized
    fun put(objective: Objective, now: Long = System.currentTimeMillis()): Objective {
        val stored = objective.copy(updatedAt = if (objective.updatedAt > 0) objective.updatedAt else now)
        val current = _objectives.value
        val next = if (current.any { it.id == stored.id }) {
            current.map { if (it.id == stored.id) stored else it }
        } else {
            current + stored
        }
        persist(next)
        return stored
    }

    /** Removes one objective by exact id; returns true only when something was removed. */
    @Synchronized
    fun delete(id: String): Boolean {
        val current = _objectives.value
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return false
        persist(next)
        return true
    }

    /** Removes every objective; returns true only when there was something to clear. */
    @Synchronized
    fun clear(): Boolean {
        if (_objectives.value.isEmpty()) return false
        persist(emptyList())
        return true
    }

    /**
     * Sets the objective's [status]. Terminal objectives are never reopened here (they report
     * [ObjectiveOutcome.NOOP_TERMINAL]); an unchanged status reports [ObjectiveOutcome.NOOP_SAME].
     */
    @Synchronized
    fun setStatus(idOrTitle: String, status: ObjectiveStatus, now: Long = System.currentTimeMillis()): ObjectiveResult {
        val existing = get(idOrTitle) ?: return ObjectiveResult(null, ObjectiveOutcome.NOT_FOUND)
        if (ObjectiveRules.isTerminal(existing.status)) {
            return ObjectiveResult(existing, ObjectiveOutcome.NOOP_TERMINAL)
        }
        if (existing.status == status) return ObjectiveResult(existing, ObjectiveOutcome.NOOP_SAME)
        val updated = existing.copy(status = status, updatedAt = now)
        persist(_objectives.value.map { if (it.id == updated.id) updated else it })
        return ObjectiveResult(updated, ObjectiveOutcome.CHANGED)
    }

    /**
     * Resumes a PAUSED objective (sets it ACTIVE). A DONE/CANCELLED objective cannot be resumed
     * (report [ObjectiveOutcome.NOOP_TERMINAL]); an already-ACTIVE one reports [ObjectiveOutcome.NOOP_SAME].
     */
    @Synchronized
    fun resume(idOrTitle: String, now: Long = System.currentTimeMillis()): ObjectiveResult {
        val existing = get(idOrTitle) ?: return ObjectiveResult(null, ObjectiveOutcome.NOT_FOUND)
        if (ObjectiveRules.isTerminal(existing.status)) {
            return ObjectiveResult(existing, ObjectiveOutcome.NOOP_TERMINAL)
        }
        if (existing.status == ObjectiveStatus.ACTIVE) {
            return ObjectiveResult(existing, ObjectiveOutcome.NOOP_SAME)
        }
        val updated = existing.copy(status = ObjectiveStatus.ACTIVE, updatedAt = now, lastWorkedAt = now)
        persist(_objectives.value.map { if (it.id == updated.id) updated else it })
        return ObjectiveResult(updated, ObjectiveOutcome.CHANGED)
    }

    /**
     * Updates one step's status/note. [stepRef] is a step id or a 1-based index. A terminal
     * objective is not edited ([ObjectiveOutcome.NOOP_TERMINAL]); an unknown step is
     * [ObjectiveOutcome.NOT_FOUND]; an unchanged (status and note) step is [ObjectiveOutcome.NOOP_SAME].
     */
    @Synchronized
    fun updateStep(
        idOrTitle: String,
        stepRef: String,
        status: ObjectiveStepStatus,
        note: String?,
        now: Long = System.currentTimeMillis()
    ): ObjectiveStepResult {
        val existing = get(idOrTitle) ?: return ObjectiveStepResult(null, null, ObjectiveOutcome.NOT_FOUND)
        if (ObjectiveRules.isTerminal(existing.status)) {
            return ObjectiveStepResult(existing, null, ObjectiveOutcome.NOOP_TERMINAL)
        }
        val index = resolveStepIndex(existing, stepRef)
            ?: return ObjectiveStepResult(existing, null, ObjectiveOutcome.NOT_FOUND)
        val current = existing.steps[index]
        val cleanNote = ObjectiveRules.normalize(note, ObjectiveRules.MAX_NOTE_CHARS).orEmpty()
        if (current.status == status && current.note == cleanNote) {
            return ObjectiveStepResult(existing, current, ObjectiveOutcome.NOOP_SAME)
        }
        val updatedStep = current.copy(status = status, note = cleanNote)
        val updatedObjective = existing.copy(
            steps = existing.steps.toMutableList().also { it[index] = updatedStep },
            updatedAt = now,
            lastWorkedAt = now
        )
        persist(_objectives.value.map { if (it.id == updatedObjective.id) updatedObjective else it })
        return ObjectiveStepResult(updatedObjective, updatedStep, ObjectiveOutcome.CHANGED)
    }

    /**
     * Marks the objective as worked on now and bumps [iterations]; used by `objective_next` so the
     * resume block reflects active progress. Never throws; null when the objective is unknown.
     */
    @Synchronized
    fun touch(idOrTitle: String, now: Long = System.currentTimeMillis()): Objective? {
        val existing = get(idOrTitle) ?: return null
        val updated = existing.copy(
            updatedAt = now,
            lastWorkedAt = now,
            iterations = existing.iterations + 1
        )
        persist(_objectives.value.map { if (it.id == updated.id) updated else it })
        return updated
    }

    /**
     * Records that ONE background step was attempted for the objective: bumps [Objective.backgroundRuns]
     * (the background run budget) as well as `lastWorkedAt`/`iterations`. Called by the runner BEFORE
     * the agent turn so the budget stays honest even if the turn fails. Never throws; null when unknown.
     */
    @Synchronized
    fun recordBackgroundRun(idOrTitle: String, now: Long = System.currentTimeMillis()): Objective? {
        val existing = get(idOrTitle) ?: return null
        val updated = existing.copy(
            updatedAt = now,
            lastWorkedAt = now,
            iterations = existing.iterations + 1,
            backgroundRuns = existing.backgroundRuns + 1
        )
        persist(_objectives.value.map { if (it.id == updated.id) updated else it })
        return updated
    }

    /**
     * The bounded `АКТИВНАЯ ЦЕЛЬ` prompt block for the ACTIVE objective of [chatId], or "" when
     * there is none. Never throws.
     */
    @Synchronized
    fun renderBlock(chatId: Long?, limit: Int = ObjectiveRules.MAX_BLOCK_CHARS): String =
        ObjectiveRules.renderBlock(activeForChat(chatId), limit)

    private fun resolveStepIndex(objective: Objective, stepRef: String): Int? {
        val ref = stepRef.trim()
        if (ref.isEmpty()) return null
        objective.steps.indexOfFirst { it.id == ref }.takeIf { it >= 0 }?.let { return it }
        val asIndex = ref.toIntOrNull() ?: return null
        // Accept a 1-based index (as shown to the model); also tolerate 0-based.
        val index = asIndex - 1
        return index.takeIf { it in objective.steps.indices }
            ?: asIndex.takeIf { it in objective.steps.indices }
    }

    private fun load(): List<Objective> = try {
        if (file.isFile) CODEC.decodeFromString(SERIALIZER, file.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun persist(next: List<Objective>) {
        val bounded = ObjectiveRules.bound(next)
        try {
            rootDir.mkdirs()
            val temp = File(rootDir, "$FILE_NAME.tmp")
            val encoded = CODEC.encodeToString(SERIALIZER, bounded)
            temp.writeText(encoded)
            if (!temp.renameTo(file)) {
                // Rename can fail on some filesystems; fall back to a direct write so the save still
                // lands, and clean up the temp file.
                file.writeText(encoded)
                temp.delete()
            }
            _objectives.value = bounded
        } catch (_: Exception) {
            // Best-effort: keep the in-memory state unchanged so a failed disk write is visible
            // (the list simply does not change) rather than silently reporting success.
        }
    }

    private fun nextId(): String {
        val taken = _objectives.value.mapTo(HashSet()) { it.id }
        while (true) {
            val candidate = "o" + UUID.randomUUID().toString().take(8)
            if (candidate !in taken) return candidate
        }
    }

    companion object {
        /** Subdirectory of `filesDir` that holds the objectives file. */
        const val DIR_NAME = "objective"

        /** Single JSON file inside [DIR_NAME]. */
        const val FILE_NAME = "objectives.json"

        private val CODEC = AppJson.LenientEncodeDefaults

        private val SERIALIZER = ListSerializer(Objective.serializer())

        /** Stable, human-addressable step id: `s1`, `s2`, … within one objective. */
        fun stepId(index: Int): String = "s" + (index + 1)
    }
}
