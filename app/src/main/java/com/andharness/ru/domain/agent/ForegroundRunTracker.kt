package com.andharness.ru.domain.agent

/**
 * Process-scoped reference count of the chats whose agent turn currently needs the shared
 * [com.andharness.ru.bg.AgentForegroundService] to stay alive.
 *
 * Why it exists: the app now runs up to `RunConcurrencyLimiter` turns at once (a per-chat registry),
 * but there is ONE foreground service for all of them. Without a reference count, the FIRST run that
 * finishes stops the service (`stopForeground`/`stopSelf`), and a still-running sibling turn loses its
 * foreground protection — the OS may then kill the process (and the turn) even though work remains.
 * This tracker keeps the service alive until the LAST turn leaves.
 *
 * Android background-execution / foreground-service rules this backs (the service is `dataSync`):
 * - https://developer.android.com/develop/background-work/services/fgs
 * - https://developer.android.com/develop/background-work/background-tasks
 * - https://developer.android.com/about/versions/14/changes/fgs-types-required
 *
 * Pure and Android-free on purpose: the count/keep-alive semantics are unit-tested on the JVM.
 */
class ForegroundRunTracker {

    private val chats = mutableSetOf<Long>()

    /** Registers [chatId]; idempotent. @return true when it was not already registered. */
    @Synchronized
    fun add(chatId: Long): Boolean = chats.add(chatId)

    /**
     * Unregisters [chatId]. @return true when NO run remains, i.e. the shared foreground service may
     * now be stopped; false while another chat's turn is still running (the service must stay up).
     */
    @Synchronized
    fun remove(chatId: Long): Boolean {
        chats.remove(chatId)
        return chats.isEmpty()
    }

    /** How many chats currently hold the shared foreground service (0 = it may be stopped). */
    @Synchronized
    fun size(): Int = chats.size
}
