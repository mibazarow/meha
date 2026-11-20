package com.andharness.ru.data.tools.assist

import com.andharness.ru.data.db.entity.NoteEntity
import com.andharness.ru.data.db.entity.TodoEntity
import com.andharness.ru.data.schedule.ScheduledTaskActions
import com.andharness.ru.data.tools.schedule.ScheduleToolsHandler
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.assist.AssistStats
import com.andharness.ru.domain.assist.MemoryHit
import com.andharness.ru.domain.common.TimeProvider
import com.andharness.ru.domain.schedule.ScheduleRepository
import com.andharness.ru.domain.schedule.ScheduleScheduler
import com.andharness.ru.domain.schedule.ScheduledTask
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

/**
 * BUG-059 regression tests: a destructive handler must report success ONLY when a real re-check
 * confirms the effect. A fake repository that "reports" a delete but leaves the row in place (or
 * refuses to delete but matched a row) must produce `success=false` with honest wording — never a
 * false «готово»/«Удалено …».
 */
class DestructiveDeleteVerificationTest {

    /** In-memory [AssistRepository]; the `*DeleteWorks` flags simulate a lying/refusing provider. */
    private class FakeAssistRepository : AssistRepository {
        val notes = mutableListOf<NoteEntity>()
        val todos = mutableListOf<TodoEntity>()
        var noteDeleteWorks = true
        var memoryDeleteWorks = true
        var todoDeleteWorks = true
        private var nextNote = 1L
        private var nextTodo = 1L

        override suspend fun listNotes(kind: String, limit: Int): List<NoteEntity> =
            notes.filter { it.kind == kind }.sortedByDescending { it.id }.take(limit)

        override suspend fun searchNotes(kind: String, query: String, limit: Int): List<NoteEntity> =
            notes.filter { it.kind == kind && it.text.contains(query) }.take(limit)

        override suspend fun addNote(kind: String, text: String): Long {
            val id = nextNote++
            notes += NoteEntity(id = id, kind = kind, text = text)
            return id
        }

        override suspend fun deleteNoteById(id: Long): Boolean =
            noteDeleteWorks && notes.removeAll { it.id == id }

        override suspend fun listMemories(limit: Int): List<NoteEntity> =
            notes.filter { it.kind == "memory" }.sortedByDescending { it.id }.take(limit)

        override suspend fun deleteMemory(id: Long): Boolean =
            memoryDeleteWorks && notes.removeAll { it.id == id && it.kind == "memory" }

        override suspend fun stats(): AssistStats = AssistStats(notes.size, 0, emptyMap())

        override suspend fun saveMemory(text: String, tags: String, importance: Int): Long {
            val id = nextNote++
            notes += NoteEntity(id = id, kind = "memory", text = text, tags = tags, importance = importance)
            return id
        }

        override suspend fun searchMemories(query: String, limit: Int): List<NoteEntity> = emptyList()

        override suspend fun searchMemoriesRanked(query: String, limit: Int): List<MemoryHit> = emptyList()

        override suspend fun listTodos(limit: Int): List<TodoEntity> =
            todos.sortedByDescending { it.id }.take(limit)

        override suspend fun addTodo(text: String): Long {
            val id = nextTodo++
            todos += TodoEntity(id = id, text = text)
            return id
        }

        override suspend fun setTodoDone(id: Long, done: Boolean): Boolean {
            val t = todos.firstOrNull { it.id == id } ?: return false
            val idx = todos.indexOf(t)
            todos[idx] = t.copy(done = done)
            return true
        }

        override suspend fun deleteTodo(id: Long): Boolean =
            todoDeleteWorks && todos.removeAll { it.id == id }
    }

    private fun assistHandler(repository: AssistRepository, name: String): ToolHandler =
        AssistToolsHandler(repository).handlers().first { it.name == name }

    // ---- contacts: handler-level contract is already covered on-device; the decision logic is ----
    // ---- the same "success only when the row is gone" rule exercised below.                    ----

    @Test
    fun noteDeleteByQueryReportsRealCount() = runTest {
        val repo = FakeAssistRepository()
        repo.addNote("note", "купить хлеб")
        repo.addNote("note", "купить молоко")
        repo.addNote("note", "позвонить маме")
        val handler = assistHandler(repo, "note_delete")

        val result = handler.execute(buildJsonObject { put("query", "купить") })

        assertTrue("two matched notes must be deleted: ${result.output}", result.success)
        assertTrue("real count reported", result.output.contains("Удалено заметок: 2"))
        assertFalse(repo.notes.any { it.text.startsWith("купить") })
    }

    @Test
    fun noteDeleteByQueryFailsHonestlyWhenNothingDeleted() = runTest {
        val repo = FakeAssistRepository()
        repo.addNote("note", "купить хлеб")
        repo.noteDeleteWorks = false
        val handler = assistHandler(repo, "note_delete")

        val result = handler.execute(buildJsonObject { put("query", "купить") })

        assertFalse("matched rows that were not deleted must NOT be a success: ${result.output}", result.success)
        assertTrue("honest wording", result.output.contains("НЕ выполнено"))
        assertTrue("row is still there", repo.notes.isNotEmpty())
    }

    @Test
    fun noteDeleteByQueryFailsWhenSurvivorRemains() = runTest {
        // The repository claims success but leaves the row visible: success must not be reported.
        val repo = FakeAssistRepository()
        repo.addNote("note", "купить хлеб")
        val lying = object : AssistRepository by repo {
            override suspend fun deleteNoteById(id: Long): Boolean = true
        }
        val handler = assistHandler(lying, "note_delete")

        val result = handler.execute(buildJsonObject { put("query", "купить") })

        assertFalse("survivor after a 'successful' delete must fail: ${result.output}", result.success)
        assertTrue("honest wording", result.output.contains("НЕ подтверждено"))
    }

    @Test
    fun memoryDeleteVerifiedByRequery() = runTest {
        val repo = FakeAssistRepository()
        val id = repo.saveMemory("люблю кофе")
        val handler = assistHandler(repo, "memory_delete")

        val ok = handler.execute(buildJsonObject { put("id", id.toString()) })
        assertTrue("verified delete must succeed: ${ok.output}", ok.success)
        assertTrue(repo.notes.none { it.id == id })

        val again = handler.execute(buildJsonObject { put("id", id.toString()) })
        assertFalse("deleting a missing row must fail honestly", again.success)
    }

    @Test
    fun todoDeleteVerifiedByRequery() = runTest {
        val repo = FakeAssistRepository()
        val id = repo.addTodo("вынести мусор")
        val handler = assistHandler(repo, "todo_delete")

        val ok = handler.execute(buildJsonObject { put("id", id.toString()) })
        assertTrue("verified delete must succeed: ${ok.output}", ok.success)
        assertTrue(repo.todos.none { it.id == id })

        repo.addTodo("полить цветы")
        repo.todoDeleteWorks = false
        val noop = handler.execute(buildJsonObject { put("id", "1") })
        assertFalse("unconfirmed delete must fail honestly", noop.success)
    }

    // ---- schedule_delete: repository-backed, JVM-testable ------------------------------------

    private class FakeScheduleRepository : ScheduleRepository {
        val items = mutableListOf<ScheduledTask>()
        /** When false, `delete` claims success but leaves the row — the lying-provider case. */
        var deleteActuallyRemoves = true
        private var next = 1L

        override fun observeAll(): Flow<List<ScheduledTask>> = flowOf(items.toList())
        override suspend fun getAll(): List<ScheduledTask> = items.toList()
        override suspend fun getById(id: Long): ScheduledTask? = items.firstOrNull { it.id == id }
        override suspend fun create(task: ScheduledTask): Long {
            val id = next++
            items += task.copy(id = id)
            return id
        }

        override suspend fun update(task: ScheduledTask) {
            val i = items.indexOfFirst { it.id == task.id }
            if (i >= 0) items[i] = task
        }

        override suspend fun delete(id: Long): Boolean {
            // The lying-provider case: claim success but leave the row in place.
            if (!deleteActuallyRemoves) return true
            return items.removeAll { it.id == id }
        }

        override suspend fun setEnabled(id: Long, enabled: Boolean): Boolean {
            val i = items.indexOfFirst { it.id == id }
            if (i < 0) return false
            items[i] = items[i].copy(enabled = enabled)
            return true
        }

        override suspend fun markRun(id: Long, timestamp: Long) = Unit
        override suspend fun claimRun(id: Long, slot: Long): Boolean = true
    }

    private class FakeScheduler : ScheduleScheduler {
        override fun schedule(task: ScheduledTask) = Unit
        override fun cancel(taskId: Long) = Unit
        override fun rescheduleAll(tasks: List<ScheduledTask>) = Unit
        override fun scheduleOverdue(task: ScheduledTask, slotMs: Long) = Unit
    }

    private object FixedTime : TimeProvider {
        override fun nowMillis(): Long = 0L
        override fun zoneId(): ZoneId = ZoneId.of("UTC")
        override fun locale(): Locale = Locale.US
    }

    private fun scheduleHandler(repo: FakeScheduleRepository): ToolHandler {
        val actions = ScheduledTaskActions(repo, FakeScheduler())
        return ScheduleToolsHandler(repo, actions, timeProvider = FixedTime)
            .handlers().first { it.name == "schedule_delete" }
    }

    @Test
    fun scheduleDeleteVerifiedByRequery() = runTest {
        val repo = FakeScheduleRepository()
        val id = repo.create(
            ScheduledTask(
                id = 0, prompt = "проверь почту", hour = 9, minute = 0,
                daysMask = ScheduledTask.EVERY_DAY, enabled = true, lastRunAt = 0
            )
        )
        val handler = scheduleHandler(repo)

        val ok = handler.execute(buildJsonObject { put("id", id.toString()) })
        assertTrue("verified delete must succeed: ${ok.output}", ok.success)
        assertTrue("real count reported", ok.output.contains("Удалено задач: 1"))
        assertTrue(repo.getById(id) == null)
    }

    @Test
    fun scheduleDeleteFailsHonestlyWhenRowSurvives() = runTest {
        val repo = FakeScheduleRepository()
        val id = repo.create(
            ScheduledTask(
                id = 0, prompt = "проверь почту", hour = 9, minute = 0,
                daysMask = ScheduledTask.EVERY_DAY, enabled = true, lastRunAt = 0
            )
        )
        repo.deleteActuallyRemoves = false
        val handler = scheduleHandler(repo)

        val result = handler.execute(buildJsonObject { put("id", id.toString()) })

        assertFalse("a surviving row must NOT be reported as deleted: ${result.output}", result.success)
        assertTrue("honest wording", result.output.contains("НЕ подтверждено"))
        assertTrue("row is still there", repo.getById(id) != null)
    }
}
