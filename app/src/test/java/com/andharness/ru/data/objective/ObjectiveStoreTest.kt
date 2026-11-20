package com.andharness.ru.data.objective

import com.andharness.ru.domain.objective.ObjectiveOutcome
import com.andharness.ru.domain.objective.ObjectiveRules
import com.andharness.ru.domain.objective.ObjectiveStatus
import com.andharness.ru.domain.objective.ObjectiveStepStatus
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android-free JVM tests of the file-backed [ObjectiveStore]: CRUD, atomic persistence across store
 * instances (the RESUME guarantee), bounds, terminal honesty, step transitions and progress.
 */
class ObjectiveStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("objective-store").toFile()

    @Test
    fun createBindsChatAndPersistsAcrossInstances() {
        val dir = tempDir()
        val store = ObjectiveStore(dir)
        val created = store.create(
            title = "Отчёт",
            goal = "Собрать и отправить отчёт",
            steps = listOf("собрать данные", "сделать отчёт", "отправить"),
            chatId = 42L,
            now = 1_000L
        )
        assertNotNull(created)
        assertEquals(ObjectiveStatus.ACTIVE, created!!.status)
        assertEquals(42L, created.chatId)
        assertEquals(listOf("s1", "s2", "s3"), created.steps.map { it.id })
        assertTrue(created.steps.all { it.status == ObjectiveStepStatus.PENDING })

        // A SECOND store instance over the same directory == a process/app restart.
        val reopened = ObjectiveStore(dir)
        val loaded = reopened.get(created.id)
        assertNotNull("objective must survive an app/process restart", loaded)
        assertEquals("Собрать и отправить отчёт", loaded!!.goal)
        assertEquals(ObjectiveStatus.ACTIVE, loaded.status)
        assertEquals(42L, loaded.chatId)
        assertEquals(3, loaded.steps.size)
        assertTrue(File(dir, ObjectiveStore.FILE_NAME).isFile)
    }

    @Test
    fun createRejectsBlankTitleOrGoal() {
        val store = ObjectiveStore(tempDir())
        assertNull(store.create("  ", "goal", emptyList(), 1L))
        assertNull(store.create("title", "", emptyList(), 1L))
        assertEquals(0, store.count())
    }

    @Test
    fun getResolvesByIdAndByTitle() {
        val store = ObjectiveStore(tempDir())
        val created = store.create("Позвонить маме", "Договориться о встрече", emptyList(), null)!!
        assertEquals(created.id, store.get(created.id)?.id)
        assertEquals(created.id, store.get("Позвонить маме")?.id)
        assertEquals(created.id, store.get("позвонить маме")?.id)
        assertNull(store.get("нет такой"))
    }

    @Test
    fun listOrderReflectsMostRecentUpdate() {
        val store = ObjectiveStore(tempDir())
        val a = store.create("A", "goal", listOf("шаг"), null, now = 100L)!!
        store.create("B", "goal", listOf("шаг"), null, now = 200L)
        store.updateStep(a.id, "s1", ObjectiveStepStatus.DONE, null, now = 300L)
        assertEquals(a.id, store.list().first().id)
    }

    @Test
    fun boundsCapStoredObjectives() {
        val store = ObjectiveStore(tempDir())
        repeat(ObjectiveRules.MAX_OBJECTIVES + 12) { index ->
            store.create("Цель $index", "goal", emptyList(), null, now = index.toLong())
        }
        assertEquals(ObjectiveRules.MAX_OBJECTIVES, store.count())
    }

    @Test
    fun boundsNeverEvictActiveObjective() {
        val dir = tempDir()
        val store = ObjectiveStore(dir)
        val important = store.create("Важная", "goal", emptyList(), null, now = 1L)!!
        // Fill with newer terminal objectives; the old ACTIVE one must still survive.
        repeat(ObjectiveRules.MAX_OBJECTIVES + 5) { index ->
            val o = store.create("Цель $index", "goal", emptyList(), null, now = 1_000L + index)!!
            store.setStatus(o.id, ObjectiveStatus.DONE, now = 1_000L + index)
        }
        assertEquals(ObjectiveRules.MAX_OBJECTIVES, store.count())
        assertNotNull("an ACTIVE objective must never be evicted", store.get(important.id))
        assertEquals(ObjectiveStatus.ACTIVE, store.get(important.id)!!.status)
    }

    @Test
    fun stepTransitionsPendingProgressDoneAndBlocked() {
        val store = ObjectiveStore(tempDir())
        val o = store.create("План", "goal", listOf("один", "два", "три"), 5L)!!

        val inProgress = store.updateStep(o.id, "s1", ObjectiveStepStatus.IN_PROGRESS, "начал")
        assertEquals(ObjectiveOutcome.CHANGED, inProgress.outcome)
        assertEquals(ObjectiveStepStatus.IN_PROGRESS, inProgress.step!!.status)
        assertEquals("начал", inProgress.step.note)

        val done = store.updateStep(o.id, "s1", ObjectiveStepStatus.DONE, "готово")
        assertEquals(ObjectiveOutcome.CHANGED, done.outcome)
        assertEquals(1, ObjectiveRules.progress(done.objective!!).done)

        val blocked = store.updateStep(o.id, "2", ObjectiveStepStatus.BLOCKED, "нет доступа")
        assertEquals(ObjectiveOutcome.CHANGED, blocked.outcome)
        assertEquals(ObjectiveStepStatus.BLOCKED, blocked.step!!.status)

        // Same status + same note is a no-op, not a fake change.
        val again = store.updateStep(o.id, "2", ObjectiveStepStatus.BLOCKED, "нет доступа")
        assertEquals(ObjectiveOutcome.NOOP_SAME, again.outcome)

        // The next actionable step is the remaining PENDING one, skipping the BLOCKED step.
        val objective = store.get(o.id)!!
        assertEquals("s3", ObjectiveRules.nextStep(objective)?.id)
    }

    @Test
    fun updateStepRejectsUnknownStepAndTerminalObjective() {
        val store = ObjectiveStore(tempDir())
        val o = store.create("План", "goal", listOf("один"), 5L)!!
        assertEquals(ObjectiveOutcome.NOT_FOUND, store.updateStep(o.id, "s9", ObjectiveStepStatus.DONE, null).outcome)
        store.setStatus(o.id, ObjectiveStatus.DONE)
        assertEquals(
            ObjectiveOutcome.NOOP_TERMINAL,
            store.updateStep(o.id, "s1", ObjectiveStepStatus.DONE, null).outcome
        )
    }

    @Test
    fun setStatusIsHonestAboutTerminalAndSameState() {
        val store = ObjectiveStore(tempDir())
        val o = store.create("Цель", "goal", emptyList(), null)!!
        assertEquals(ObjectiveOutcome.CHANGED, store.setStatus(o.id, ObjectiveStatus.PAUSED).outcome)
        assertEquals(ObjectiveStatus.PAUSED, store.get(o.id)!!.status)
        assertEquals(ObjectiveOutcome.NOOP_SAME, store.setStatus(o.id, ObjectiveStatus.PAUSED).outcome)
        assertEquals(ObjectiveOutcome.CHANGED, store.setStatus(o.id, ObjectiveStatus.DONE).outcome)
        assertEquals(ObjectiveOutcome.NOOP_TERMINAL, store.setStatus(o.id, ObjectiveStatus.CANCELLED).outcome)
        assertEquals(ObjectiveStatus.DONE, store.get(o.id)!!.status)
        assertEquals("unknown id", ObjectiveOutcome.NOT_FOUND, store.setStatus("nope", ObjectiveStatus.DONE).outcome)
    }

    @Test
    fun resumeMovesPausedBackToActiveButNotTerminal() {
        val store = ObjectiveStore(tempDir())
        val o = store.create("Цель", "goal", emptyList(), 9L)!!
        store.setStatus(o.id, ObjectiveStatus.PAUSED)
        assertEquals(ObjectiveOutcome.CHANGED, store.resume(o.id).outcome)
        assertEquals(ObjectiveStatus.ACTIVE, store.get(o.id)!!.status)
        assertEquals(ObjectiveOutcome.NOOP_SAME, store.resume(o.id).outcome)

        store.setStatus(o.id, ObjectiveStatus.CANCELLED)
        assertEquals(ObjectiveOutcome.NOOP_TERMINAL, store.resume(o.id).outcome)
        assertEquals(ObjectiveOutcome.NOT_FOUND, store.resume("nope").outcome)
    }

    @Test
    fun touchBumpsIterationsAndTimestamp() {
        val store = ObjectiveStore(tempDir())
        val o = store.create("Цель", "goal", emptyList(), null, now = 10L)!!
        val touched = store.touch(o.id, now = 50L)!!
        assertEquals(1, touched.iterations)
        assertEquals(50L, touched.lastWorkedAt)
        assertEquals(100L, store.touch(o.id, now = 100L)!!.lastWorkedAt)
        assertNull(store.touch("nope"))
    }

    @Test
    fun activeForChatAndRenderBlockPresentOnlyForActiveChatObjective() {
        val store = ObjectiveStore(tempDir())
        store.create("Другая", "goal", listOf("шаг"), 2L)!!
        assertEquals("", store.renderBlock(2L + 1))

        val target = store.create("Моя цель", "Сделать всё", listOf("первый", "второй"), 7L)!!
        val block = store.renderBlock(7L)
        assertTrue(block.contains("Моя цель"))
        assertTrue(block.contains("Прогресс: 0/2"))
        assertTrue(block.contains("[s1]"))
        assertEquals(target.id, store.activeForChat(7L)?.id)

        // Pausing removes it from the prompt entirely — no silent auto-continuation.
        store.setStatus(target.id, ObjectiveStatus.PAUSED)
        assertEquals("", store.renderBlock(7L))
        assertNull(store.activeForChat(7L))
    }

    @Test
    fun deleteAndClear() {
        val store = ObjectiveStore(tempDir())
        val o = store.create("Цель", "goal", emptyList(), null)!!
        assertTrue(store.delete(o.id))
        assertFalse(store.delete(o.id))
        store.create("A", "goal", emptyList(), null)
        store.create("B", "goal", emptyList(), null)
        assertTrue(store.clear())
        assertFalse(store.clear())
        assertEquals(0, store.count())
    }

    @Test
    fun corruptFileDegradesToEmptyInsteadOfThrowing() {
        val dir = tempDir()
        File(dir, ObjectiveStore.FILE_NAME).writeText("{ this is not valid json ]")
        val store = ObjectiveStore(dir)
        assertEquals(0, store.count())
    }

    @Test
    fun loadsObjectiveBoundToAMissingChatWithoutCrashing() {
        val dir = tempDir()
        val store = ObjectiveStore(dir)
        val o = store.create("Цель", "goal", listOf("шаг"), chatId = 9_999L)!!
        // A second store instance over the same directory == a process/app restart. Loading must not
        // touch or validate the chat reference (the FK lives in Room, not in the JSON file), so an
        // objective whose chat was deleted still loads instead of taking the whole store down.
        val reopened = ObjectiveStore(dir)
        val loaded = reopened.get(o.id)
        assertNotNull(loaded)
        assertEquals(9_999L, loaded!!.chatId)
        assertEquals(1, reopened.count())
    }
}
