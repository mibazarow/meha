package com.andharness.ru.data.undo

import com.andharness.ru.domain.undo.UndoPayload
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure inverses in [UndoActions]. Only the lambda-based helpers
 * ([UndoActions.settingsRestore] and [UndoActions.deleteById]) are exercised here; the
 * `ContentResolver`-backed [UndoActions.deleteByUri] and the production `android(...)` table need a
 * real Android runtime and are wired in `ToolBindingsModule`.
 */
class UndoActionsTest {

    private class FakeSettings(var value: String? = null) {
        fun get(): String? = value
        fun put(newValue: String?): Boolean {
            value = newValue
            return true
        }
    }

    @Test
    fun `settings restore writes the previous value back`() = runTest {
        val settings = FakeSettings(value = "новое")
        val action = UndoActions.settingsRestore(
            get = { settings.get() },
            put = { _, value -> settings.put(value) },
        )

        val outcome = action.undo(
            mapOf(UndoPayload.KEY to "screen_brightness", UndoPayload.PREVIOUS_VALUE to "старое"),
        )

        assertTrue(outcome.success)
        assertEquals("старое", settings.value)
    }

    @Test
    fun `settings restore with no previous value deletes the key`() = runTest {
        val settings = FakeSettings(value = "set-by-tool")
        val action = UndoActions.settingsRestore(
            get = { settings.get() },
            put = { _, value -> settings.put(value) },
        )

        val outcome = action.undo(mapOf(UndoPayload.KEY to "some_key"))

        assertTrue(outcome.success)
        assertNull(settings.value)
    }

    @Test
    fun `settings restore without a key is an honest failure`() = runTest {
        val settings = FakeSettings(value = "x")
        val action = UndoActions.settingsRestore(
            get = { settings.get() },
            put = { _, value -> settings.put(value) },
        )

        val outcome = action.undo(emptyMap())

        assertFalse(outcome.success)
        assertEquals("x", settings.value)
    }

    @Test
    fun `settings restore reports when the write is rejected`() = runTest {
        val action = UndoActions.settingsRestore(get = { null }, put = { _, _ -> false })

        val outcome = action.undo(
            mapOf(UndoPayload.KEY to "k", UndoPayload.PREVIOUS_VALUE to "v"),
        )

        assertFalse(outcome.success)
    }

    @Test
    fun `settings restore reports when the read-back does not match`() = runTest {
        // The write "succeeds" but the system keeps overriding the value: honest failure.
        val action = UndoActions.settingsRestore(get = { "system-value" }, put = { _, _ -> true })

        val outcome = action.undo(
            mapOf(UndoPayload.KEY to "k", UndoPayload.PREVIOUS_VALUE to "v"),
        )

        assertFalse(outcome.success)
        assertTrue(outcome.message.contains("system-value"))
    }

    @Test
    fun `delete by id reports success and failure honestly`() = runTest {
        val action = UndoActions.deleteById("Заметка") { it == 5L }

        val ok = action.undo(mapOf(UndoPayload.ID to "5"))
        assertTrue(ok.success)

        val missing = action.undo(mapOf(UndoPayload.ID to "6"))
        assertFalse(missing.success)

        val noId = action.undo(emptyMap())
        assertFalse(noId.success)
    }
}
