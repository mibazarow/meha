package com.andharness.ru.data.ui

import com.andharness.ru.domain.ui.UiRequest
import com.andharness.ru.domain.ui.UiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UiInteractionBridgeImplTest {

    @Test
    fun submitCompletesRequest() = runTest {
        val bridge = UiInteractionBridgeImpl()
        val request = UiRequest.OpenDocumentTree("Выберите папку")
        var result: UiResult? = null

        val job = launch { result = bridge.request(request, timeoutMs = 60_000L) }
        runCurrent()

        assertSame(request, bridge.pending.value)

        val submitted = UiResult.ConsentResult(granted = true)
        bridge.submit(submitted)
        advanceUntilIdle()
        job.join()

        assertEquals(submitted, result)
        assertNull(bridge.pending.value)
    }

    @Test
    fun timeoutReturnsFailed() = runTest {
        val bridge = UiInteractionBridgeImpl()
        var result: UiResult? = null

        val job = launch {
            result = bridge.request(UiRequest.OpenDocument(), timeoutMs = 1_000L)
        }
        runCurrent()

        advanceTimeBy(1_001L)
        runCurrent()
        job.join()

        assertTrue(result is UiResult.Failed)
        assertNull(bridge.pending.value)
    }

    @Test
    fun secondConcurrentRequestFailsFast() = runTest {
        val bridge = UiInteractionBridgeImpl()
        var firstResult: UiResult? = null

        val first = launch {
            firstResult = bridge.request(UiRequest.OpenDocumentTree(), timeoutMs = 60_000L)
        }
        runCurrent()

        val second = bridge.request(UiRequest.OpenDocument(), timeoutMs = 60_000L)
        assertTrue(second is UiResult.Failed)

        bridge.submit(UiResult.Cancelled)
        advanceUntilIdle()
        first.join()

        assertEquals(UiResult.Cancelled, firstResult)
    }
}
