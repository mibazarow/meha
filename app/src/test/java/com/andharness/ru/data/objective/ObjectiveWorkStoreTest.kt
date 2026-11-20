package com.andharness.ru.data.objective

import com.andharness.ru.domain.objective.ObjectiveConcurrencyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Persistence + 1..3 clamping of the background objective worker config. */
class ObjectiveWorkStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `defaults to the policy default concurrency`() {
        val store = ObjectiveWorkStore(folder.root)
        assertEquals(ObjectiveConcurrencyPolicy.DEFAULT, store.maxConcurrency())
        assertEquals(0L, store.load().lastTickAt)
        assertNull(store.load().lastError)
    }

    @Test
    fun `setMaxConcurrency clamps and persists across instances`() {
        val store = ObjectiveWorkStore(folder.root)
        assertEquals(ObjectiveConcurrencyPolicy.MIN, store.setMaxConcurrency(0))
        assertEquals(ObjectiveConcurrencyPolicy.MAX, store.setMaxConcurrency(9))
        assertEquals(3, store.setMaxConcurrency(3))

        val reopened = ObjectiveWorkStore(folder.root)
        assertEquals(3, reopened.maxConcurrency())
    }

    @Test
    fun `recordTick persists the tick time and an honest error note`() {
        val store = ObjectiveWorkStore(folder.root)
        store.recordTick(1234L, "цель o1: ошибка")
        val reloaded = ObjectiveWorkStore(folder.root).load()
        assertEquals(1234L, reloaded.lastTickAt)
        assertEquals("цель o1: ошибка", reloaded.lastError)

        store.recordTick(5678L, null)
        assertNull(ObjectiveWorkStore(folder.root).load().lastError)
        assertEquals(5678L, ObjectiveWorkStore(folder.root).load().lastTickAt)
    }

    @Test
    fun `a corrupt file degrades to defaults instead of throwing`() {
        val dir = folder.root
        assertTrue(dir.exists())
        java.io.File(dir, ObjectiveWorkStore.FILE_NAME).writeText("{ not json")
        val store = ObjectiveWorkStore(dir)
        assertEquals(ObjectiveConcurrencyPolicy.DEFAULT, store.maxConcurrency())
    }
}
