package com.andharness.ru.data.agent

import com.andharness.ru.data.db.dao.MemoryPersonDao
import com.andharness.ru.data.db.dao.MissingLinkMemory
import com.andharness.ru.data.db.entity.ChatEntity
import com.andharness.ru.data.db.entity.MemoryPersonEntity
import com.andharness.ru.data.db.entity.MessageEntity
import com.andharness.ru.data.db.entity.NoteEntity
import com.andharness.ru.data.db.entity.TodoEntity
import com.andharness.ru.data.profile.GlobalProfileStore
import com.andharness.ru.data.profile.ProfileRules
import com.andharness.ru.domain.agent.AUTO_IMPORTANCE
import com.andharness.ru.domain.agent.ContextManager
import com.andharness.ru.domain.agent.FactExtractor
import com.andharness.ru.domain.agent.LlmClient
import com.andharness.ru.domain.agent.LlmEvent
import com.andharness.ru.domain.agent.LlmMessage
import com.andharness.ru.domain.agent.LlmRequest
import com.andharness.ru.domain.agent.LlmTool
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.assist.AssistStats
import com.andharness.ru.domain.assist.MemoryHit
import com.andharness.ru.domain.assist.MemorySaveResult
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.common.TimeProvider
import com.andharness.ru.domain.device.DeviceInfoProvider
import com.andharness.ru.domain.locale.AppLanguage
import com.andharness.ru.domain.memory.DeviceFact
import com.andharness.ru.domain.memory.DeviceFactEvent
import com.andharness.ru.domain.memory.DeviceFactRepository
import com.andharness.ru.domain.person.Interaction
import com.andharness.ru.domain.person.InteractionRepository
import com.andharness.ru.domain.person.Person
import com.andharness.ru.domain.person.PersonRepository
import com.andharness.ru.domain.settings.LlmSettings
import com.andharness.ru.domain.settings.LlmSettingsStore
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DEVICE_SUMMARY = "Model: TestPhone"

class ContextManagerImplTest {

    class FakeDeviceInfoProvider : DeviceInfoProvider {
        override fun summary(): String = DEVICE_SUMMARY
    }

    open class FakeAssistRepository : AssistRepository {
        override suspend fun listNotes(kind: String, limit: Int): List<NoteEntity> = emptyList()
        override suspend fun searchNotes(kind: String, query: String, limit: Int): List<NoteEntity> = emptyList()
        override suspend fun addNote(kind: String, text: String): Long = 0
        override suspend fun deleteNoteById(id: Long): Boolean = false
        override suspend fun listMemories(limit: Int): List<NoteEntity> = emptyList()
        override suspend fun deleteMemory(id: Long): Boolean = false
        override suspend fun stats(): AssistStats = AssistStats(0, 0, emptyMap())
        override suspend fun saveMemory(text: String, tags: String, importance: Int): Long = 0
        override suspend fun searchMemories(query: String, limit: Int): List<NoteEntity> = emptyList()
        override suspend fun searchMemoriesRanked(query: String, limit: Int): List<MemoryHit> = emptyList()
        override suspend fun listTodos(limit: Int): List<TodoEntity> = emptyList()
        override suspend fun addTodo(text: String): Long = 0
        override suspend fun setTodoDone(id: Long, done: Boolean): Boolean = false
        override suspend fun deleteTodo(id: Long): Boolean = false
    }

    /** Records the auto-recall query and returns one lexical hit, to test the injected block. */
    class RecordingAssistRepository(private val hit: MemoryHit) : AssistRepository {
        var lastQuery: String? = null
        override suspend fun listNotes(kind: String, limit: Int): List<NoteEntity> = emptyList()
        override suspend fun searchNotes(kind: String, query: String, limit: Int): List<NoteEntity> = emptyList()
        override suspend fun addNote(kind: String, text: String): Long = 0
        override suspend fun deleteNoteById(id: Long): Boolean = false
        override suspend fun listMemories(limit: Int): List<NoteEntity> = emptyList()
        override suspend fun deleteMemory(id: Long): Boolean = false
        override suspend fun stats(): AssistStats = AssistStats(0, 0, emptyMap())
        override suspend fun saveMemory(text: String, tags: String, importance: Int): Long = 0
        override suspend fun searchMemories(query: String, limit: Int): List<NoteEntity> = emptyList()
        override suspend fun searchMemoriesRanked(query: String, limit: Int): List<MemoryHit> {
            lastQuery = query
            return listOf(hit)
        }
        override suspend fun listTodos(limit: Int): List<TodoEntity> = emptyList()
        override suspend fun addTodo(text: String): Long = 0
        override suspend fun setTodoDone(id: Long, done: Boolean): Boolean = false
        override suspend fun deleteTodo(id: Long): Boolean = false
    }

    /** Records consolidated memory saves so the pre-compaction extraction can be asserted. */
    class RecordingMemoryAssistRepository : FakeAssistRepository() {
        data class Saved(val text: String, val tags: String, val importance: Int)

        val saved = mutableListOf<Saved>()

        override suspend fun saveMemoryConsolidated(
            text: String,
            tags: String,
            importance: Int
        ): MemorySaveResult {
            saved += Saved(text, tags, importance)
            return MemorySaveResult(saved.size.toLong(), true, text.trim(), tags.trim(), importance)
        }
    }

    /** Fake LLM client that replays a fixed event list and records how often it was called. */
    class FakeLlmClient(
        private val events: List<LlmEvent> = listOf(LlmEvent.Done)
    ) : LlmClient {
        var calls = 0
        var lastRequest: LlmRequest? = null

        override fun complete(request: LlmRequest): Flow<LlmEvent> = flow {
            calls++
            lastRequest = request
            events.forEach { emit(it) }
        }
    }

    /** Empty cache: no device-fact block is injected, so the prompt assertions stay stable. */
    class FakeDeviceFactRepository : DeviceFactRepository {
        override suspend fun get(key: String): DeviceFact? = null
        override suspend fun getCategory(category: String): List<DeviceFact> = emptyList()
        override suspend fun all(): List<DeviceFact> = emptyList()
        override suspend fun put(fact: DeviceFact) = Unit
        override suspend fun invalidate(key: String, reason: String) = Unit
        override suspend fun invalidateCategory(category: String, reason: String) = Unit
        override suspend fun invalidateAll(reason: String) = Unit
        override suspend fun isFresh(key: String, now: Long): Boolean = false
        override suspend fun expired(now: Long): List<DeviceFact> = emptyList()
        override suspend fun remove(key: String) = Unit
        override suspend fun clear(reason: String) = Unit
        override suspend fun events(limit: Int): List<DeviceFactEvent> = emptyList()
    }

    /** Empty personal base: no «ЛЮДИ» block is injected, so the prompt assertions stay stable. */
    class FakePersonRepository : PersonRepository {
        override suspend fun insert(displayName: String, notes: String): Long = 0
        override suspend fun get(id: Long): Person? = null
        override suspend fun searchByName(query: String, limit: Int): List<Person> = emptyList()
        override suspend fun byAlias(alias: String, limit: Int): List<Person> = emptyList()
        override suspend fun recent(limit: Int): List<Person> = emptyList()
        override suspend fun update(person: Person) = Unit
        override suspend fun delete(id: Long): Int = 0
        override suspend fun merge(fromId: Long, intoId: Long): Boolean = false
        override suspend fun count(): Int = 0
    }

    /** Returns the given person for its id, so linked-memory enrichment can be asserted. */
    class LinkedPersonRepository(private val person: Person) : PersonRepository {
        override suspend fun insert(displayName: String, notes: String): Long = 0
        override suspend fun get(id: Long): Person? = if (id == person.id) person else null
        override suspend fun searchByName(query: String, limit: Int): List<Person> = emptyList()
        override suspend fun byAlias(alias: String, limit: Int): List<Person> = emptyList()
        override suspend fun recent(limit: Int): List<Person> = emptyList()
        override suspend fun update(person: Person) = Unit
        override suspend fun delete(id: Long): Int = 0
        override suspend fun merge(fromId: Long, intoId: Long): Boolean = false
        override suspend fun count(): Int = 1
    }

    /** Links every queried memory to one person id (the `memory ↔ person` join). */
    class FakeMemoryPersonDao(private val personId: Long) : MemoryPersonDao {
        override suspend fun upsertAll(entities: List<MemoryPersonEntity>) = Unit
        override suspend fun personIdsForNotes(noteIds: List<Long>): List<Long> =
            if (noteIds.isEmpty()) emptyList() else listOf(personId)
        override suspend fun noteIdsForPerson(personId: Long, limit: Int): List<Long> = emptyList()
        override suspend fun countForNote(noteId: Long): Int = 0
        override suspend fun deleteForNote(noteId: Long): Int = 0
        override suspend fun deleteAll() = Unit
        override suspend fun memoriesMissingLinks(limit: Int): List<MissingLinkMemory> = emptyList()
    }

    class FakeInteractionRepository : InteractionRepository {
        override suspend fun insert(interaction: Interaction): Long = 0
        override suspend fun recentFor(personId: Long, limit: Int): List<Interaction> = emptyList()
        override suspend fun recentOverall(limit: Int): List<Interaction> = emptyList()
        override suspend fun search(query: String, limit: Int): List<Interaction> = emptyList()
        override suspend fun searchForPerson(
            personId: Long,
            query: String,
            limit: Int
        ): List<Interaction> = emptyList()
        override suspend fun existsByExternalRef(personId: Long, externalRef: String): Boolean = false
        override suspend fun countFor(personId: Long): Int = 0
        override suspend fun deleteFor(personId: Long): Int = 0
    }

    /** Deterministic pseudo-clock: the tests move [instant] forward to prove freshness. */
    class FakeTimeProvider(
        var instant: Long = Instant.parse("2026-09-17T17:45:00Z").toEpochMilli(),
        var zone: ZoneId = ZoneId.of("Europe/Moscow"),
        var locale: Locale = Locale.forLanguageTag("ru")
    ) : TimeProvider {
        override fun nowMillis(): Long = instant
        override fun zoneId(): ZoneId = zone
        override fun locale(): Locale = locale
    }

    private fun contextManager(
        repo: ChatRepository,
        settingsStore: LlmSettingsStore,
        timeProvider: TimeProvider = FakeTimeProvider(),
        assistRepository: AssistRepository = FakeAssistRepository(),
        llmClient: LlmClient = FakeLlmClient(),
        personRepository: PersonRepository = FakePersonRepository(),
        memoryPersonDao: MemoryPersonDao? = null,
        globalProfileStore: GlobalProfileStore? = null
    ): ContextManager = ContextManagerImpl(
        repo,
        settingsStore,
        FakeDeviceInfoProvider(),
        assistRepository,
        FactExtractor(llmClient, settingsStore, assistRepository),
        FakeDeviceFactRepository(),
        personRepository,
        FakeInteractionRepository(),
        timeProvider,
        memoryPersonDao,
        globalProfileStore = globalProfileStore
    )

    private fun msg(chatId: Long, role: String, content: String, id: Long = 0): MessageEntity =
        MessageEntity(
            id = id,
            chatId = chatId,
            role = role,
            content = content,
            createdAt = 0,
            isStreaming = false
        )

    private fun longContent(prefix: String, length: Int): String =
        "$prefix " + "x".repeat(length)

    class FakeChatRepository(
        var messages: List<MessageEntity> = emptyList(),
        var chat: ChatEntity? = null
    ) : ChatRepository {
        var updatedSummary: String? = null
        var updatedContextState: String? = null
        val addedMessages = mutableListOf<Pair<String, String>>()

        override fun observeChats(): Flow<List<ChatEntity>> = flowOf(emptyList())
        override fun observeChat(chatId: Long): Flow<ChatEntity?> = flowOf(chat)

        override suspend fun createChat(title: String): Long = TODO("unused")

        override suspend fun chatExists(chatId: Long): Boolean = true

        override fun observeMessages(chatId: Long): Flow<List<MessageEntity>> = flowOf(messages)

        override suspend fun addMessage(
            chatId: Long,
            role: String,
            content: String,
            toolName: String?
        ): Long {
            addedMessages += role to content
            return addedMessages.size.toLong()
        }

        override suspend fun updateMessageContent(messageId: Long, content: String) =
            TODO("unused")

        override suspend fun setStreaming(messageId: Long, isStreaming: Boolean) =
            TODO("unused")

        override suspend fun clearStaleStreaming(): Int = 0

        override suspend fun deleteChat(chatId: Long) = TODO("unused")

        override suspend fun renameChat(chatId: Long, title: String) = TODO("unused")

        override suspend fun updateSummary(chatId: Long, summary: String) {
            updatedSummary = summary
        }

        override suspend fun updateContextState(chatId: Long, state: String?) {
            updatedContextState = state
        }

        override suspend fun pruneToLimit(keep: Int) {}

        override suspend fun deleteAllChats() {}

        override suspend fun setPinned(chatId: Long, pinned: Boolean) = Unit

        override suspend fun clearMessages(chatId: Long) = Unit

        override suspend fun mostRecentChatId(): Long? = null
    }

    class FakeLlmSettingsStore(
        override val config: Flow<LlmSettings>,
        private val configured: Boolean = true
    ) : LlmSettingsStore {
        override suspend fun updateEndpoint(value: String) = TODO("unused")
        override suspend fun updateModel(value: String) = TODO("unused")
        override suspend fun updateReasoningEffort(value: String) = TODO("unused")
        override suspend fun updateContextCompressionTokens(value: Long) = TODO("unused")
        override suspend fun getApiKey(): String? = TODO("unused")
        override suspend fun setApiKey(key: String) = TODO("unused")
        override suspend fun clearApiKey() = TODO("unused")
        override suspend fun getAuthConfig(): com.andharness.ru.domain.llm.LlmAuthConfig =
            com.andharness.ru.domain.llm.LlmAuthConfig.DEFAULT
        override suspend fun setAuthConfig(config: com.andharness.ru.domain.llm.LlmAuthConfig) = TODO("unused")
        override suspend fun reset() = TODO("unused")
        override fun isConfigured(): Flow<Boolean> = flowOf(configured)
    }

    @Test
    fun noCompressionUnderLimit() = runTest {
        val settings = LlmSettings(
            endpoint = "",
            model = "m",
            contextCompressionTokens = 1_000_000L
        )
        val repo = FakeChatRepository(
            messages = listOf(
                msg(1, "user", "hello"),
                msg(1, "assistant", "hi there")
            )
        )
        val settingsStore = FakeLlmSettingsStore(config = flowOf(settings))

        val ctx = contextManager(repo, settingsStore).compile(1)

        assertFalse("should not compress under the token limit", ctx.compressed)
        // system prompt + 2 history messages
        assertEquals(3, ctx.messages.size)
        val systemContent = ctx.messages[0].content
        assertTrue("prompt must state the agent identity", systemContent!!.startsWith("You are Меха"))
        // The system message is STATIC: it is exactly the byte-exact base prompt, so no volatile
        // block (e.g. device context) may leak into the KV-cacheable prefix.
        assertEquals(SystemPrompts.BASE_SYSTEM_PROMPT, systemContent)
        // The device context now rides in the runtime tail of the LAST user message.
        assertTrue(
            "device context must be injected into the last user message",
            lastUserContent(ctx.messages).contains("DEVICE CONTEXT:\n$DEVICE_SUMMARY")
        )
    }

    @Test
    fun recallQueryUsesTheNewestThreeNonBlankDialogueMessages() {
        val messages = listOf(
            msg(1, "user", "первое"),
            msg(1, "assistant", "второе"),
            msg(1, "tool", "сырой лог инструмента"),
            msg(1, "assistant", "третье"),
            msg(1, "user", "четвёртое"),
            msg(1, "user", "   ")
        )
        assertEquals(
            "второе\nтретье\nчетвёртое",
            buildRecallQuery(messages)
        )
        assertEquals(
            "no dialogue message → no query, so nothing is recalled",
            "",
            buildRecallQuery(listOf(msg(1, "tool", "x"), msg(1, "user", "  ")))
        )
    }

    @Test
    fun recallQueryIsCappedKeepingTheNewestTail() {
        val query = buildRecallQuery(
            listOf(
                msg(1, "user", "a".repeat(500)),
                msg(1, "assistant", "свежий ответ")
            )
        )
        assertEquals("the query must be capped to 400 chars", 400, query.length)
        assertTrue("the newest words must survive the cap", query.endsWith("свежий ответ"))
    }

    @Test
    fun memoryLineCarriesIdImportanceAndTagsAndIsBounded() {
        assertEquals(
            "- [3] Пользователь любит чай (важность 2, теги: чай, напитки)",
            formatMemoryLine(
                NoteEntity(
                    id = 3,
                    kind = "memory",
                    text = "Пользователь любит чай",
                    tags = "чай, напитки",
                    importance = 2
                )
            )
        )
        assertEquals(
            "a memory without tags must still render id + importance",
            "- [4] без тегов (важность 0)",
            formatMemoryLine(NoteEntity(id = 4, kind = "memory", text = "без тегов", importance = 0))
        )
        assertEquals(
            "the memory text must be bounded per item",
            "- [5] " + "x".repeat(160) + "… (важность 0)",
            formatMemoryLine(NoteEntity(id = 5, kind = "memory", text = "x".repeat(500)))
        )
        assertEquals(
            "an oversized tag list is omitted instead of being cut mid-tag",
            "- [6] короткий факт (важность 0)",
            formatMemoryLine(
                NoteEntity(id = 6, kind = "memory", text = "короткий факт", tags = "т".repeat(120))
            )
        )
    }

    /** MEMORY_TASKS §3: the body is cut on a sentence boundary, never mid-word. */
    @Test
    fun memoryBodyIsCutOnASentenceBoundary() {
        val sentence = "Пользователь любит крепкий чай без сахара. " // 45 chars
        val text = sentence.repeat(10) // 450 chars, dots every 45
        val line = formatMemoryLine(NoteEntity(id = 7, kind = "memory", text = text))

        assertTrue("the cut must keep whole sentences", line.contains(sentence.trim()))
        assertTrue("the cut must be announced", line.contains("…"))
        val body = line.removePrefix("- [7] ").substringBefore(" (важность")
        assertTrue(
            "the body must end on a sentence terminator, not mid-word",
            body.trimEnd('…').trimEnd().endsWith(".")
        )
        assertTrue("the body must stay within the cap", body.length <= 161)

        val noDots = "слово ".repeat(100)
        val wordCut = formatMemoryLine(NoteEntity(id = 8, kind = "memory", text = noDots))
        val wordBody = wordCut.removePrefix("- [8] ").substringBefore(" (важность")
        assertTrue(
            "without sentence terminators the cut must land on a space",
            !wordBody.removeSuffix("…").endsWith("слов") && wordBody.trimEnd('…').endsWith("слово")
        )
    }

    @Test
    fun autoRecallQuerySpansTheDialogueTailAndTheBlockCarriesProvenance() = runTest {
        val settings = LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 1_000_000L)
        val repo = FakeChatRepository(
            messages = listOf(
                msg(1, "user", "старая тема"),
                msg(1, "assistant", "старый ответ"),
                msg(1, "user", "расскажи про налоговую"),
                msg(1, "assistant", "ФНС и декларация"),
                msg(1, "user", "а что я решил?")
            )
        )
        val assist = RecordingAssistRepository(
            MemoryHit(
                note = NoteEntity(
                    id = 7,
                    kind = "memory",
                    text = "Вопрос с ФНС решён",
                    tags = "налоговая, фнс",
                    importance = 1
                ),
                score = 2.0,
                lexicallyMatched = true
            )
        )

        val ctx = contextManager(
            repo,
            FakeLlmSettingsStore(flowOf(settings)),
            assistRepository = assist
        ).compile(1)

        val tailUser = lastUserContent(ctx.messages)
        assertTrue(
            "the injected memory must carry its id, importance and tags",
            tailUser.contains("- [7] Вопрос с ФНС решён (важность 1, теги: налоговая, фнс)")
        )
        val query = assist.lastQuery.orEmpty()
        assertTrue("the recall query must include the latest user message", query.contains("а что я решил?"))
        assertTrue(
            "the recall query must include the assistant's last reply",
            query.contains("ФНС и декларация")
        )
        assertFalse("only the newest dialogue tail may feed recall", query.contains("старая тема"))
    }

    /**
     * The shared cross-chat profile: a NON-EMPTY profile must be injected into the cloud prompt on
     * EVERY chat, ahead of the (recall-gated) memory block, with the explicit Russian header — this is
     * what makes a saved name/preference available without any lexical recall.
     */
    @Test
    fun profileBlockIsAlwaysInjectedIntoTheCloudPromptWhenNonEmpty() = runTest {
        val settings = LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 1_000_000L)
        val repo = FakeChatRepository(messages = listOf(msg(1, "user", "привет")))
        val dir: File = Files.createTempDirectory("profile-prompt-cloud").toFile()
        try {
            val profile = GlobalProfileStore(dir)
            profile.put(null, "Имя: Иван")
            profile.put(null, "Любит зелёный чай")

            val messages = contextManager(
                repo,
                FakeLlmSettingsStore(flowOf(settings)),
                globalProfileStore = profile
            ).compile(1).messages

            // The saved facts ride in the runtime tail; the management instructions stay in the
            // static system prompt.
            val userTail = lastUserContent(messages)
            val staticSystem = messages.first().content!!
            assertTrue(
                "the always-on profile block must carry its explicit header",
                userTail.contains(ProfileRules.HEADER)
            )
            assertTrue("the saved name must be injected", userTail.contains("Имя: Иван"))
            assertTrue("every saved fact must be injected", userTail.contains("Любит зелёный чай"))
            assertTrue(
                "the static prompt must tell the agent how to manage the profile",
                staticSystem.contains("profile_set") && staticSystem.contains("profile_forget")
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun emptyProfileInjectsNoHeaderAtAll() = runTest {
        val settings = LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 1_000_000L)
        val repo = FakeChatRepository(messages = listOf(msg(1, "user", "привет")))
        val dir: File = Files.createTempDirectory("profile-prompt-empty").toFile()
        try {
            val messages = contextManager(
                repo,
                FakeLlmSettingsStore(flowOf(settings)),
                globalProfileStore = GlobalProfileStore(dir)
            ).compile(1).messages

            assertFalse(
                "an empty profile must inject nothing, not even the header",
                messages.any { it.content?.contains(ProfileRules.HEADER) == true }
            )
        } finally {
            dir.deleteRecursively()
        }
    }


    @Test
    fun injectedMemoriesSurfaceTheirRelatedPeople() = runTest {
        val settings = LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 1_000_000L)
        val repo = FakeChatRepository(
            messages = listOf(msg(1, "user", "обсуждали налоги, не помню с кем"))
        )
        val assist = RecordingAssistRepository(
            MemoryHit(
                note = NoteEntity(
                    id = 7,
                    kind = "memory",
                    text = "Вопрос с ФНС решён",
                    tags = "налоговая, фнс",
                    importance = 1
                ),
                score = 2.0,
                lexicallyMatched = true
            )
        )
        val person = Person(
            id = 42,
            displayName = "Иван Петров",
            notes = "",
            createdAt = 0,
            updatedAt = 0
        )

        val messages = contextManager(
            repo,
            FakeLlmSettingsStore(flowOf(settings)),
            assistRepository = assist,
            personRepository = LinkedPersonRepository(person),
            memoryPersonDao = FakeMemoryPersonDao(person.id)
        ).compile(1).messages

        assertTrue(
            "a recalled memory must name the people linked to it, even when its text does not",
            lastUserContent(messages).contains("Связанные люди: Иван Петров")
        )
    }


    @Test
    fun skillsCapabilityCarriesTheStrictCreationPolicy() = runTest {
        val settings = LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 1_000_000L)
        val repo = FakeChatRepository(messages = listOf(msg(1, "user", "привет")))

        val system = contextManager(repo, FakeLlmSettingsStore(flowOf(settings)))
            .compile(1).messages.first().content!!

        assertTrue(
            "the cloud prompt must allow skill creation only on explicit request or a recurring workflow",
            system.contains("create or update a skill ONLY when the user explicitly asks")
        )
        assertTrue(
            "the cloud prompt must forbid one-off skills",
            system.contains("NEVER for a one-off action")
        )
        assertTrue(
            "the cloud prompt must require checking for an existing skill before creating",
            system.contains("skill_list/skill_get")
        )
        assertTrue(
            "the cloud prompt must forbid saving a skill silently",
            system.contains("never save a skill silently")
        )
    }

    @Test
    fun screenMapCapabilityCarriesTheStaleCacheWarning() = runTest {
        val settings = LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 1_000_000L)
        val repo = FakeChatRepository(messages = listOf(msg(1, "user", "привет")))

        val system = contextManager(repo, FakeLlmSettingsStore(flowOf(settings)))
            .compile(1).messages.first().content!!

        assertTrue(
            "the cloud prompt must mention the screen-map cache tool",
            system.contains("screen_map_get")
        )
        assertTrue(
            "the cloud prompt must require verifying the live screen before acting",
            system.contains("ALWAYS verify the live screen with a fresh ui_tree")
        )
    }


    /**
     * BUG-055 (feature A): the "act first, report later" policy must be present in the cloud prompt,
     * integrated with the existing execution rules — prefer doing over asking, at most one
     * clarifying question, try alternatives, and report an honest failure only after real attempts.
     */
    @Test
    fun cloudPromptCarriesTheActFirstPolicy() = runTest {
        val settings = LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 1_000_000L)
        val repo = FakeChatRepository(messages = listOf(msg(1, "user", "привет")))

        val system = contextManager(repo, FakeLlmSettingsStore(flowOf(settings)))
            .compile(1).messages.first().content!!

        assertTrue(
            "the cloud prompt must carry the merged act-first/execution block header",
            system.contains("ПРАВИЛА ВЫПОЛНЕНИЯ (ЖЁСТКО):")
        )
        assertTrue(
            "the cloud prompt must prefer doing over asking",
            system.contains("Сначала ДЕЛАЙ, потом докладывай")
        )
        assertTrue(
            "the cloud prompt must cap clarifying questions at one",
            system.contains("Уточняющий вопрос — максимум ОДИН")
        )
        assertTrue(
            "the cloud prompt must require trying alternatives before giving up",
            system.contains("пробуй разумные АЛЬТЕРНАТИВЫ")
        )
    }


    /**
     * M3 «Доверие» (reverted 2026-09-21): the owner decided the app must NOT gate dangerous actions
     * behind a confirmation. The cloud prompt must state honestly that dangerous/irreversible actions
     * execute directly without asking the user, while still preferring reversible paths and pointing
     * at `undo_last`; the mandatory "ask to confirm" wording must be absent.
     */
    @Test
    fun cloudPromptStatesDangerousActionsExecuteDirectlyWithoutConfirmation() = runTest {
        val settings = LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 1_000_000L)
        val repo = FakeChatRepository(messages = listOf(msg(1, "user", "привет")))

        val system = contextManager(repo, FakeLlmSettingsStore(flowOf(settings)))
            .compile(1).messages.first().content!!

        assertTrue(
            "the cloud prompt must state that the app does not ask for confirmation",
            system.contains("the app does NOT ask for confirmation")
        )
        assertTrue(
            "the cloud prompt must state that dangerous/irreversible actions run directly",
            system.contains("they run directly")
        )
        assertTrue(
            "the cloud prompt must point at undo_last for app-undoable actions",
            system.contains("revertible with undo_last")
        )
        assertTrue(
            "the cloud prompt must still prefer reversible paths over permanent ones",
            system.contains("trash over permanent delete")
        )
        assertFalse(
            "the mandatory ask-to-confirm rule must be gone",
            system.contains(
                "you MUST first state exactly what you are about to do and ask the user to confirm"
            )
        )
        assertFalse(
            "no mandatory 'ask the user to confirm' wording may remain",
            system.contains("ask the user to confirm")
        )
    }


    /**
     * Bug #1: the agent has no clock, so every compiled request must carry the FRESH current local
     * date/time/day-of-week/timezone — and a later request in the same long session must see the NEW
     * time, never a value cached at startup.
     */
    @Test
    fun systemPromptCarriesAFreshCurrentTimestampOnEveryRequest() = runTest {
        val settings = LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 1_000_000L)
        val repo = FakeChatRepository(messages = listOf(msg(1, "user", "который час")))
        val clock = FakeTimeProvider()
        val manager = contextManager(repo, FakeLlmSettingsStore(flowOf(settings)), clock)

        val first = lastUserContent(manager.compile(1).messages)
        assertTrue("prompt must state the current local time", first.contains("20:45"))
        assertTrue("prompt must state the current local date", first.contains("17.09.2026"))
        assertTrue("prompt must name the weekday in the user's locale", first.contains("четверг"))
        assertTrue("prompt must state the timezone", first.contains("Europe/Moscow"))
        assertTrue("prompt must carry the authoritative epoch millis", first.contains(clock.instant.toString()))

        // The same chat is asked again later (long session): the timestamp must be recomputed.
        clock.instant = Instant.parse("2026-09-17T18:15:00Z").toEpochMilli()
        val second = lastUserContent(manager.compile(1).messages)
        assertTrue("the timestamp must be refreshed on every compile", second.contains("21:15"))
        assertFalse("a stale timestamp must never be reused", second.contains("20:45"))
    }

    @Test
    fun excludesStaleToolMessagesFromLlmContext() = runTest {
        val settings = LlmSettings(
            endpoint = "",
            model = "m",
            contextCompressionTokens = 1_000_000L
        )
        // Simulates a chat where a previous turn already executed a tool: the raw tool result is
        // persisted as a role="tool" message, but its tool_call_id / assistant tool_calls pairing
        // is NOT persisted. Feeding that stray tool message back to the LLM on the next turn
        // produces an invalid, confusing context. It must be excluded from the LLM context.
        val repo = FakeChatRepository(
            messages = listOf(
                msg(1, "user", "включи фонарик"),
                msg(1, "assistant", "Фонарик включён."),
                msg(1, "tool", "Фонарик включён."),
                msg(1, "user", "выключи фонарик")
            )
        )
        val settingsStore = FakeLlmSettingsStore(config = flowOf(settings))

        val ctx = contextManager(repo, settingsStore).compile(1)

        assertFalse("should not compress under the token limit", ctx.compressed)
        assertTrue(
            "no tool-role message may be sent to the LLM",
            ctx.messages.none { it.role == "tool" }
        )
        // system + user(включи) + assistant + user(выключи) — the raw tool log is dropped
        assertEquals(4, ctx.messages.size)
        val roles = ctx.messages.drop(1).map { it.role }
        assertEquals(listOf("user", "assistant", "user"), roles)
    }

    @Test
    fun compressesOldMessagesWhenOverLimit() = runTest {
        val settings = LlmSettings(
            endpoint = "",
            model = "m",
            contextCompressionTokens = 20L
        )
        val repo = FakeChatRepository(
            messages = listOf(
                msg(1, "user", longContent("user", 500)),
                msg(1, "assistant", longContent("assistant", 500)),
                msg(1, "user", longContent("user", 500)),
                msg(1, "assistant", longContent("assistant", 500))
            )
        )
        val settingsStore = FakeLlmSettingsStore(config = flowOf(settings))

        val ctx = contextManager(repo, settingsStore).compile(1)

        assertTrue("should compress when over the token limit", ctx.compressed)
        assertNotNull("summary must be persisted", repo.updatedSummary)
        // original system prompt is retained somewhere in the compressed context
        assertTrue(
            "system prompt must be present in the compressed context",
            ctx.messages.any { it.content?.startsWith("You are Меха") == true }
        )
        // the rolling summary now rides in the runtime tail of the last user message
        assertTrue(
            "the rolling summary must be carried in the runtime tail",
            ctx.messages.any { it.role == "user" && it.content?.contains("Краткое изложение предыдущего диалога:") == true }
        )
        // compression must DROP the oldest over-limit messages (history trimmed),
        // so the compressed context is smaller than the uncompressed one (system + 4 = 5)
        assertTrue(
            "oldest over-limit messages must be dropped",
            ctx.messages.size < 5
        )
    }

    /**
     * NEW_TASKS §6.2 — «сжатие не врёт»: a fact that existed ONLY in the folded-away part of the
     * dialogue must still be recoverable from the persisted summary. If the summary dropped it, the
     * agent would answer confidently from nothing.
     */
    @Test
    fun theSummaryKeepsTheSubstanceOfTheFoldedMessages() = runTest {
        val settings = LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 20L)
        val repo = FakeChatRepository(
            messages = listOf(
                msg(1, "user", "Кодовое слово нашего проекта — ФЕНИКС. " + "x".repeat(300)),
                msg(1, "assistant", "Запомнил: ФЕНИКС. " + "y".repeat(300)),
                msg(1, "user", "и ещё немного текста " + "z".repeat(300)),
                msg(1, "assistant", "принято " + "w".repeat(300))
            )
        )

        val ctx = contextManager(repo, FakeLlmSettingsStore(flowOf(settings))).compile(1)

        assertTrue("the turn must compact", ctx.compressed)
        val summary = repo.updatedSummary.orEmpty()
        assertTrue("the summary must not be empty", summary.isNotBlank())
        assertTrue(
            "a fact that only existed in the dropped part must survive into the summary",
            summary.contains("ФЕНИКС")
        )
    }

    @Test
    fun toolSchemasCountTowardsTheContextBudget() = runTest {
        val repo = FakeChatRepository(messages = listOf(msg(1, "user", "привет")))
        val tool = LlmTool(
            name = "files_write",
            description = "w".repeat(2_000),
            parameters = buildJsonObject { }
        )

        // Learn the message-only size with an effectively unlimited budget.
        val unlimited = FakeLlmSettingsStore(
            config = flowOf(LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 10_000_000L))
        )
        val messages = contextManager(repo, unlimited).compile(1, listOf(tool)).messages
        val messageTokens = LlmTokenEstimator.estimate(messages)
        val toolTokens = LlmTokenEstimator.estimate(listOf(tool))
        assertTrue("the tool schema must have a non-zero cost", toolTokens > 0L)

        // A budget the messages alone fit into, but messages + tool schemas do not.
        val budget = messageTokens + toolTokens / 2
        val limited = FakeLlmSettingsStore(
            config = flowOf(LlmSettings(endpoint = "", model = "m", contextCompressionTokens = budget))
        )

        val ctx = contextManager(repo, limited).compile(1, listOf(tool))

        assertTrue(
            "the tool-schema payload must be part of the pre-send budget check",
            ctx.compressed
        )
    }

    @Test
    fun compactionTriggerIsEightyPercentOfTheBudget() {
        assertEquals(800L, ContextBudget.triggerThreshold(1_000L))
        assertEquals(600L, ContextBudget.targetThreshold(1_000L))
        // Below/at the trigger: no compaction. Just above it: compaction.
        assertFalse(ContextBudget.shouldCompact(800L, 1_000L))
        assertTrue(ContextBudget.shouldCompact(801L, 1_000L))
        // A zero budget must never trigger an endless compaction loop.
        assertFalse(ContextBudget.shouldCompact(1_000_000L, 0L))
    }

    @Test
    fun budgetTriggerAndTargetFollowTheUserSetting() {
        // The user's context-compression setting drives the 80%/60% trigger/target ratios.
        assertEquals(123_456L, ContextBudget.budgetFor(123_456L))
        assertEquals(98_764L, ContextBudget.triggerFor(123_456L))
        assertEquals(74_073L, ContextBudget.targetFor(123_456L))
    }

    @Test
    fun compactsAtEightyPercentOfBudgetNotOnlyWhenOverBudget() = runTest {
        val repo = FakeChatRepository(
            messages = listOf(
                msg(1, "user", longContent("user", 500), id = 1),
                msg(1, "assistant", longContent("assistant", 500), id = 2)
            )
        )
        // Learn the real message-only size under an effectively unlimited budget.
        val unlimited = FakeLlmSettingsStore(
            config = flowOf(LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 10_000_000L))
        )
        val tokens = LlmTokenEstimator.estimate(contextManager(repo, unlimited).compile(1).messages)
        assertTrue("the request must have a non-zero size", tokens > 0L)

        // Budget set so the request sits at ~90% of it: BELOW the budget, ABOVE the 80% trigger.
        // The old "compact only when the budget is exceeded" logic would NOT compact here.
        val budget = tokens * 100 / 90
        val limited = FakeLlmSettingsStore(
            config = flowOf(LlmSettings(endpoint = "", model = "m", contextCompressionTokens = budget))
        )

        val ctx = contextManager(repo, limited).compile(1)

        assertTrue(
            "compaction must fire at ~80% of the budget, not only once the budget is exceeded",
            ctx.compressed
        )
    }

    @Test
    fun reusesPersistedSummaryIncrementallyAndHidesItFromMessages() = runTest {
        val chat = ChatEntity(
            id = 1,
            title = "t",
            createdAt = 0,
            updatedAt = 0,
            summary = "СТАРОЕ РЕЗЮМЕ: пользователь любит чай.",
            contextState = "summarizedUpTo=2"
        )
        val repo = FakeChatRepository(
            messages = listOf(
                // id 1..2 are already covered by the persisted summary and must not be replayed raw
                msg(1, "user", longContent("ancient", 500), id = 1),
                msg(1, "assistant", longContent("ancient", 500), id = 2),
                msg(1, "user", longContent("fresh", 500), id = 3),
                msg(1, "assistant", longContent("fresh", 500), id = 4)
            ),
            chat = chat
        )
        val settings = FakeLlmSettingsStore(
            config = flowOf(LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 20L))
        )

        val ctx = contextManager(repo, settings).compile(1)

        assertTrue("over the tiny budget → must compact", ctx.compressed)
        val summary = repo.updatedSummary
        assertNotNull("the rolling summary must be persisted", summary)
        assertTrue(
            "the previously persisted summary must be reused, not recomputed",
            summary!!.contains("СТАРОЕ РЕЗЮМЕ")
        )
        // Already-summarised messages are not replayed in full, and the freshly dropped ones are
        // folded into the summary (incremental update).
        assertTrue(
            "already-summarised messages must not be sent raw",
            ctx.messages.none { it.content?.contains("ancient") == true }
        )
        assertTrue(
            "the reused summary must be present in the compiled context",
            ctx.messages.any { it.role == "user" && it.content?.contains("СТАРОЕ РЕЗЮМЕ") == true }
        )
        // The boundary advances to the newest summarised message.
        assertEquals("summarizedUpTo=4", repo.updatedContextState)
        // The summary must NOT leak into user-visible chat messages (nothing persisted).
        assertTrue(
            "compaction must not persist any chat message",
            repo.addedMessages.isEmpty()
        )
    }

    @Test
    fun currentUserTurnIsNeverFoldedAwayEvenWhenTheFixedPartExceedsTheBudget() = runTest {
        // A budget smaller than the system prompt alone: the fixed part can never fit, yet the
        // user's just-sent request must still reach the model (only older turns are summarised).
        val repo = FakeChatRepository(
            messages = listOf(
                msg(1, "user", longContent("old", 500), id = 1),
                msg(1, "assistant", longContent("old", 500), id = 2),
                msg(1, "user", "текущий вопрос", id = 3)
            )
        )
        val settings = FakeLlmSettingsStore(
            config = flowOf(LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 10L))
        )

        val ctx = contextManager(repo, settings).compile(1)

        assertTrue("the tiny budget must trigger compaction", ctx.compressed)
        assertTrue(
            "older turns must be folded into the summary",
            ctx.messages.any { it.role == "user" && it.content?.contains("Краткое изложение предыдущего диалога:") == true }
        )
        assertTrue(
            "the current user request must never be dropped",
            ctx.messages.any { it.role == "user" && it.content?.contains("текущий вопрос") == true }
        )
        assertTrue(
            "the older turns must no longer be replayed in full",
            ctx.messages.none { it.role == "assistant" }
        )
        assertEquals("summarizedUpTo=2", repo.updatedContextState)
    }

    @Test
    fun toolResultTruncationKeepsSmallOutputsAndMarksLargeOnes() {
        val small = "небольшой вывод"
        assertEquals(small, ToolOutputLimiter.limit(small))

        val huge = "x".repeat(ToolOutputLimiter.MAX_CHARS + 5_000)
        val limited = ToolOutputLimiter.limit(huge)
        assertTrue("large tool output must be truncated", limited.length < huge.length)
        assertTrue(
            "truncation must be marked honestly",
            limited.contains("вывод усечён") && limited.contains(ToolOutputLimiter.MAX_CHARS.toString())
        )
    }

    /**
     * A repository whose reads are genuinely scoped by chat id (unlike [FakeChatRepository], which
     * returns the same list regardless of the requested chat). It mirrors the real Room DAO
     * queries (`WHERE chatId = :chatId`), so any cross-chat leakage fails the tests loudly.
     */
    class PerChatChatRepository(
        private val chats: Map<Long, ChatEntity>,
        private val messagesByChat: Map<Long, List<MessageEntity>>
    ) : ChatRepository {
        /** Summary written per chat id (key = chat that was compacted). */
        val summaries = mutableMapOf<Long, String>()

        /** Compaction boundary written per chat id. */
        val contextStates = mutableMapOf<Long, String?>()

        override fun observeChats(): Flow<List<ChatEntity>> = flowOf(chats.values.toList())

        override fun observeChat(chatId: Long): Flow<ChatEntity?> = flowOf(chats[chatId])

        override suspend fun createChat(title: String): Long = TODO("unused")

        override suspend fun chatExists(chatId: Long): Boolean = true

        override fun observeMessages(chatId: Long): Flow<List<MessageEntity>> =
            flowOf(messagesByChat[chatId].orEmpty())

        override suspend fun addMessage(
            chatId: Long,
            role: String,
            content: String,
            toolName: String?
        ): Long = TODO("unused")

        override suspend fun updateMessageContent(messageId: Long, content: String) = TODO("unused")

        override suspend fun setStreaming(messageId: Long, isStreaming: Boolean) = TODO("unused")

        override suspend fun clearStaleStreaming(): Int = 0

        override suspend fun deleteChat(chatId: Long) = TODO("unused")

        override suspend fun renameChat(chatId: Long, title: String) = TODO("unused")

        override suspend fun updateSummary(chatId: Long, summary: String) {
            summaries[chatId] = summary
        }

        override suspend fun updateContextState(chatId: Long, state: String?) {
            contextStates[chatId] = state
        }

        override suspend fun pruneToLimit(keep: Int) {}

        override suspend fun deleteAllChats() {}

        override suspend fun setPinned(chatId: Long, pinned: Boolean) = Unit

        override suspend fun clearMessages(chatId: Long) = Unit

        override suspend fun mostRecentChatId(): Long? = null
    }

    private fun twoChatRepository(): PerChatChatRepository = PerChatChatRepository(
        chats = mapOf(
            1L to ChatEntity(id = 1, title = "A", createdAt = 0, updatedAt = 0),
            2L to ChatEntity(id = 2, title = "B", createdAt = 0, updatedAt = 0)
        ),
        messagesByChat = mapOf(
            1L to listOf(
                msg(1, "user", "секретный факт ПЕРВОГО чата", id = 1),
                msg(1, "assistant", "ответ первого чата", id = 2)
            ),
            2L to listOf(
                msg(2, "user", "секретный факт ВТОРОГО чата", id = 3),
                msg(2, "assistant", "ответ второго чата", id = 4)
            )
        )
    )

    @Test
    fun messagesAreIsolatedPerChat() = runTest {
        val settings = FakeLlmSettingsStore(
            config = flowOf(
                LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 1_000_000L)
            )
        )
        val manager = contextManager(twoChatRepository(), settings)

        val firstText = manager.compile(1).messages.mapNotNull { it.content }
        val secondText = manager.compile(2).messages.mapNotNull { it.content }

        assertTrue("chat 1 must see its own history", firstText.any { it.contains("ПЕРВОГО чата") })
        assertFalse("chat 1 must NOT see chat 2's history", firstText.any { it.contains("ВТОРОГО чата") })
        assertTrue("chat 2 must see its own history", secondText.any { it.contains("ВТОРОГО чата") })
        assertFalse("chat 2 must NOT see chat 1's history", secondText.any { it.contains("ПЕРВОГО чата") })
    }

    @Test
    fun persistedSummaryAndBoundaryAreIsolatedPerChat() = runTest {
        val settings = FakeLlmSettingsStore(
            config = flowOf(
                LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 1_000_000L)
            )
        )
        val repo = PerChatChatRepository(
            chats = mapOf(
                1L to ChatEntity(
                    id = 1, title = "A", createdAt = 0, updatedAt = 0,
                    summary = "РЕЗЮМЕ_АЛЬФА", contextState = "summarizedUpTo=1"
                ),
                2L to ChatEntity(
                    id = 2, title = "B", createdAt = 0, updatedAt = 0,
                    summary = "РЕЗЮМЕ_БЕТА", contextState = "summarizedUpTo=3"
                )
            ),
            messagesByChat = mapOf(
                1L to listOf(
                    msg(1, "user", "старое сообщение альфы", id = 1),
                    msg(1, "user", "новое сообщение альфы", id = 2)
                ),
                2L to listOf(
                    msg(2, "user", "старое сообщение беты", id = 3),
                    msg(2, "user", "новое сообщение беты", id = 4)
                )
            )
        )
        val manager = contextManager(repo, settings)

        val firstText = manager.compile(1).messages.mapNotNull { it.content }
        val secondText = manager.compile(2).messages.mapNotNull { it.content }

        assertTrue(
            "chat 1 reuses only its own persisted summary",
            firstText.any { it.contains("РЕЗЮМЕ_АЛЬФА") }
        )
        assertFalse(
            "chat 1 must never reuse chat 2's summary",
            firstText.any { it.contains("РЕЗЮМЕ_БЕТА") }
        )
        assertTrue(
            "chat 2 reuses only its own persisted summary",
            secondText.any { it.contains("РЕЗЮМЕ_БЕТА") }
        )
        assertFalse(
            "chat 2 must never reuse chat 1's summary",
            secondText.any { it.contains("РЕЗЮМЕ_АЛЬФА") }
        )
        // The summarizedUpTo boundary is per chat: only the messages AFTER it are replayed raw.
        assertFalse(
            "messages already folded into chat 1's summary are not replayed",
            firstText.any { it.contains("старое сообщение альфы") }
        )
        assertTrue(
            "fresh chat 1 messages are replayed",
            firstText.any { it.contains("новое сообщение альфы") }
        )
        assertFalse(
            "messages already folded into chat 2's summary are not replayed",
            secondText.any { it.contains("старое сообщение беты") }
        )
        assertTrue(
            "fresh chat 2 messages are replayed",
            secondText.any { it.contains("новое сообщение беты") }
        )
    }

    @Test
    fun compactionPersistsSummaryAndBoundaryOnlyForTheCompiledChat() = runTest {
        val settings = FakeLlmSettingsStore(
            config = flowOf(LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 20L))
        )
        val repo = PerChatChatRepository(
            chats = mapOf(
                1L to ChatEntity(id = 1, title = "A", createdAt = 0, updatedAt = 0),
                2L to ChatEntity(id = 2, title = "B", createdAt = 0, updatedAt = 0)
            ),
            messagesByChat = mapOf(
                1L to listOf(
                    msg(1, "user", longContent("alpha", 500), id = 1),
                    msg(1, "assistant", longContent("alpha", 500), id = 2),
                    msg(1, "user", longContent("alpha", 500), id = 3)
                ),
                2L to listOf(
                    msg(2, "user", longContent("beta", 500), id = 4),
                    msg(2, "assistant", longContent("beta", 500), id = 5),
                    msg(2, "user", longContent("beta", 500), id = 6)
                )
            )
        )
        val manager = contextManager(repo, settings)

        val first = manager.compile(1)

        assertTrue("chat 1 must compact under the tiny budget", first.compressed)
        assertEquals(
            "the summary must be stored on chat 1 only",
            setOf(1L),
            repo.summaries.keys
        )
        assertEquals(
            "the boundary must be stored on chat 1 only",
            setOf(1L),
            repo.contextStates.keys
        )
        assertTrue(
            "chat 1's summary must describe chat 1's turns",
            repo.summaries.getValue(1L).contains("alpha")
        )
        assertFalse(
            "chat 1's summary must never contain chat 2's turns",
            repo.summaries.getValue(1L).contains("beta")
        )

        // Compiling the OTHER chat must compact independently and must not touch chat 1's state.
        val firstSummaryBefore = repo.summaries.getValue(1L)
        val second = manager.compile(2)
        assertTrue("chat 2 must also compact under the tiny budget", second.compressed)
        assertTrue(
            "chat 2's summary must describe chat 2's turns",
            repo.summaries.getValue(2L).contains("beta")
        )
        assertEquals(
            "chat 1's summary must be untouched by chat 2's compaction",
            firstSummaryBefore,
            repo.summaries.getValue(1L)
        )
    }

    // ------------------------------------------------ facts before compaction

    @Test
    fun extractsDurableFactsFromTheDroppedSliceBeforeCompaction() = runTest {
        val settings = LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 20L)
        val repo = FakeChatRepository(
            messages = listOf(
                msg(1, "user", longContent("user", 500)),
                msg(1, "assistant", longContent("assistant", 500)),
                msg(1, "user", longContent("user", 500)),
                msg(1, "assistant", longContent("assistant", 500))
            )
        )
        val assist = RecordingMemoryAssistRepository()
        val llm = FakeLlmClient(
            listOf(
                LlmEvent.Text("- Пользователь любит чай\n2) Живёт в Москве"),
                LlmEvent.Done
            )
        )

        val ctx = contextManager(
            repo,
            FakeLlmSettingsStore(flowOf(settings)),
            assistRepository = assist,
            llmClient = llm
        ).compile(1)

        assertTrue("over the tiny budget → must compact", ctx.compressed)
        assertEquals("exactly one pre-compaction extraction pass", 1, llm.calls)
        assertEquals(
            "parsed facts must be saved with tag «auto» and the noise importance (0), " +
                "so MemoryGcWorker can collect them once untouched (BUGS.md M7)",
            listOf(
                RecordingMemoryAssistRepository.Saved("Пользователь любит чай", "auto", AUTO_IMPORTANCE),
                RecordingMemoryAssistRepository.Saved("Живёт в Москве", "auto", AUTO_IMPORTANCE)
            ),
            assist.saved
        )
        assertNotNull("the rolling summary must still be persisted", repo.updatedSummary)
    }

    @Test
    fun doesNotExtractWhenNothingIsDropped() = runTest {
        val settings = LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 1_000_000L)
        val repo = FakeChatRepository(messages = listOf(msg(1, "user", "привет")))
        val assist = RecordingMemoryAssistRepository()
        val llm = FakeLlmClient(listOf(LlmEvent.Text("факт"), LlmEvent.Done))

        val ctx = contextManager(
            repo,
            FakeLlmSettingsStore(flowOf(settings)),
            assistRepository = assist,
            llmClient = llm
        ).compile(1)

        assertFalse("under the limit → no compaction", ctx.compressed)
        assertEquals("no extraction pass without dropped messages", 0, llm.calls)
        assertTrue(assist.saved.isEmpty())
    }

    @Test
    fun skipsExtractionWhenTheLlmIsNotConfigured() = runTest {
        val settings = LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 20L)
        val repo = FakeChatRepository(
            messages = listOf(
                msg(1, "user", longContent("user", 500)),
                msg(1, "assistant", longContent("assistant", 500)),
                msg(1, "user", longContent("user", 500)),
                msg(1, "assistant", longContent("assistant", 500))
            )
        )
        val assist = RecordingMemoryAssistRepository()
        val llm = FakeLlmClient(listOf(LlmEvent.Text("факт"), LlmEvent.Done))

        val ctx = contextManager(
            repo,
            FakeLlmSettingsStore(flowOf(settings), configured = false),
            assistRepository = assist,
            llmClient = llm
        ).compile(1)

        assertTrue("compaction must still happen", ctx.compressed)
        assertEquals("no LLM call when not configured", 0, llm.calls)
        assertTrue(assist.saved.isEmpty())
        assertNotNull("the rolling summary must still be persisted", repo.updatedSummary)
    }

    @Test
    fun extractionFailureNeverBreaksCompaction() = runTest {
        val settings = LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 20L)
        val repo = FakeChatRepository(
            messages = listOf(
                msg(1, "user", longContent("user", 500)),
                msg(1, "assistant", longContent("assistant", 500)),
                msg(1, "user", longContent("user", 500)),
                msg(1, "assistant", longContent("assistant", 500))
            )
        )
        val assist = RecordingMemoryAssistRepository()
        val llm = FakeLlmClient(listOf(LlmEvent.Error("нет соединения", true)))

        val ctx = contextManager(
            repo,
            FakeLlmSettingsStore(flowOf(settings)),
            assistRepository = assist,
            llmClient = llm
        ).compile(1)

        assertTrue("compaction must succeed despite the extraction failure", ctx.compressed)
        assertNotNull("the rolling summary must still be persisted", repo.updatedSummary)
        assertTrue(assist.saved.isEmpty())
        assertTrue(
            "the compiled context must still carry the summary",
            ctx.messages.any {
                it.role == "user" && it.content?.contains("Краткое изложение") == true
            }
        )
    }

    /**
     * Characterization snapshot (W5a): the assembled system prompt is a wire-level artifact whose
     * block order, headers, separators and wording must stay byte-for-byte identical across
     * refactors. This pins the cloud variant for a deterministic input (fixed
     * clock/zone/locale, empty memory/device-facts/people blocks, default trusted-sources block and
     * no user append), so any drift in the assembled prompt fails loudly.
     */
    @Test
    fun systemPromptSnapshotIsByteStable() = runTest {
        val settings = LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 1_000_000L)
        val system = contextManager(
            FakeChatRepository(messages = listOf(msg(1, "user", "привет"))),
            FakeLlmSettingsStore(flowOf(settings))
        ).compile(1).messages.first().content!!

        // The SYSTEM message is exactly the STATIC training prompt (byte-for-byte), independent of
        // any volatile block. This is what makes the local KV-cache prefix stable.
        assertEquals(SystemPrompts.BASE_SYSTEM_PROMPT, system)
        assertEquals(
            "static system prompt snapshot",
            "44389455dc6d8672548ea3416dcb53196c16256213eb14030a50b0ace0b76edb",
            sha256(system)
        )
        // The reply-language directive is language-neutral in the static head: it must NOT appear
        // here (it lives in the runtime tail), so the prefix stays stable across a language switch.
        assertFalse(
            "the static head must stay language-neutral",
            system.contains("REPLY LANGUAGE")
        )
        assertFalse(
            "the static head must not hardcode a reply language",
            system.contains("respond to the user in")
        )

        // The ENGLISH base prompt is selected EXPLICITLY by language (the default/RU path above is
        // unchanged) and is byte-identical to the English training prompt — a distinct snapshot.
        val english = SystemPromptBuilder.buildStatic(AppLanguage.ENGLISH)
        assertEquals(SystemPrompts.BASE_SYSTEM_PROMPT_EN, english)
        assertEquals(
            "static English system prompt snapshot",
            "7b8d9bb359729169676877c244a50c4320c91a88804b53d82bb5e7a8c9170ad8",
            sha256(english)
        )
        assertTrue("the English prompt must state the agent identity", english.startsWith("You are Меха"))
        assertTrue(
            "the English base prompt must instruct English replies",
            english.contains("ALWAYS respond to the user in English")
        )
        assertFalse(
            "the English base prompt must differ from the Russian one",
            sha256(system) == sha256(english)
        )
    }

    /**
     * [SystemPromptBuilder.buildStatic] selects the base prompt by language: the default and every
     * non-English tag return the byte-identical Russian prompt, while English (any variant) returns
     * the English one.
     */
    @Test
    fun buildStaticSelectsTheBasePromptByLanguage() {
        assertEquals(SystemPrompts.BASE_SYSTEM_PROMPT, SystemPromptBuilder.buildStatic())
        assertEquals(SystemPrompts.BASE_SYSTEM_PROMPT, SystemPromptBuilder.buildStatic("ru"))
        assertEquals(SystemPrompts.BASE_SYSTEM_PROMPT, SystemPromptBuilder.buildStatic("de"))
        assertEquals(SystemPrompts.BASE_SYSTEM_PROMPT, SystemPromptBuilder.buildStatic(""))
        assertEquals(SystemPrompts.BASE_SYSTEM_PROMPT_EN, SystemPromptBuilder.buildStatic("en"))
        assertEquals(SystemPrompts.BASE_SYSTEM_PROMPT_EN, SystemPromptBuilder.buildStatic("en-US"))
        assertEquals(
            "the default (RU) path must be byte-identical to the constant",
            sha256(SystemPrompts.BASE_SYSTEM_PROMPT),
            sha256(SystemPromptBuilder.buildStatic("ru"))
        )
        assertFalse(
            "English and Russian base prompts must be distinct",
            SystemPromptBuilder.buildStatic("en") == SystemPromptBuilder.buildStatic()
        )
    }

    /**
     * The reply-language directive rides in the RUNTIME TAIL and reflects the configured UI language
     * ([LlmSettings.language]): ru → Russian (the default), en → English. It must never touch the
     * byte-stable static head, so both cloud and the on-device model get it in the volatile tail.
     */
    @Test
    fun replyLanguageDirectiveFollowsTheConfiguredLanguage() = runTest {
        val ru = LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 1_000_000L)
        val en = ru.copy(language = "en")

        val ruCtx = contextManager(
            FakeChatRepository(messages = listOf(msg(1, "user", "привет"))),
            FakeLlmSettingsStore(flowOf(ru))
        ).compile(1)
        val enCtx = contextManager(
            FakeChatRepository(messages = listOf(msg(1, "user", "hello"))),
            FakeLlmSettingsStore(flowOf(en))
        ).compile(1)

        // The SYSTEM head is the STATIC training prompt OF THE CONFIGURED LANGUAGE (RU stays
        // byte-identical to before; EN selects the English prompt), while the reply-language directive
        // rides only in the volatile tail.
        assertEquals(SystemPrompts.BASE_SYSTEM_PROMPT, ruCtx.messages.first().content)
        assertEquals(SystemPrompts.BASE_SYSTEM_PROMPT_EN, enCtx.messages.first().content)
        assertFalse(
            "the system head must differ by language (EN gets the English prompt)",
            ruCtx.messages.first().content == enCtx.messages.first().content
        )

        assertTrue(
            "ru must instruct Russian in the runtime tail",
            lastUserContent(ruCtx.messages).contains(
                "REPLY LANGUAGE: Always respond to the user in Russian, " +
                    "regardless of internal reasoning language."
            )
        )
        assertTrue(
            "en must instruct English in the runtime tail",
            lastUserContent(enCtx.messages).contains(
                "REPLY LANGUAGE: Always respond to the user in English, " +
                    "regardless of internal reasoning language."
            )
        )
        assertFalse(
            "the ru tail must not instruct English",
            lastUserContent(ruCtx.messages).contains("respond to the user in English")
        )
    }

    /** The pure directive helper maps tags to names and defaults unknown/blank values to Russian. */
    @Test
    fun replyLanguageDirectiveNormalizesUnknownTagsToRussian() {
        val russian = "REPLY LANGUAGE: Always respond to the user in Russian, " +
            "regardless of internal reasoning language."
        assertEquals(russian, SystemPromptBuilder.replyLanguageDirective(null))
        assertEquals(russian, SystemPromptBuilder.replyLanguageDirective(""))
        assertEquals(russian, SystemPromptBuilder.replyLanguageDirective("ru"))
        assertEquals(russian, SystemPromptBuilder.replyLanguageDirective("de"))

        assertEquals("English", SystemPromptBuilder.replyLanguageName("en"))
        assertEquals("English", SystemPromptBuilder.replyLanguageName("en-US"))
        assertEquals("Russian", SystemPromptBuilder.replyLanguageName(null))
        assertTrue(
            SystemPromptBuilder.replyLanguageDirective("en-US").contains("in English,")
        )
    }

    /**
     * The core refactor invariant: the SYSTEM message is byte-identical for two compiles with
     * DIFFERENT volatile inputs (time, chat id, recalled memory), and all that volatile data lives
     * in the LAST user message — never in the system prefix.
     */
    @Test
    fun systemMessageIsByteStableAcrossVolatileInputsAndTheTailCarriesThem() = runTest {
        val settings = LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 1_000_000L)

        val assistA = RecordingAssistRepository(
            MemoryHit(
                note = NoteEntity(id = 1, kind = "memory", text = "пользователь любит чай", tags = "чай", importance = 1),
                score = 2.0,
                lexicallyMatched = true
            )
        )
        val ctxA = contextManager(
            FakeChatRepository(messages = listOf(msg(1, "user", "привет"))),
            FakeLlmSettingsStore(flowOf(settings)),
            FakeTimeProvider(),
            assistRepository = assistA
        ).compile(1)

        val assistB = RecordingAssistRepository(
            MemoryHit(
                note = NoteEntity(id = 2, kind = "memory", text = "пользователь любит кофе", tags = "кофе", importance = 2),
                score = 2.0,
                lexicallyMatched = true
            )
        )
        val ctxB = contextManager(
            FakeChatRepository(messages = listOf(msg(2, "user", "здравствуй"))),
            FakeLlmSettingsStore(flowOf(settings)),
            FakeTimeProvider(instant = Instant.parse("2026-12-25T08:00:00Z").toEpochMilli()),
            assistRepository = assistB
        ).compile(2)

        // SYSTEM is byte-identical regardless of the volatile inputs.
        assertEquals(SystemPrompts.BASE_SYSTEM_PROMPT, ctxA.messages.first().content)
        assertEquals(ctxA.messages.first().content, ctxB.messages.first().content)
        assertEquals(sha256(ctxA.messages.first().content!!), sha256(ctxB.messages.first().content!!))
        assertFalse("no volatile block may leak into the system prefix", ctxA.messages.first().content!!.contains("ТЕКУЩИЙ ЧАТ"))
        assertFalse("no device context may leak into the system prefix", ctxA.messages.first().content!!.contains("DEVICE CONTEXT"))

        // The volatile data is in the LAST user message of each compile.
        val userA = lastUserContent(ctxA.messages)
        val userB = lastUserContent(ctxB.messages)
        assertTrue("compile A must carry its own clock", userA.contains("20:45"))
        assertTrue("compile A must carry its own chat id", userA.contains("ТЕКУЩИЙ ЧАТ: id=1"))
        assertTrue("compile A must carry its own recalled memory", userA.contains("пользователь любит чай"))
        assertTrue("compile B must carry its own chat id", userB.contains("ТЕКУЩИЙ ЧАТ: id=2"))
        assertTrue("compile B must carry its own recalled memory", userB.contains("пользователь любит кофе"))
        assertFalse("compile B must not carry compile A's memory", userB.contains("пользователь любит чай"))
    }

    /**
     * The agent must always know the id of the chat it is talking in, so «переименуй этот чат»
     * resolves without asking which chat is meant. The block is rendered from the run's chat id and
     * placed between the current-time block and the device context.
     */
    @Test
    fun currentChatIdIsCarriedInTheSystemPrompt() = runTest {
        val settings = LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 1_000_000L)
        val tail = lastUserContent(
            contextManager(
                FakeChatRepository(messages = listOf(msg(42, "user", "привет"))),
                FakeLlmSettingsStore(flowOf(settings))
            ).compile(42).messages
        )

        assertTrue(
            "the runtime tail must carry the run's chat id",
            tail.contains("ТЕКУЩИЙ ЧАТ: id=42.")
        )
        assertTrue(
            "the block must explain the «этот чат» shorthand",
            tail.contains("«этот чат»/«текущий чат» относятся к нему")
        )
        assertFalse(
            "a different chat must not leak the wrong id",
            tail.contains("ТЕКУЩИЙ ЧАТ: id=43.")
        )
    }

    /** The block is optional: without a chat id (null) it renders nothing at all. */
    @Test
    fun currentChatBlockIsEmptyWithoutAChatId() {
        assertEquals("", renderCurrentChatBlock(null))
        assertEquals(
            "ТЕКУЩИЙ ЧАТ: id=7. Фразы «этот чат»/«текущий чат» относятся к нему.",
            renderCurrentChatBlock(7)
        )
    }

    /**
     * Fresh-chat prompt budget: the base prompt is the single biggest fixed cost of every new chat.
     * After the per-domain capability catalog (which merely restated tool schemas) was replaced by a
     * short [tool_search] discovery note, this locks the condensed size and the policy markers that
     * must survive any future condensation.
     */
    @Test
    fun baseSystemPromptStaysWithinFreshChatTokenBudget() {
        val cloudTokens = LlmTokenEstimator.estimate(SystemPrompts.BASE_SYSTEM_PROMPT)
        println("BASE_PROMPT_TOKENS cloud=$cloudTokens")
        assertTrue("cloud BASE_SYSTEM_PROMPT must stay under 3.9k tokens, was $cloudTokens", cloudTokens <= 3_900L)

        assertTrue(
            "must instruct tool_search discovery",
            SystemPrompts.BASE_SYSTEM_PROMPT.contains("tool_search")
        )
        assertTrue(
            "the compact capability index must name the commonly-forgotten chat rename tool",
            SystemPrompts.BASE_SYSTEM_PROMPT.contains("chat_rename")
        )
        assertTrue(
            "must enforce tool_search before claiming a capability is missing",
            SystemPrompts.BASE_SYSTEM_PROMPT.contains(
                "claim a capability or tool is missing without checking tool_search"
            )
        )
        assertTrue(
            "must forbid fabricating a successful outcome",
            SystemPrompts.BASE_SYSTEM_PROMPT.contains("NEVER fabricate")
        )
        assertFalse(
            "the reply language must be driven by the runtime directive, not hardcoded in the static prompt",
            SystemPrompts.BASE_SYSTEM_PROMPT.contains("respond to the user in")
        )
    }

    /**
     * Prefix-cache contract (CONTEXT_TASKS_V2 §3): the same history compiled twice inside the same
     * minute must produce a byte-identical request. If this fails, the volatile tail has crept into
     * the prefix and every turn pays a full re-prefill on a local model.
     */
    @Test
    fun twoCompilesOfTheSameTurnProduceAByteIdenticalRequest() = runTest {
        val settings = LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 1_000_000L)
        val repo = FakeChatRepository(
            messages = listOf(
                msg(1, "user", "привет", id = 1),
                msg(1, "assistant", "здравствуй", id = 2),
                msg(1, "user", "который час?", id = 3)
            )
        )
        val clock = FakeTimeProvider()
        val manager = contextManager(repo, FakeLlmSettingsStore(flowOf(settings)), clock)

        val first = manager.compile(1)
        clock.instant += 30_000L // still inside the same minute
        val second = manager.compile(1)

        assertEquals(
            "identical history + identical minute must render an identical request",
            first.messages.map { it.role to it.content },
            second.messages.map { it.role to it.content }
        )
    }

    /**
     * The recall nudge and every other volatile block belong to the runtime tail: a recall turn and a
     * non-recall turn over the same history may differ ONLY in the last user message.
     */
    @Test
    fun recallHintChangesOnlyTheRuntimeTail() = runTest {
        val settings = LlmSettings(endpoint = "", model = "m", contextCompressionTokens = 1_000_000L)
        val prefix = listOf(
            msg(1, "user", "привет", id = 1),
            msg(1, "assistant", "здравствуй", id = 2)
        )
        val neutral = contextManager(
            FakeChatRepository(prefix + msg(1, "user", "какая погода?", id = 3)),
            FakeLlmSettingsStore(flowOf(settings))
        ).compile(1)
        val recall = contextManager(
            FakeChatRepository(prefix + msg(1, "user", "помнишь, что мы решили?", id = 3)),
            FakeLlmSettingsStore(flowOf(settings))
        ).compile(1)

        assertTrue(
            "the recall turn must carry the hint in its tail",
            lastUserContent(recall.messages).contains("RECALL HINT")
        )
        assertFalse(
            "the neutral turn must not carry the hint",
            lastUserContent(neutral.messages).contains("RECALL HINT")
        )
        assertEquals(
            "everything before the last message must be byte-identical",
            neutral.messages.dropLast(1).map { it.role to it.content },
            recall.messages.dropLast(1).map { it.role to it.content }
        )
    }

    private fun lastUserContent(messages: List<LlmMessage>): String =
        messages.last { it.role == "user" }.content.orEmpty()

    private fun sha256(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
