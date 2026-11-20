package com.andharness.ru.data.notifications

import com.andharness.ru.domain.notifications.NotificationRules
import com.andharness.ru.domain.notifications.NotificationRulesCodec
import com.andharness.ru.domain.notifications.QuietHours
import com.andharness.ru.domain.notifications.RuleImportance
import com.andharness.ru.domain.notifications.SummaryMode
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the file-backed [NotificationRulesStore]. It is Android-free by construction (it only
 * takes a directory), so real file CRUD, persistence across instances, the app-rule cap and
 * corrupt-file degradation are exercised on a temp directory — no Android runtime involved.
 */
class NotificationRulesStoreTest {

    private val dirs = mutableListOf<File>()

    private fun newDir(): File =
        Files.createTempDirectory("notification-rules-test").toFile().also { dirs += it }

    private fun store(dir: File = newDir()) = NotificationRulesStore(dir)

    @After
    fun cleanup() {
        dirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun defaultsAreAStrictNoOp() {
        val store = store()
        assertEquals(NotificationRules(), store.current())
        assertTrue(store.current().apps.isEmpty())
        assertNull(store.current().quietHours)
        assertTrue(store.current().quietAllowImportant)
        assertEquals(SummaryMode.ALL, store.current().summaryMode)
    }

    @Test
    fun appRuleCrud() {
        val store = store()
        assertNull(store.setAppRule("  ", RuleImportance.MUTE))

        val saved = store.setAppRule("org.telegram.messenger", RuleImportance.IMPORTANT, "от шефа")
        assertNotNull(saved)
        assertEquals(1, store.appRuleCount())
        assertEquals(RuleImportance.IMPORTANT, store.getAppRule("org.telegram.messenger")?.importance)
        assertEquals("от шефа", store.getAppRule("ORG.TELEGRAM.MESSENGER")?.note)

        store.setAppRule("org.telegram.messenger", RuleImportance.MUTE)
        assertEquals(1, store.appRuleCount())
        assertEquals(RuleImportance.MUTE, store.getAppRule("org.telegram.messenger")?.importance)

        assertTrue(store.removeAppRule("org.telegram.messenger"))
        assertFalse(store.removeAppRule("org.telegram.messenger"))
        assertEquals(0, store.appRuleCount())
    }

    @Test
    fun removeAllAppRulesKeepsGlobalSettings() {
        val store = store()
        store.setAppRule("a.app", RuleImportance.MUTE)
        store.setAppRule("b.app", RuleImportance.IMPORTANT)
        store.setQuietHours(QuietHours.of(23, 0, 7, 0))
        store.setSummaryMode(SummaryMode.IMPORTANT_ONLY)

        assertTrue(store.removeAppRule(null))
        assertEquals(0, store.appRuleCount())
        assertNotNull(store.current().quietHours)
        assertEquals(SummaryMode.IMPORTANT_ONLY, store.current().summaryMode)
        assertFalse(store.removeAppRule(null))
    }

    @Test
    fun quietHoursAndSummaryMode() {
        val store = store()
        store.setQuietHours(22, 30, 7, 15)
        assertEquals(QuietHours.of(22, 30, 7, 15), store.current().quietHours)
        store.setQuietAllowImportant(false)
        assertFalse(store.current().quietAllowImportant)
        store.setSummaryMode(SummaryMode.OFF)
        assertEquals(SummaryMode.OFF, store.current().summaryMode)
        assertTrue(store.clearQuietHours())
        assertNull(store.current().quietHours)
        assertFalse(store.clearQuietHours())
    }

    @Test
    fun statePersistsAcrossInstances() {
        val dir = newDir()
        val first = store(dir)
        first.setAppRule("ru.bank", RuleImportance.MUTE, "спам")
        first.setQuietHours(QuietHours.of(23, 0, 7, 0))
        first.setQuietAllowImportant(false)
        first.setSummaryMode(SummaryMode.IMPORTANT_ONLY)

        val second = store(dir)
        assertEquals(1, second.appRuleCount())
        assertEquals(RuleImportance.MUTE, second.getAppRule("ru.bank")?.importance)
        assertEquals("спам", second.getAppRule("ru.bank")?.note)
        assertEquals(QuietHours.of(23, 0, 7, 0), second.current().quietHours)
        assertFalse(second.current().quietAllowImportant)
        assertEquals(SummaryMode.IMPORTANT_ONLY, second.current().summaryMode)
    }

    @Test
    fun corruptFileDegradesToDefaultsAndStillWrites() {
        val dir = newDir()
        File(dir, NotificationRulesStore.FILE_NAME).writeText("{ not json")
        val store = store(dir)
        assertEquals(NotificationRules(), store.current())

        store.setAppRule("ok.app", RuleImportance.IMPORTANT)
        assertEquals(RuleImportance.IMPORTANT, store(dir).getAppRule("ok.app")?.importance)
    }

    @Test
    fun appRuleCapIsEnforced() {
        val store = store()
        repeat(NotificationRulesCodec.MAX_APPS + 20) { i ->
            store.setAppRule("app.$i", RuleImportance.NORMAL)
        }
        assertEquals(NotificationRulesCodec.MAX_APPS, store.appRuleCount())
        assertNull(store.getAppRule("app.0"))
        assertNotNull(store.getAppRule("app.${NotificationRulesCodec.MAX_APPS + 19}"))
    }

    @Test
    fun stateFlowReflectsMutations() {
        val store = store()
        store.setSummaryMode(SummaryMode.OFF)
        assertEquals(SummaryMode.OFF, store.rules.value.summaryMode)
    }
}
