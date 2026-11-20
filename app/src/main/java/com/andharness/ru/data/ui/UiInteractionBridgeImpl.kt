package com.andharness.ru.data.ui

import com.andharness.ru.domain.ui.UiInteractionBridge
import com.andharness.ru.domain.ui.UiRequest
import com.andharness.ru.domain.ui.UiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single-slot [UiInteractionBridge]: at most one request may be in flight at a time, which keeps
 * the UI contract trivial (show one dialog/contract, then report back).
 *
 * Built on `CompletableDeferred` to hand a value across the UI/tool boundary once
 * (https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-completable-deferred/)
 * and `StateFlow` so the UI observes the pending request
 * (https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/).
 */
@Singleton
class UiInteractionBridgeImpl @Inject constructor() : UiInteractionBridge {

    private val _pending = MutableStateFlow<UiRequest?>(null)
    override val pending: StateFlow<UiRequest?> = _pending.asStateFlow()

    /** Guards the single request slot: `request` runs on an IO coroutine, `submit` on the UI thread. */
    private val lock = Any()

    @Volatile
    private var deferred: CompletableDeferred<UiResult>? = null

    override suspend fun request(request: UiRequest, timeoutMs: Long): UiResult {
        val current = CompletableDeferred<UiResult>()
        val alreadyPending = synchronized(lock) {
            if (deferred != null || _pending.value != null) {
                true
            } else {
                deferred = current
                _pending.value = request
                false
            }
        }
        if (alreadyPending) {
            return UiResult.Failed("Уже ожидается другое действие пользователя.")
        }

        return try {
            withTimeoutOrNull(timeoutMs) { current.await() }
                ?: UiResult.Failed("Не дождались действия пользователя (таймаут).")
        } finally {
            // Guard by identity: if a newer flow already replaced the slot, do not clear it.
            synchronized(lock) {
                if (deferred === current) {
                    _pending.value = null
                    deferred = null
                }
            }
        }
    }

    override fun submit(result: UiResult) {
        // Claim the slot atomically so a stale submit cannot complete a newer deferred.
        val current = synchronized(lock) {
            val pending = deferred ?: return
            deferred = null
            _pending.value = null
            pending
        }
        current.complete(result)
    }

    override fun cancel() {
        submit(UiResult.Cancelled)
    }
}
