package com.andharness.ru.domain.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the actionable error-hint layer: one representative failure per rule, plus the
 * "nothing useful applies" cases that must stay empty (never a generic filler).
 */
class ToolErrorHintsTest {

    @Test
    fun permissionDeniedGivesPermissionHintNamingCapability() {
        val hint = ToolErrorHints.hint("files_read", "SecurityException: permission denied")
        assertTrue(hint.contains("Настройки"))
        assertTrue(hint.contains("доступ к файлам"))
    }

    @Test
    fun allFilesAccessGivesAllFilesHint() {
        val hint = ToolErrorHints.hint("files_list", "нужен MANAGE_EXTERNAL_STORAGE")
        assertTrue(hint.contains("Доступ ко всем файлам"))
        // The hint must point at the in-app grant path, not only the system settings screen.
        assertTrue(hint.contains("Все разрешения"))
        assertTrue(hint.contains("Особые доступы"))
    }

    @Test
    fun archiveToolsAllFilesFailureGivesAllFilesHint() {
        // The archive tools declare MANAGE_EXTERNAL_STORAGE; a handler-level all-files failure
        // (e.g. raised after the grant was revoked) must still get the actionable grant hint.
        val hint = ToolErrorHints.hint(
            "files_zip",
            "Нужен доступ ко всем файлам (MANAGE_EXTERNAL_STORAGE)."
        )
        assertTrue(hint.contains("Все разрешения"))
        assertTrue(hint.contains("Особые доступы"))
    }

    @Test
    fun accessibilityDisabledGivesAccessibilityHint() {
        val hint = ToolErrorHints.hint("ui_dump", "служба спецвозможностей не включена")
        assertTrue(hint.contains("Доступность"))
    }

    @Test
    fun notificationListenerDisabledGivesNotificationHint() {
        val hint = ToolErrorHints.hint("notification_list", "Notification Listener not enabled")
        assertTrue(hint.contains("Доступ к уведомлениям"))
    }

    @Test
    fun appMissingGivesInstallHint() {
        val hint = ToolErrorHints.hint("app_open", "ActivityNotFoundException: нет приложения")
        assertTrue(hint.contains("app_store_open"))
    }

    @Test
    fun batteryDeferralGivesBatteryHint() {
        val hint = ToolErrorHints.hint("schedule_run", "deferred by PowerGuard")
        assertTrue(hint.contains("PowerGuard"))
    }

    @Test
    fun networkFailureGivesInternetHint() {
        val hint = ToolErrorHints.hint("web_fetch", "UnknownHostException: Unable to resolve host")
        assertTrue(hint.contains("интернет"))
    }

    @Test
    fun exactAlarmNotPermittedGivesAlarmHint() {
        val hint = ToolErrorHints.hint("alarm_exact_set", "разрешение SCHEDULE_EXACT_ALARM не выдано")
        assertTrue(hint.contains("Будильники"))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertTrue(ToolErrorHints.hint("web", "PERMISSION DENIED").isNotEmpty())
        assertTrue(ToolErrorHints.hint("web", "TIMEOUT while reading").isNotEmpty())
    }

    @Test
    fun emptyErrorGivesNoHint() {
        assertEquals("", ToolErrorHints.hint("files_read", ""))
        assertEquals("", ToolErrorHints.hint("files_read", "   "))
    }

    @Test
    fun unknownErrorGivesNoGenericFiller() {
        assertEquals("", ToolErrorHints.hint("device_info", "файл не найден"))
        assertEquals("", ToolErrorHints.hint("device_info", "Некорректный аргумент duration_ms"))
    }

    @Test
    fun failedBenignToolGetsAnAlternativesLine() {
        // Even without a recognised failure signature, a failed readable tool must point the agent
        // at other tools so it changes approach instead of repeating the same call.
        val hint = ToolErrorHints.hint("files_read", "файл не найден")
        assertTrue(hint.contains("Попробуй также:"))
        assertTrue(hint.contains("download_file"))
    }

    @Test
    fun irreversibleToolNeverGetsAnAlternativesLine() {
        // files_delete is destructive; it must never be "recovered" by suggesting another tool, and
        // the generic error below matches no signature, so the whole hint stays empty.
        assertEquals("", ToolErrorHints.hint("files_delete", "непредвиденная ошибка"))
    }

    @Test
    fun intentionalPrivilegedRefusalNeverGetsAGrantHint() {
        // `sim_set_default_sms` always refuses because MODIFY_PHONE_STATE cannot be granted; the
        // message names the privilege but must not be followed by a "grant it in Settings" hint.
        val refusal = "требуется системная привилегия android.permission.MODIFY_PHONE_STATE"
        assertEquals("", ToolErrorHints.hint("sim_set_default_sms", refusal))
    }
}
