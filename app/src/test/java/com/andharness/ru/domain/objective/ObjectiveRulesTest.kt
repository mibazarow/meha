package com.andharness.ru.domain.objective

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure rules of the objective feature (Android-free, JVM): progress, next-step selection, the
 * active-for-chat resume decision, bounds and the compact prompt block.
 */
class ObjectiveRulesTest {

    private fun objective(
        id: String = "o1",
        status: ObjectiveStatus = ObjectiveStatus.ACTIVE,
        chatId: Long? = 7L,
        updatedAt: Long = 100L,
        steps: List<ObjectiveStep> = emptyList()
    ) = Objective(
        id = id,
        title = "Цель $id",
        goal = "Получить результат",
        status = status,
        steps = steps,
        chatId = chatId,
        updatedAt = updatedAt
    )

    private fun step(id: String, status: ObjectiveStepStatus, text: String = "шаг $id") =
        ObjectiveStep(id = id, text = text, status = status)

    @Test
    fun progressCountsDoneStepsOnly() {
        val o = objective(
            steps = listOf(
                step("s1", ObjectiveStepStatus.DONE),
                step("s2", ObjectiveStepStatus.IN_PROGRESS),
                step("s3", ObjectiveStepStatus.PENDING),
                step("s4", ObjectiveStepStatus.BLOCKED)
            )
        )
        assertEquals(ObjectiveProgress(done = 1, total = 4), ObjectiveRules.progress(o))
    }

    @Test
    fun nextStepPrefersInProgressThenPending() {
        val o = objective(
            steps = listOf(
                step("s1", ObjectiveStepStatus.DONE),
                step("s2", ObjectiveStepStatus.PENDING),
                step("s3", ObjectiveStepStatus.IN_PROGRESS)
            )
        )
        assertEquals("s3", ObjectiveRules.nextStep(o)?.id)
        val noInProgress = o.copy(
            steps = listOf(
                step("s1", ObjectiveStepStatus.DONE),
                step("s2", ObjectiveStepStatus.PENDING)
            )
        )
        assertEquals("s2", ObjectiveRules.nextStep(noInProgress)?.id)
    }

    @Test
    fun nextStepIsNullWhenNothingActionable() {
        val o = objective(
            steps = listOf(
                step("s1", ObjectiveStepStatus.DONE),
                step("s2", ObjectiveStepStatus.BLOCKED)
            )
        )
        assertNull(ObjectiveRules.nextStep(o))
    }

    @Test
    fun activeForChatMatchesOnlyActiveObjectiveOfThatChat() {
        val list = listOf(
            objective("o1", status = ObjectiveStatus.ACTIVE, chatId = 1L, updatedAt = 10),
            objective("o2", status = ObjectiveStatus.PAUSED, chatId = 1L, updatedAt = 20),
            objective("o3", status = ObjectiveStatus.ACTIVE, chatId = 2L, updatedAt = 30)
        )
        assertEquals("o3", ObjectiveRules.activeForChat(list, 2L)?.id)
        assertEquals("o1", ObjectiveRules.activeForChat(list, 1L)?.id)
        assertNull(ObjectiveRules.activeForChat(list, 99L))
        assertNull("unbound chat must inject nothing", ObjectiveRules.activeForChat(list, null))
    }

    @Test
    fun boundKeepsActiveObjectivesAndCaps() {
        val actives = (1..ObjectiveRules.MAX_OBJECTIVES).map {
            objective("a$it", status = ObjectiveStatus.ACTIVE, updatedAt = it.toLong())
        }
        val terminals = (1..30).map {
            objective("t$it", status = ObjectiveStatus.DONE, updatedAt = 1_000L + it)
        }
        val bounded = ObjectiveRules.bound(actives + terminals)
        assertEquals(ObjectiveRules.MAX_OBJECTIVES, bounded.size)
        assertTrue("all active objectives must survive", bounded.all { it.status == ObjectiveStatus.ACTIVE })
        // Newest-updated first.
        assertEquals(bounded.map { it.updatedAt }.sortedDescending(), bounded.map { it.updatedAt })
    }

    @Test
    fun boundDropsStalestWhenOverCap() {
        val list = (1..ObjectiveRules.MAX_OBJECTIVES + 10).map {
            objective("o$it", status = ObjectiveStatus.DONE, updatedAt = it.toLong())
        }
        val bounded = ObjectiveRules.bound(list)
        assertEquals(ObjectiveRules.MAX_OBJECTIVES, bounded.size)
        assertFalse("the stalest must be dropped", bounded.any { it.id == "o1" })
        assertTrue("the newest must survive", bounded.any { it.id == "o${ObjectiveRules.MAX_OBJECTIVES + 10}" })
    }

    @Test
    fun renderBlockIsAbsentForNullPausedOrTerminal() {
        assertEquals("", ObjectiveRules.renderBlock(null))
        assertEquals("", ObjectiveRules.renderBlock(objective(status = ObjectiveStatus.PAUSED)))
        assertEquals("", ObjectiveRules.renderBlock(objective(status = ObjectiveStatus.DONE)))
        assertEquals("", ObjectiveRules.renderBlock(objective(status = ObjectiveStatus.CANCELLED)))
    }

    @Test
    fun renderBlockRendersTitleGoalProgressAndNextStep() {
        val o = objective(
            status = ObjectiveStatus.ACTIVE,
            steps = listOf(
                step("s1", ObjectiveStepStatus.DONE),
                step("s2", ObjectiveStepStatus.PENDING, text = "Сделать отчёт")
            )
        )
        val block = ObjectiveRules.renderBlock(o)
        assertTrue(block.startsWith(ObjectiveRules.HEADER))
        assertTrue(block.contains("Цель"))
        assertTrue(block.contains("o1"))
        assertTrue(block.contains("Прогресс: 1/2"))
        assertTrue(block.contains("[s2]"))
        assertTrue(block.contains("Сделать отчёт"))
        assertTrue("must tell the model how to checkpoint", block.contains("objective_step_update"))
        assertTrue("must tell the model how to fetch the next step", block.contains("objective_next"))
    }

    @Test
    fun renderBlockTellsHowToFinishWhenAllStepsAreDone() {
        val o = objective(
            status = ObjectiveStatus.ACTIVE,
            steps = listOf(
                step("s1", ObjectiveStepStatus.DONE),
                step("s2", ObjectiveStepStatus.DONE)
            )
        )
        val block = ObjectiveRules.renderBlock(o)
        assertTrue(block.contains("Все шаги выполнены"))
        assertTrue(block.contains("objective_finish"))
    }

    @Test
    fun renderBlockIsBounded() {
        val o = objective().copy(goal = "я".repeat(5_000))
        val block = ObjectiveRules.renderBlock(o, limit = 300)
        assertTrue(block.length <= 300)
    }
}
