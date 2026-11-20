package com.andharness.ru.data.objective

import com.andharness.ru.data.db.entity.ChatEntity
import com.andharness.ru.data.db.entity.MessageEntity
import com.andharness.ru.domain.agent.ActiveRun
import com.andharness.ru.domain.agent.AgentOrchestrator
import com.andharness.ru.domain.agent.AgentRunOutcome
import com.andharness.ru.domain.agent.AgentRunPriority
import com.andharness.ru.domain.agent.AgentStatus
import com.andharness.ru.domain.chat.ChatAttachment
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.diagnostics.PowerGuardPolicy
import com.andharness.ru.domain.diagnostics.PowerSnapshot
import com.andharness.ru.domain.diagnostics.PowerStateProvider
import com.andharness.ru.domain.llm.LlmAuthConfig
import com.andharness.ru.domain.objective.ObjectiveRunGate
import com.andharness.ru.domain.objective.ObjectiveRunOutcome
import com.andharness.ru.domain.objective.ObjectiveRules
import com.andharness.ru.domain.objective.ObjectiveStepStatus
import com.andharness.ru.domain.settings.LlmSettings
import com.andharness.ru.domain.settings.LlmSettingsStore
import dagger.Lazy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression tests for the missing-chat FOREIGN KEY crash (bug 787): an objective bound to a chat
 * that was deleted must NOT be run against that dangling id. Instead of letting
 * `AgentOrchestrator.initialize` insert messages for a nonexistent `chatId` (which violates the
 * `messages` FOREIGN KEY), the runner refuses honestly, marks the first unfinished step BLOCKED with
 * a note, and never calls the orchestrator.
 *
 * [ObjectiveRunnerImpl] is Android-touching only through `android.util.Log` (a no-op under
 * `unitTests.isReturnDefaultValues = true`), so it is exercised on a bare JVM with fakes for the
 * repository/settings/power/orchestrator — the same pattern as `ObjectiveRunCoordinatorTest`.
 */
class ObjectiveRunnerImplTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun healthy() = PowerSnapshot(90, false, false, PowerGuardPolicy.THERMAL_STATUS_NONE, false)

    private fun runner(
        store: ObjectiveStore,
        repository: ChatRepository,
        orchestrator: AgentOrchestrator
    ) = ObjectiveRunnerImpl(
        store = store,
        orchestrator = LazyValue(orchestrator),
        settings = FakeSettings(),
        powerGuard = FakePower(healthy()),
        chatRepository = repository
    )

    @Test
    fun `objective bound to a deleted chat is refused honestly and blocked, never run`() = runBlocking {
        val store = ObjectiveStore(folder.root)
        val objective = store.create("Цель", "goal", listOf("шаг 1", "шаг 2"), chatId = 42L)!!
        val orchestrator = FakeOrchestrator()
        val runner = runner(store, FakeChatRepository(existingChatIds = emptySet()), orchestrator)

        val result = runner.runStep(objective.id)

        assertEquals(ObjectiveRunOutcome.NO_CHAT, result.outcome)
        assertTrue("the refusal must carry an honest note", result.detail.isNotBlank())
        assertEquals("the agent must not touch the missing chat", 0, orchestrator.runCount)

        // Honest, visible state: the first unfinished step is BLOCKED with an explanatory note.
        val reloaded = store.get(objective.id)!!
        assertEquals(ObjectiveStepStatus.BLOCKED, reloaded.steps.first().status)
        assertTrue(reloaded.steps.first().note.isNotBlank())

        // And that is exactly what makes the stale-run restore path (a coordinator tick) skip it:
        // the gate sees the BLOCKED first step and refuses to auto-run it.
        assertFalse(
            ObjectiveRunGate.isRunnable(
                status = reloaded.status,
                blocked = ObjectiveRules.isBlocked(reloaded),
                llmConfigured = true,
                deferred = false,
                backgroundRuns = reloaded.backgroundRuns
            )
        )
    }

    @Test
    fun `objective with a live bound chat runs against that chat`() = runBlocking {
        val store = ObjectiveStore(folder.root)
        val objective = store.create("Цель", "goal", listOf("шаг"), chatId = 7L)!!
        val orchestrator = FakeOrchestrator()
        val runner = runner(store, FakeChatRepository(existingChatIds = setOf(7L)), orchestrator)

        val result = runner.runStep(objective.id)

        assertEquals(ObjectiveRunOutcome.ADVANCED, result.outcome)
        assertEquals(listOf(7L), orchestrator.runChatIds)
    }

    @Test
    fun `objective without a chat still binds a fresh one`() = runBlocking {
        val store = ObjectiveStore(folder.root)
        val objective = store.create("Цель", "goal", listOf("шаг"), chatId = null)!!
        val repository = FakeChatRepository(existingChatIds = emptySet(), createdId = 123L)
        val orchestrator = FakeOrchestrator()
        val runner = runner(store, repository, orchestrator)

        val result = runner.runStep(objective.id)

        assertEquals(ObjectiveRunOutcome.ADVANCED, result.outcome)
        assertEquals(1, repository.createChatCalls)
        assertEquals(123L, store.get(objective.id)!!.chatId)
        assertEquals(listOf(123L), orchestrator.runChatIds)
    }

    @Test
    fun `a failing chat existence check degrades to an honest refusal instead of throwing`() = runBlocking {
        val store = ObjectiveStore(folder.root)
        val objective = store.create("Цель", "goal", listOf("шаг"), chatId = 5L)!!
        val repository = FakeChatRepository(existingChatIds = setOf(5L)).also { it.failExists = true }
        val orchestrator = FakeOrchestrator()
        val runner = runner(store, repository, orchestrator)

        val result = runner.runStep(objective.id)

        assertEquals(ObjectiveRunOutcome.NO_CHAT, result.outcome)
        assertEquals(0, orchestrator.runCount)
    }

    // ---- fakes ----

    private class LazyValue<T>(private val value: T) : Lazy<T> {
        override fun get(): T = value
    }

    private class FakeOrchestrator : AgentOrchestrator {
        val runChatIds = mutableListOf<Long>()
        val runPriorities = mutableListOf<AgentRunPriority>()
        val runCount: Int get() = runChatIds.size
        private val _status = MutableStateFlow(AgentStatus())
        override val status: StateFlow<AgentStatus> = _status
        override fun statusFor(chatId: Long): StateFlow<AgentStatus> = _status
        override val activeRuns: StateFlow<List<ActiveRun>> = MutableStateFlow(emptyList())

        override suspend fun run(
            chatId: Long,
            userMessage: String,
            attachments: List<ChatAttachment>,
            priority: AgentRunPriority
        ): AgentRunOutcome {
            runChatIds += chatId
            runPriorities += priority
            return AgentRunOutcome.STARTED
        }

        override fun start(
            chatId: Long,
            userMessage: String,
            attachments: List<ChatAttachment>,
            priority: AgentRunPriority
        ): AgentRunOutcome {
            runChatIds += chatId
            runPriorities += priority
            return AgentRunOutcome.STARTED
        }

        override fun cancel() = Unit
        override fun cancel(chatId: Long) = Unit
        override fun grantPermission() = Unit
        override fun grantPermission(chatId: Long) = Unit
        override fun denyPermission() = Unit
        override fun denyPermission(chatId: Long) = Unit
    }

    private class FakeChatRepository(
        private val existingChatIds: Set<Long>,
        private val createdId: Long = 100L
    ) : ChatRepository {
        var createChatCalls = 0
            private set

        @Volatile
        var failExists = false

        override fun observeChats(): Flow<List<ChatEntity>> = flowOf(emptyList())
        override fun observeChat(chatId: Long): Flow<ChatEntity?> = flowOf(null)

        override suspend fun createChat(title: String): Long {
            createChatCalls++
            return createdId
        }

        override suspend fun chatExists(chatId: Long): Boolean {
            if (failExists) throw IllegalStateException("db unavailable")
            return chatId in existingChatIds
        }

        override fun observeMessages(chatId: Long): Flow<List<MessageEntity>> = flowOf(emptyList())

        override suspend fun addMessage(
            chatId: Long,
            role: String,
            content: String,
            toolName: String?
        ): Long = 0

        override suspend fun updateMessageContent(messageId: Long, content: String) = Unit
        override suspend fun setStreaming(messageId: Long, isStreaming: Boolean) = Unit
        override suspend fun clearStaleStreaming(): Int = 0
        override suspend fun deleteChat(chatId: Long) = Unit
        override suspend fun renameChat(chatId: Long, title: String) = Unit
        override suspend fun updateSummary(chatId: Long, summary: String) = Unit
        override suspend fun updateContextState(chatId: Long, state: String?) = Unit
        override suspend fun pruneToLimit(keep: Int) = Unit
        override suspend fun deleteAllChats() = Unit
        override suspend fun setPinned(chatId: Long, pinned: Boolean) = Unit
        override suspend fun clearMessages(chatId: Long) = Unit
        override suspend fun mostRecentChatId(): Long? = null
    }

    private class FakePower(private val snapshot: PowerSnapshot) : PowerStateProvider {
        override fun snapshot(): PowerSnapshot = snapshot
    }

    private class FakeSettings : LlmSettingsStore {
        override val config: Flow<LlmSettings> =
            MutableStateFlow(LlmSettings(endpoint = "http://localhost:8080", model = "test"))

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
        override fun isConfigured(): Flow<Boolean> = MutableStateFlow(true)
    }
}
