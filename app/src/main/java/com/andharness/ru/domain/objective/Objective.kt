package com.andharness.ru.domain.objective

import kotlinx.serialization.Serializable

/** Lifecycle of a long-running objective (checklist §1 «Долгие задачи с планом и чекпоинтами»). */
@Serializable
enum class ObjectiveStatus {
    /** Being worked on (or ready to be continued); injected into the chat's prompt. */
    ACTIVE,

    /** Deliberately set aside; NOT injected and never auto-continued until explicitly resumed. */
    PAUSED,

    /** Finished successfully. Terminal. */
    DONE,

    /** Abandoned. Terminal. */
    CANCELLED
}

/** Lifecycle of a single plan step. */
@Serializable
enum class ObjectiveStepStatus {
    PENDING,
    IN_PROGRESS,
    DONE,
    BLOCKED
}

/**
 * One checkpoint of an objective. [note] carries an honest short result/blocker line the agent
 * writes as it works (`objective_step_update`), so a resumed run knows what actually happened.
 */
@Serializable
data class ObjectiveStep(
    val id: String,
    val text: String,
    val status: ObjectiveStepStatus = ObjectiveStepStatus.PENDING,
    val note: String = ""
)

/**
 * A persistent, multi-turn objective: a goal plus an ordered list of steps. It is owned by one chat
 * ([chatId]) and survives an app/process restart because it is stored as JSON on disk. Only the
 * ACTIVE objective of the CURRENT chat is injected into the system prompt (see [ObjectiveRules.renderBlock]),
 * which is what makes the agent able to RESUME it in a later session.
 */
@Serializable
data class Objective(
    val id: String,
    val title: String,
    val goal: String,
    val status: ObjectiveStatus = ObjectiveStatus.ACTIVE,
    val steps: List<ObjectiveStep> = emptyList(),
    val chatId: Long? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastWorkedAt: Long = 0L,
    val iterations: Int = 0,
    /**
     * How many BACKGROUND steps ([ObjectiveRunner]) this objective has consumed. Counted separately
     * from [iterations] (which also counts interactive `objective_next`/`objective_step_update` calls)
     * so the background run budget ([ObjectiveRunGate.MAX_BACKGROUND_RUNS]) cannot be exhausted by
     * ordinary chat use.
     */
    val backgroundRuns: Int = 0
)

/** Progress of an objective: how many steps are DONE out of the total. */
data class ObjectiveProgress(val done: Int, val total: Int)

/** How a state-changing objective operation ended, so a tool can answer honestly. */
enum class ObjectiveOutcome {
    /** The change was applied and persisted. */
    CHANGED,

    /** No objective matched the given id/title. */
    NOT_FOUND,

    /** The objective is already DONE/CANCELLED — a terminal state does not change. */
    NOOP_TERMINAL,

    /** The requested change is a no-op because the objective is already in that state. */
    NOOP_SAME,

    /** The arguments were invalid (blank title/step text, unparseable status). */
    INVALID
}

/** Result of a state-changing objective operation. [objective] is the state AFTER the call. */
data class ObjectiveResult(val objective: Objective?, val outcome: ObjectiveOutcome)

/** Result of a step update. [step] is the updated step when [outcome] is CHANGED. */
data class ObjectiveStepResult(
    val objective: Objective?,
    val step: ObjectiveStep?,
    val outcome: ObjectiveOutcome
)

/**
 * Pure, Android-free rules of the objective feature: bounds, progress, next-step selection, the
 * compact prompt block and the resume decision. Kept separate from [com.andharness.ru.data.objective.ObjectiveStore]
 * so step transitions, progress and rendering are unit-testable on the JVM without Android or disk.
 */
object ObjectiveRules {

    /** Hard cap of stored objectives; ACTIVE ones are never evicted (see [bound]). */
    const val MAX_OBJECTIVES = 50

    /** Hard cap of steps per objective. */
    const val MAX_STEPS = 20

    const val MAX_TITLE_CHARS = 120
    const val MAX_GOAL_CHARS = 600
    const val MAX_STEP_CHARS = 240
    const val MAX_NOTE_CHARS = 400

    /** Hard cap of the injected `АКТИВНАЯ ЦЕЛЬ` prompt block. */
    const val MAX_BLOCK_CHARS = 900

    /** How much of the goal is shown inside the prompt block. */
    const val GOAL_PREVIEW_CHARS = 300

    /** How much of the next step's text is shown inside the prompt block. */
    const val STEP_PREVIEW_CHARS = 200

    /** Header of the injected block; also the anchor asserted by tests. */
    const val HEADER = "АКТИВНАЯ ЦЕЛЬ:"

    /**
     * Trims [raw] and caps it to [max] characters; returns null when blank. Used for every free-text
     * field so a runaway model string can never bloat the JSON file or the prompt.
     */
    fun normalize(raw: String?, max: Int): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return if (trimmed.length > max) trimmed.take(max) else trimmed
    }

    /** True for the terminal statuses (DONE/CANCELLED). */
    fun isTerminal(status: ObjectiveStatus): Boolean =
        status == ObjectiveStatus.DONE || status == ObjectiveStatus.CANCELLED

    /** Progress = DONE steps / all steps. */
    fun progress(objective: Objective): ObjectiveProgress {
        val done = objective.steps.count { it.status == ObjectiveStepStatus.DONE }
        return ObjectiveProgress(done = done, total = objective.steps.size)
    }

    /**
     * The step the agent should work on next: the first IN_PROGRESS step, otherwise the first
     * PENDING one. null when there is nothing left (all DONE/BLOCKED or no steps).
     */
    fun nextStep(objective: Objective): ObjectiveStep? =
        objective.steps.firstOrNull { it.status == ObjectiveStepStatus.IN_PROGRESS }
            ?: objective.steps.firstOrNull { it.status == ObjectiveStepStatus.PENDING }

    /** The most recently updated ACTIVE objective bound to [chatId], or null. */
    fun activeForChat(objectives: List<Objective>, chatId: Long?): Objective? {        if (chatId == null) return null
        return objectives
            .filter { it.status == ObjectiveStatus.ACTIVE && it.chatId == chatId }
            .maxByOrNull { it.updatedAt }
    }

    /**
     * The first step that is not DONE (PENDING / IN_PROGRESS / BLOCKED), or null when every step is
     * DONE / there are no steps. This is the step background execution is genuinely "up to".
     */
    fun firstUnfinished(objective: Objective): ObjectiveStep? =
        objective.steps.firstOrNull { it.status != ObjectiveStepStatus.DONE }

    /**
     * True when the objective cannot be auto-continued because its first unfinished step is BLOCKED.
     * A BLOCKED objective is never auto-run until the user/agent unblocks that step explicitly.
     */
    fun isBlocked(objective: Objective): Boolean =
        firstUnfinished(objective)?.status == ObjectiveStepStatus.BLOCKED

    /**
     * Caps [objectives] to [MAX_OBJECTIVES] without ever evicting an ACTIVE one: the stalest
     * terminal/PAUSED entries are dropped first, then the list is ordered newest-updated-first.
     */
    fun bound(objectives: List<Objective>): List<Objective> {
        val sorted = objectives.sortedByDescending { it.updatedAt }
        if (sorted.size <= MAX_OBJECTIVES) return sorted
        val active = sorted.filter { it.status == ObjectiveStatus.ACTIVE }.take(MAX_OBJECTIVES)
        val rest = sorted.filter { it.status != ObjectiveStatus.ACTIVE }
        val room = (MAX_OBJECTIVES - active.size).coerceAtLeast(0)
        return (active + rest.take(room)).sortedByDescending { it.updatedAt }
    }

    /**
     * Renders the compact prompt block for [objective]. Returns "" when [objective] is null or not
     * ACTIVE, so unrelated chats (and terminal objectives) inject nothing. Bounded to
     * [MAX_BLOCK_CHARS]; the tail instruction tells the model how to continue honestly and to
     * checkpoint via `objective_step_update`.
     */
    fun renderBlock(objective: Objective?, limit: Int = MAX_BLOCK_CHARS): String {
        if (objective == null || objective.status != ObjectiveStatus.ACTIVE) return ""
        val progress = progress(objective)
        val next = nextStep(objective)
        val text = buildString {
            append(HEADER).append(' ').append(objective.title)
            append("\nЦель: ").append(preview(objective.goal, GOAL_PREVIEW_CHARS))
            append("\nПрогресс: ").append(progress.done).append('/').append(progress.total)
                .append(" шагов")
            if (next != null) {
                append("\nСледующий шаг [").append(next.id).append("]: ")
                    .append(preview(next.text, STEP_PREVIEW_CHARS))
            } else if (progress.total > 0 && progress.done == progress.total) {
                append("\nВсе шаги выполнены — заверши цель через objective_finish.")
            }
            append(
                "\nПродолжай эту цель: делай следующий шаг и после каждого шага обновляй его статус " +
                    "через objective_step_update (следующий шаг — objective_next). Не выдумывай " +
                    "выполненное: отмечай done только реально сделанное."
            )
        }
        return if (text.length <= limit) text else text.take(limit - 1) + "…"
    }

    private fun preview(value: String, max: Int): String {
        val flat = value.replace('\n', ' ').trim()
        return if (flat.length <= max) flat else flat.take(max) + "…"
    }
}
