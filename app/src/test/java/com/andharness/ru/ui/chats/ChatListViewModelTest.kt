package com.andharness.ru.ui.chats

import com.andharness.ru.data.db.entity.ChatEntity
import com.andharness.ru.data.db.entity.MessageEntity
import com.andharness.ru.domain.chat.ChatMessageMatch
import com.andharness.ru.domain.chat.ChatRepository
import com.andharness.ru.domain.chat.ChatSearch
import com.andharness.ru.domain.chat.ChatSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the chat-list search wiring in [ChatListViewModel]: a blank query never searches, a
 * non-blank query is debounced before it reaches [ChatSearch], and a burst of keystrokes cancels the
 * earlier jobs so only the final query is searched (exactly once). Deterministic via a
 * [StandardTestDispatcher] installed as `Dispatchers.Main`.
 */
class ChatListViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `blank query never searches and shows the normal list`() = runTest(dispatcher) {
        val search = FakeSearch()
        val vm = ChatListViewModel(EmptyChatRepository(), search, dispatcher)

        vm.onQueryChange("")
        advanceUntilIdle()

        assertTrue(search.queries.isEmpty())
        assertTrue(vm.uiState.value.results.isEmpty())
        assertFalse(vm.uiState.value.isSearching)
    }

    @Test
    fun `query reaches the search only after the debounce window`() = runTest(dispatcher) {
        val search = FakeSearch(
            results = listOf(ChatSearchResult(1L, "Работа", "сниппет совпадения", false, 1.0))
        )
        val vm = ChatListViewModel(EmptyChatRepository(), search, dispatcher)

        vm.onQueryChange("работа")
        assertTrue("isSearching should be true while the debounce is pending", vm.uiState.value.isSearching)

        advanceTimeBy(ChatListViewModel.SEARCH_DEBOUNCE_MS - 1)
        assertTrue("no search before the debounce elapses", search.queries.isEmpty())

        advanceUntilIdle()

        assertEquals(listOf("работа"), search.queries)
        assertEquals(1, vm.uiState.value.results.size)
        assertFalse(vm.uiState.value.isSearching)
    }

    @Test
    fun `rapid typing cancels prior jobs and searches only the final query once`() = runTest(dispatcher) {
        val search = FakeSearch()
        val vm = ChatListViewModel(EmptyChatRepository(), search, dispatcher)

        vm.onQueryChange("ра")
        advanceTimeBy(100)
        vm.onQueryChange("раб")
        advanceTimeBy(100)
        vm.onQueryChange("работа")
        advanceUntilIdle()

        assertEquals(listOf("работа"), search.queries)
        assertEquals("работа", vm.uiState.value.query)
        assertFalse(vm.uiState.value.isSearching)
    }

    @Test
    fun `clearing the query drops results and cancels the pending search`() = runTest(dispatcher) {
        val search = FakeSearch()
        val vm = ChatListViewModel(EmptyChatRepository(), search, dispatcher)

        vm.onQueryChange("работа")
        vm.onQueryChange("")
        advanceUntilIdle()

        assertTrue(search.queries.isEmpty())
        assertTrue(vm.uiState.value.results.isEmpty())
        assertFalse(vm.uiState.value.isSearching)
    }

    private class FakeSearch(
        private val results: List<ChatSearchResult> = emptyList()
    ) : ChatSearch {
        val queries = mutableListOf<String>()

        override suspend fun searchChats(query: String, limit: Int): List<ChatSearchResult> {
            queries += query
            return results
        }

        override suspend fun searchInChat(
            chatId: Long,
            query: String,
            limit: Int
        ): List<ChatMessageMatch> = emptyList()
    }

    /**
     * A [ChatRepository] whose observation flows never emit — enough to exercise the search wiring
     * without a database, since the list itself is not under test here.
     */
    private class EmptyChatRepository : ChatRepository {
        override fun observeChats(): Flow<List<ChatEntity>> = emptyFlow()
        override fun observeChat(chatId: Long): Flow<ChatEntity?> = emptyFlow()
        override suspend fun createChat(title: String): Long = 0L
        override suspend fun chatExists(chatId: Long): Boolean = false
        override fun observeMessages(chatId: Long): Flow<List<MessageEntity>> = emptyFlow()
        override suspend fun addMessage(
            chatId: Long,
            role: String,
            content: String,
            toolName: String?
        ): Long = 0L

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
}
