package com.andharness.ru.domain.chat

import com.andharness.ru.domain.tools.currentToolChatId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Access to the chat of the agent run a tool handler is executing in, plus a best-effort tracker
 * for crash recovery.
 *
 * **[chatId] is per-run, never global.** It reads the [com.andharness.ru.domain.tools.ToolExecutionContext]
 * coroutine element the orchestrator installs around the tool loop, so with the concurrent per-chat
 * runs (SPEC §20) a tool executing for chat A always sees chat A — two simultaneous runs can never
 * observe each other's chat. It returns null outside an agent run (e.g. a direct `self_diagnostics`
 * call, or the tool registry in a unit test).
 *
 * [lastActiveChatIdForCrash] is a separate, deliberately process-global best-effort value used ONLY
 * by [com.andharness.ru.data.crash.CrashGuard] to snapshot the unfinished task when the process is
 * about to die (a crash unwind has no live coroutine context to read, and "which chat was last
 * active" is inherently process state). It is NEVER consulted for tool dispatch.
 */
@Singleton
class CurrentChatProvider @Inject constructor() {

    /** Chat of the agent run this coroutine belongs to, or null outside an agent run. */
    suspend fun chatId(): Long? = currentToolChatId()

    @Volatile
    private var lastActiveForCrash: Long? = null

    /**
     * Best-effort "last chat an agent run started in", for the CrashGuard snapshot ONLY. Written when
     * a run starts; deliberately not cleared on finish so a crash before the next run still has a
     * snapshot to point at.
     */
    fun lastActiveChatIdForCrash(): Long? = lastActiveForCrash

    fun noteLastActiveChatForCrash(chatId: Long) {
        lastActiveForCrash = chatId
    }
}
