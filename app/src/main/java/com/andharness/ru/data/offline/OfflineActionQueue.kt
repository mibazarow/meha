package com.andharness.ru.data.offline

import com.andharness.ru.domain.offline.OfflineQueueCodec
import com.andharness.ru.domain.offline.OfflineQueuePolicy
import com.andharness.ru.domain.offline.OfflineQueuedAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * Persistent queue of tool calls that failed for lack of network, retried later by
 * [com.andharness.ru.bg.OfflineActionWorker] (§2 "Очередь действий offline").
 *
 * Like `SkillStore` it is deliberately **Android-free** — it only takes a directory [File] — so its
 * CRUD, bounding, attempt accounting and persistence are exercised on the JVM with a temp dir
 * (`OfflineActionQueueTest`); the DI module supplies the directory under `context.filesDir`.
 *
 * Backed by a single JSON file `<rootDir>/offline_queue.json` written atomically (temp file +
 * rename). The size is bounded by [OfflineQueueCodec.MAX_ACTIONS] (~50): enqueuing at the cap drops
 * the OLDEST entry. A corrupt/foreign file degrades to an empty queue instead of throwing.
 *
 * All mutation is `@Synchronized`; [count] is a [StateFlow] so a UI banner/indicator can observe the
 * pending-actions count without touching the file (the count is a plain derived value, cheap to
 * publish on every change).
 */
class OfflineActionQueue(private val rootDir: File) {

    private val file = File(rootDir, FILE_NAME)

    private val _actions = MutableStateFlow(load())

    /** Current queue, oldest first. */
    val actions: StateFlow<List<OfflineQueuedAction>> = _actions.asStateFlow()

    private val _count = MutableStateFlow(_actions.value.size)

    /** Observable number of pending actions — enough for a UI badge/banner. */
    val count: StateFlow<Int> = _count.asStateFlow()

    /** All queued actions, oldest first. Never throws. */
    @Synchronized
    fun list(): List<OfflineQueuedAction> = _actions.value

    /** Number of queued actions. */
    fun count(): Int = list().size

    /**
     * Enqueues the original [toolName]/[argumentsJson] call after a network failure.
     *
     * Only a call that both [OfflineQueuePolicy.isEligibleTool] and failed for a network reason
     * ([OfflineQueuePolicy.isNetworkError]) is accepted — the policy is re-applied here so a caller
     * cannot bypass it. A blank tool name is rejected (returns null). The new entry starts at
     * `attempts = 0`; [OfflineQueueCodec.MAX_ACTIONS] is enforced by dropping the oldest.
     */
    @Synchronized
    fun enqueue(
        toolName: String,
        argumentsJson: String,
        error: String,
        now: Long = System.currentTimeMillis(),
        id: String = UUID.randomUUID().toString(),
    ): OfflineQueuedAction? {
        if (toolName.isBlank()) return null
        if (!OfflineQueuePolicy.shouldQueue(toolName, error)) return null
        val action = OfflineQueuedAction(
            id = id,
            toolName = toolName,
            argumentsJson = argumentsJson,
            createdAt = now,
            attempts = 0,
            lastError = error.take(MAX_ERROR_CHARS),
        )
        val next = (_actions.value + action).takeLast(OfflineQueueCodec.MAX_ACTIONS)
        persist(next)
        return action
    }

    /**
     * Records one failed drain attempt: increments [OfflineQueuedAction.attempts] and stores the
     * honest [error]. Does NOT remove the entry — that is the drain policy's call. Returns the
     * updated action, or null when [id] is unknown.
     */
    @Synchronized
    fun recordFailure(
        id: String,
        error: String,
    ): OfflineQueuedAction? {
        val current = _actions.value.firstOrNull { it.id == id } ?: return null
        val updated = current.copy(
            attempts = current.attempts + 1,
            lastError = error.take(MAX_ERROR_CHARS),
        )
        persist(_actions.value.map { if (it.id == id) updated else it })
        return updated
    }

    /** Removes an action by id. Returns true only when something was removed. */
    @Synchronized
    fun remove(id: String): Boolean {
        val current = _actions.value
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return false
        persist(next)
        return true
    }

    private fun load(): List<OfflineQueuedAction> = try {
        if (file.isFile) OfflineQueueCodec.decode(file.readText()) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun persist(actions: List<OfflineQueuedAction>) {
        try {
            rootDir.mkdirs()
            val payload = OfflineQueueCodec.encode(actions)
            val temp = File(rootDir, "$FILE_NAME.tmp")
            temp.writeText(payload)
            if (!temp.renameTo(file)) {
                // Rename can fail on some filesystems; fall back to a direct write so the save still
                // lands, and clean up the temp file.
                file.writeText(payload)
                temp.delete()
            }
            _actions.value = actions
            _count.value = actions.size
        } catch (_: Exception) {
            // Best-effort: keep the in-memory state unchanged so a failed disk write is visible
            // (the queue simply does not change) rather than silently reporting success.
        }
    }

    companion object {
        /** Subdirectory of `filesDir` that holds the offline queue. */
        const val DIR_NAME = "offline_queue"

        /** Single JSON file inside [DIR_NAME]. */
        const val FILE_NAME = "offline_queue.json"

        /** Bound on the stored per-item error text, so the file cannot grow from a huge stack trace. */
        const val MAX_ERROR_CHARS = 500
    }
}
