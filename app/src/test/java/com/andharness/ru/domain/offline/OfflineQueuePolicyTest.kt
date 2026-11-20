package com.andharness.ru.domain.offline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure [OfflineQueuePolicy] decision (no Android runtime involved): the queue may
 * only accept genuinely network-bound tools that failed for a clear network reason, and must NEVER
 * accept irreversible local actions (SMS, calls, deletes, files, contacts, calendar).
 */
class OfflineQueuePolicyTest {

    private val dns = "Ошибка: Unable to resolve host \"example.com\": No address associated with hostname"
    private val timeout = "java.net.SocketTimeoutException: timeout"
    private val refused = "java.net.ConnectException: Failed to connect to example.com/1.2.3.4:443"

    @Test
    fun queuesNetworkBoundToolsOnNetworkError() {
        for (tool in listOf(
            "http_request", "web_fetch", "web_search", "download_file",
            "ssh_exec", "ssh_sftp_get", "ssh_sftp_put", "ssh_sftp_list",
            "repo_status", "repo_diff", "repo_log", "repo_run", "repo_apply_patch",
        )) {
            assertTrue("$tool should queue on DNS failure", OfflineQueuePolicy.shouldQueue(tool, dns))
            assertTrue("$tool should queue on timeout", OfflineQueuePolicy.shouldQueue(tool, timeout))
            assertTrue("$tool should queue on refused", OfflineQueuePolicy.shouldQueue(tool, refused))
        }
    }

    @Test
    fun rejectsIrreversibleLocalActionsEvenOnNetworkError() {
        for (tool in listOf(
            "sms_send", "call_make", "call_phone", "files_delete", "files_write",
            "contacts_delete", "contacts_create", "calendar_create", "calendar_delete",
            "photos_delete", "memory_delete", "app_uninstall", "notification_send",
        )) {
            assertFalse("$tool must NEVER be queued", OfflineQueuePolicy.shouldQueue(tool, dns))
            assertFalse("$tool must NEVER be queued", OfflineQueuePolicy.shouldQueue(tool, timeout))
        }
    }

    @Test
    fun rejectsNonNetworkToolsEvenWithNetworkError() {
        // Not network-bound, so no retry is ever armed — regardless of the error text.
        for (tool in listOf("device_info", "files_read", "app_list", "ui_tap", "memory_search")) {
            assertFalse(tool, OfflineQueuePolicy.shouldQueue(tool, dns))
        }
    }

    @Test
    fun rejectsLocalSshServerConfigTools() {
        // `ssh_server_*` only touch the on-device server store and never need the network.
        for (tool in listOf(
            "ssh_server_add", "ssh_server_list", "ssh_server_remove",
            "ssh_server_rename", "ssh_server_update",
        )) {
            assertFalse("$tool is local config", OfflineQueuePolicy.shouldQueue(tool, dns))
        }
    }

    @Test
    fun doesNotQueueLogicalFailures() {
        // A network-bound tool can still fail for a logical reason that retrying cannot fix.
        for (error in listOf(
            "Ошибка: HTTP 404",
            "Отсутствует обязательный аргумент: url",
            "Неверные аргументы: Expected start of the object '{'",
            "PERMISSION_REQUIRED:android.permission.INTERNET",
            "",
        )) {
            assertFalse(error, OfflineQueuePolicy.shouldQueue("http_request", error))
        }
    }

    @Test
    fun eligibilityIsAnExplicitAllowlist() {
        assertTrue(OfflineQueuePolicy.isEligibleTool("http_request"))
        assertTrue(OfflineQueuePolicy.isEligibleTool("repo_apply_patch"))
        assertFalse(OfflineQueuePolicy.isEligibleTool("repo_unknown"))
        assertFalse(OfflineQueuePolicy.isEligibleTool("ssh_server_add"))
    }

    @Test
    fun networkErrorDetectionIsCaseInsensitive() {
        assertTrue(OfflineQueuePolicy.isNetworkError("UNKNOWNHOST example.com"))
        assertTrue(OfflineQueuePolicy.isNetworkError("Нет подключения"))
        assertFalse(OfflineQueuePolicy.isNetworkError("HTTP 200 OK"))
    }
}
