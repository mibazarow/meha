package com.andharness.ru.domain.tools

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/**
 * Per-RUN agent context (chat + model), carried in the **coroutine context** of the agent turn
 * instead of a shared mutable singleton. [com.andharness.ru.data.agent.AgentOrchestratorImpl]
 * installs exactly one such element around the tool-execution loop
 * (`withContext(ToolExecutionContext(chatId, model))`), so every tool handler and the audit hook at
 * the single dispatch point
 * ([com.andharness.ru.data.tools.ToolRegistryImpl.execute]) read the chat/model of ITS OWN run.
 *
 * Why a coroutine element and not a `@Volatile` field: with the concurrent per-chat runs
 * (SPEC §20) a single "current chat" would be "last writer wins", so a tool executing for chat A
 * could act on chat B's id. A coroutine-context element is confined to the coroutine that owns the
 * run and is inherited by nested `withContext(Dispatchers.IO)` blocks, so two simultaneous runs can
 * never observe each other's chat. This is the idiomatic Kotlin approach to dynamic, per-coroutine
 * data (the coroutine analogue of a thread-local):
 * - `CoroutineContext.Element` / `AbstractCoroutineContextElement`:
 *   https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-abstract-coroutine-context-element/
 * - `currentCoroutineContext()`:
 *   https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/current-coroutine-context.html
 * - shared mutable state / confinement:
 *   https://kotlinlang.org/docs/shared-mutable-state-and-concurrency.html
 *
 * A `ThreadContextElement` is deliberately NOT used: tool handlers are suspend functions and never
 * hand work to their own threads, so there is no thread to confine to.
 */
class ToolExecutionContext(
    val chatId: Long?,
    val model: String?,
) : AbstractCoroutineContextElement(ToolExecutionContext) {

    companion object Key : CoroutineContext.Key<ToolExecutionContext>
}

/** The full per-run context of the agent turn this coroutine belongs to, or null outside a run. */
suspend fun currentToolExecutionContext(): ToolExecutionContext? =
    currentCoroutineContext()[ToolExecutionContext]

/** Chat id of the agent run this coroutine belongs to, or null outside a run. */
suspend fun currentToolChatId(): Long? = currentToolExecutionContext()?.chatId
