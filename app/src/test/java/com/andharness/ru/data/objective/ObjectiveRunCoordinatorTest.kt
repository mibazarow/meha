package com.andharness.ru.data.objective

import com.andharness.ru.domain.diagnostics.PowerGuardPolicy
import com.andharness.ru.domain.diagnostics.PowerSnapshot
import com.andharness.ru.domain.diagnostics.PowerStateProvider
import com.andharness.ru.domain.llm.LlmAuthConfig
import com.andharness.ru.domain.objective.ObjectiveRunOutcome
import com.andharness.ru.domain.objective.ObjectiveRunResult
import com.andharness.ru.domain.objective.ObjectiveRunner
import com.andharness.ru.domain.settings.LlmSettings
import com.andharness.ru.domain.settings.LlmSettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end (JVM, Android-free) tests of the background objective coordinator: the concurrency
 * bound is real (independent runs), queued objectives are admitted FIFO, the queue drains to empty,
 * and a degraded device reduces the effective limit to 1 with an honest note.
 *
 * The Android pieces are replaced by fakes: [FakeRunner] is the objective runner, [FakeSettings] the
 * LLM settings and [FakePower] the power snapshot; [ObjectiveStore] and [ObjectiveWorkStore] are the
 * real Android-free file stores.
 */
class ObjectiveRunCoordinatorTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun healthy() = PowerSnapshot(90, false, false, PowerGuardPolicy.THERMAL_STATUS_NONE, false)

    private fun degradedLowBattery() = PowerSnapshot(10, false, false, PowerGuardPolicy.THERMAL_STATUS_NONE, false)

    private fun coordinator(
        store: ObjectiveStore,
        runner: ObjectiveRunner,
        configured: Boolean = true,
        power: PowerSnapshot = healthy()
    ) = ObjectiveRunCoordinator(
        store = store,
        runner = runner,
        settings = FakeSettings(configured),
        power = FakePower(power),
        workStore = ObjectiveWorkStore(folder.root)
    )

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(5) }
    }

    @Test
    fun `runs up to the limit and queues the rest, then drains FIFO`() = runBlocking {
        val store = ObjectiveStore(folder.root)
        val a = store.create("A", "goal a", emptyList(), 1L)!!
        val b = store.create("B", "goal b", emptyList(), 1L)!!
        val c = store.create("C", "goal c", emptyList(), 1L)!!
        val runner = FakeRunner().also { r -> listOf(a, b, c).forEach { r.gateFor(it.id) } }
        val coordinator = coordinator(store, runner)

        coordinator.tick()
        await {
            coordinator.status.value.running.size == 2 && coordinator.status.value.queued.size == 1 &&
                runner.maxActive == 2
        }
        assertEquals("concurrency must be real but bounded", 2, runner.maxActive)

        runner.release(a.id)
        await {
            coordinator.status.value.running.size == 2 && coordinator.status.value.queued.isEmpty() &&
                runner.maxActive == 2
        }
        assertEquals(2, runner.maxActive)

        runner.release(b.id)
        runner.release(c.id)
        await { coordinator.status.value.running.isEmpty() }
        assertEquals(2, runner.maxActive)
    }

    @Test
    fun `degraded power reduces the limit to one with an honest note`() = runBlocking {
        val store = ObjectiveStore(folder.root)
        val a = store.create("A", "goal a", emptyList(), 1L)!!
        val b = store.create("B", "goal b", emptyList(), 1L)!!
        val runner = FakeRunner().also { r -> listOf(a, b).forEach { r.gateFor(it.id) } }
        val coordinator = coordinator(store, runner, power = degradedLowBattery())

        coordinator.tick()
        await { coordinator.status.value.running.size == 1 && coordinator.status.value.queued.size == 1 }
        assertEquals(1, runner.maxActive)
        assertEquals(1, coordinator.status.value.effectiveLimit)
        assertTrue(coordinator.status.value.note.contains("понижено"))

        runner.release(a.id)
        runner.release(b.id)
        await { coordinator.status.value.running.isEmpty() }
    }

    @Test
    fun `stop clears running and queued work`() = runBlocking {
        val store = ObjectiveStore(folder.root)
        val a = store.create("A", "goal a", emptyList(), 1L)!!
        val b = store.create("B", "goal b", emptyList(), 1L)!!
        val runner = FakeRunner().also { r -> listOf(a, b).forEach { r.gateFor(it.id) } }
        val coordinator = coordinator(store, runner)

        coordinator.tick()
        await { coordinator.status.value.running.isNotEmpty() }
        val report = coordinator.stop(null)
        assertTrue(report.stoppedSomething)
        await { coordinator.status.value.running.isEmpty() && coordinator.status.value.queued.isEmpty() }
        runner.release(a.id)
        runner.release(b.id)
    }

    @Test
    fun `no llm means an honest refusal and nothing runs`() = runBlocking {
        val store = ObjectiveStore(folder.root)
        store.create("A", "goal a", emptyList(), 1L)!!
        val runner = FakeRunner()
        val coordinator = coordinator(store, runner, configured = false)

        val report = coordinator.runRequested(null)
        assertEquals(ObjectiveRunOutcome.NO_LLM, report.outcome)
        assertTrue(coordinator.status.value.running.isEmpty())
        assertTrue(coordinator.status.value.queued.isEmpty())
        assertEquals(0, runner.maxActive)
    }

    @Test
    fun `terminal objective is refused by objective_run`() = runBlocking {
        val store = ObjectiveStore(folder.root)
        val a = store.create("A", "goal a", emptyList(), 1L)!!
        store.setStatus(a.id, com.andharness.ru.domain.objective.ObjectiveStatus.DONE)
        val coordinator = coordinator(store, FakeRunner())

        val report = coordinator.runRequested(a.id)
        assertEquals(ObjectiveRunOutcome.TERMINAL, report.outcome)
    }

    @Test
    fun `concurrency setter is bounded to one through three`() {
        val store = ObjectiveStore(folder.root)
        val coordinator = coordinator(store, FakeRunner())
        assertEquals(1, coordinator.setConcurrency(0))
        assertEquals(3, coordinator.setConcurrency(9))
        assertEquals(2, coordinator.setConcurrency(2))
    }

    // ---- fakes ----

    /** Runner that blocks each objective on its own gate, so real overlap can be observed. */
    private class FakeRunner : ObjectiveRunner {
        private val active = AtomicInteger()
        private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        @Volatile
        var maxActive: Int = 0
            private set

        fun gateFor(id: String): CompletableDeferred<Unit> =
            gates.computeIfAbsent(id) { CompletableDeferred() }

        fun release(id: String) {
            gateFor(id).complete(Unit)
        }

        override suspend fun runStep(objectiveId: String): ObjectiveRunResult {
            val now = active.incrementAndGet()
            maxActive = maxOf(maxActive, now)
            gateFor(objectiveId).await()
            active.decrementAndGet()
            return ObjectiveRunResult(ObjectiveRunOutcome.ADVANCED, "ok")
        }
    }

    private class FakePower(private val snapshot: PowerSnapshot) : PowerStateProvider {
        override fun snapshot(): PowerSnapshot = snapshot
    }

    private class FakeSettings(private val configured: Boolean) : LlmSettingsStore {
        override val config: Flow<LlmSettings> = MutableStateFlow(
            LlmSettings(endpoint = if (configured) "http://localhost:8080" else "", model = "test")
        )

        override suspend fun updateEndpoint(value: String) = Unit
        override suspend fun updateModel(value: String) = Unit
        override suspend fun updateReasoningEffort(value: String) = Unit
        override suspend fun updateContextCompressionTokens(value: Long) = Unit
        override suspend fun getApiKey(): String? = null
        override suspend fun setApiKey(key: String) = Unit
        override suspend fun clearApiKey() = Unit
        override suspend fun getAuthConfig(): LlmAuthConfig = LlmAuthConfig.DEFAULT
        override suspend fun setAuthConfig(config: LlmAuthConfig) = Unit
        override suspend fun reset() = Unit
        override fun isConfigured(): Flow<Boolean> = MutableStateFlow(configured)
    }
}
