package com.andharness.ru.domain.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the shared bounded-consent helper [requestBoundedUi]: when no foreground Activity
 * can host the system dialog it must fail fast WITHOUT touching the bridge (that full-length wait is
 * what ANR'd the debug `goAsync()` broadcast in BUG-014/BUG-016), and when the app is foreground it
 * must delegate to the bridge with the requested timeout.
 */
class UiConsentAwaitTest {

    @After
    fun tearDown() {
        ActiveChatTracker.reset()
    }

    @Test
    fun backgroundedAppFailsFastWithoutCallingBridge() = runTest {
        ActiveChatTracker.onAppBackgrounded()
        val bridge = RecordingBridge()

        val result = bridge.requestBoundedUi(UiRequest.OpenDocumentTree())

        assertTrue(result is UiResult.Failed)
        assertEquals(UiConsentPolicy.NO_UI_MESSAGE, (result as UiResult.Failed).message)
        assertEquals(0, bridge.calls)
    }

    @Test
    fun foregroundAppDelegatesToBridgeWithRequestedTimeout() = runTest {
        ActiveChatTracker.onAppForegrounded()
        val bridge = RecordingBridge()

        val result = bridge.requestBoundedUi(UiRequest.OpenDocumentTree(), timeoutMs = 1234L)

        assertEquals(UiResult.Cancelled, result)
        assertEquals(1, bridge.calls)
        assertEquals(1234L, bridge.lastTimeoutMs)
    }

    /** Minimal [UiInteractionBridge] that records the single call it is expected to receive. */
    private class RecordingBridge : UiInteractionBridge {
        private val _pending = MutableStateFlow<UiRequest?>(null)
        override val pending: StateFlow<UiRequest?> = _pending

        var calls = 0
        var lastTimeoutMs = -1L

        override suspend fun request(request: UiRequest, timeoutMs: Long): UiResult {
            calls++
            lastTimeoutMs = timeoutMs
            return UiResult.Cancelled
        }

        override fun submit(result: UiResult) = Unit

        override fun cancel() = Unit
    }
}
