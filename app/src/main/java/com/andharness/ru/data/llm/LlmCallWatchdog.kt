package com.andharness.ru.data.llm

import okhttp3.Call
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * External, thread-based abort for ONE in-flight streaming [Call].
 *
 * `withTimeout` and coroutine watchdogs are COOPERATIVE: they cancel the coroutine's `Job`, and a
 * coroutine that never suspends again (or a `suspendCancellableCoroutine` whose callback never
 * arrives) can defeat them. The abort therefore must not depend on the collecting coroutine at all.
 *
 * This class owns a plain daemon [Timer] — its own thread, completely independent of every
 * coroutine dispatcher — and calls [Call.cancel] when a budget is exhausted. Cancelling the OkHttp
 * call closes the socket and unblocks the reader with an `IOException`, so the abort lands even if
 * the collector is wedged and never checks `ensureActive()`.
 *
 * Two independent budgets are enforced:
 * - **idle**: no REAL stream progress for [idleProgressTimeoutSec] (SSE keep-alive comments and
 *   heartbeats do NOT count, so a server that dribbles bytes forever cannot reset it);
 * - **overall**: the call has been running for [maxCompletionSec] — a hard wall-clock cap.
 *
 * References:
 * - OkHttp `Call.cancel()`: https://square.github.io/okhttp/4/okhttp/okhttp3/Call/#cancel
 * - `java.util.Timer`: https://developer.android.com/reference/java/util/Timer
 */
internal class LlmCallWatchdog(
    private val call: Call,
    private val idleProgressTimeoutSec: Long,
    private val maxCompletionSec: Long,
    private val tickMs: Long = DEFAULT_TICK_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val startedAtMs = AtomicLong(clock())
    private val lastProgressAtMs = AtomicLong(clock())
    private val reason = AtomicReference<String?>(null)
    private val fired = AtomicBoolean(false)
    private var timer: Timer? = null

    /** Starts the watchdog thread. Repeated calls are ignored while it is already running. */
    fun start() {
        if (timer != null) return
        val t = Timer("llm-call-watchdog", /* isDaemon = */ true)
        timer = t
        t.scheduleAtFixedRate(
            object : TimerTask() {
                override fun run() = check()
            },
            tickMs,
            tickMs,
        )
    }

    /**
     * Refreshes the idle budget. Call this ONLY for a REAL content/tool-call delta — an SSE
     * keep-alive is not progress and must not extend the idle budget.
     */
    fun noteProgress() {
        lastProgressAtMs.set(clock())
    }

    /** The honest abort reason, or `null` when the watchdog never fired. */
    fun abortReason(): String? = reason.get()

    /** Stops the watchdog thread. Safe to call from any thread and more than once. */
    fun stop() {
        timer?.cancel()
        timer = null
    }

    private fun check() {
        if (fired.get()) return
        val now = clock()
        if (now - lastProgressAtMs.get() >= idleProgressTimeoutSec * 1000L) {
            fire(
                "Модель не присылает новых данных уже более $idleProgressTimeoutSec секунд " +
                    "(поток завис) — генерация прервана."
            )
            return
        }
        if (now - startedAtMs.get() >= maxCompletionSec * 1000L) {
            fire(
                "Генерация длится дольше ${maxCompletionSec / 60} минут " +
                    "(общий лимит на один ответ) — прервано."
            )
        }
    }

    private fun fire(message: String) {
        if (!fired.compareAndSet(false, true)) return
        reason.set(message)
        call.cancel()
    }

    companion object {
        const val DEFAULT_TICK_MS = 1_000L
    }
}
