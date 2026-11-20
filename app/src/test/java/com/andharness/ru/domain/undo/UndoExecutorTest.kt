package com.andharness.ru.domain.undo

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure [UndoExecutor] dispatch. Fake [UndoAction]s stand in for the real inverses,
 * so every supported kind, the honest "unknown kind" path and exceptions are covered without Android.
 */
class UndoExecutorTest {

    @Test
    fun `dispatches each supported kind to its own action with the payload`() = runTest {
        val seen = mutableMapOf<String, Map<String, String>>()
        val executor = UndoExecutor(
            UndoKinds.SUPPORTED.associateWith { kind ->
                UndoAction { payload ->
                    seen[kind] = payload
                    UndoOutcome(true, "ok:$kind")
                }
            },
        )

        for (kind in UndoKinds.SUPPORTED) {
            val payload = mapOf(UndoPayload.ID to kind)
            assertTrue(executor.supports(kind))
            val outcome = executor.execute(kind, payload)
            assertTrue("$kind should succeed", outcome.success)
            assertEquals(payload, seen[kind])
        }
    }

    @Test
    fun `unknown kind is honestly reported as not undoable`() = runTest {
        val executor = UndoExecutor(emptyMap())
        assertFalse(executor.supports("sms_send"))
        val outcome = executor.execute("sms_send", emptyMap())
        assertFalse(outcome.success)
        assertTrue(outcome.message.contains("нельзя"))
    }

    @Test
    fun `a throwing action becomes an honest failure, never an exception`() = runTest {
        val executor = UndoExecutor(
            mapOf(UndoKinds.NOTE_DELETE to UndoAction { throw IllegalStateException("boom") }),
        )
        val outcome = executor.execute(UndoKinds.NOTE_DELETE, emptyMap())
        assertFalse(outcome.success)
        assertTrue(outcome.message.contains("boom"))
    }

    @Test
    fun `a failing action propagates its message`() = runTest {
        val executor = UndoExecutor(
            mapOf(UndoKinds.TODO_DELETE to UndoAction { UndoOutcome(false, "не найдено") }),
        )
        val outcome = executor.execute(UndoKinds.TODO_DELETE, emptyMap())
        assertFalse(outcome.success)
        assertEquals("не найдено", outcome.message)
    }

    @Test
    fun `supports is false for kinds with no registered action`() {
        val executor = UndoExecutor(mapOf(UndoKinds.NOTE_DELETE to UndoAction { UndoOutcome(true, "") }))
        assertTrue(executor.supports(UndoKinds.NOTE_DELETE))
        assertFalse(executor.supports(UndoKinds.CONTACT_DELETE))
    }
}
