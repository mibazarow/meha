package com.andharness.ru.data.tools.web

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import com.andharness.ru.domain.agent.ToolResult
import kotlinx.coroutines.delay

/**
 * Enqueues page downloads with the system [DownloadManager] into the public «Загрузки» folder and
 * reports their real outcome (never a fabricated path).
 *
 * Official docs: `DownloadManager.Request.setDestinationInExternalPublicDir` and
 * `URLUtil.guessFileName`:
 * https://developer.android.com/reference/android/app/DownloadManager.Request#setDestinationInExternalPublicDir(java.lang.String,%20java.lang.String)
 * https://developer.android.com/reference/android/webkit/URLUtil#guessFileName(java.lang.String,%20java.lang.String,%20java.lang.String)
 */
internal class WebDownloadManager(private val appContext: Context) {

    /**
     * Enqueues a download. Returns the download id, or null when the manager is unavailable / the
     * request is rejected.
     */
    fun enqueue(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ): Long? = try {
        val manager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        if (manager == null) {
            null
        } else {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                .ifBlank { "web-download-${System.currentTimeMillis()}" }
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("Загрузка из веб-сессии Меха")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            if (!mimeType.isNullOrBlank()) request.setMimeType(mimeType)
            if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)
            manager.enqueue(request)
        }
    } catch (e: Exception) {
        null
    }

    /** Waits (bounded) for a queued download to finish, then reports its real outcome. */
    suspend fun report(id: Long, url: String): ToolResult {
        val deadline = System.currentTimeMillis() + DOWNLOAD_WAIT_MS
        var status = -1
        while (System.currentTimeMillis() < deadline) {
            val info = query(id)
            if (info != null) {
                status = info.status
                if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                    break
                }
            }
            delay(DOWNLOAD_POLL_MS)
        }
        val info = query(id)
        return when (info?.status ?: status) {
            DownloadManager.STATUS_SUCCESSFUL -> ToolResult(
                true,
                buildString {
                    appendLine("Загрузка завершена: «$url».")
                    appendLine("Сохранено: «${info?.uri ?: "папка «Загрузки»"}».")
                }.trim(),
            )
            DownloadManager.STATUS_FAILED -> ToolResult(
                false,
                "Загрузка «$url» завершилась ошибкой (reason=${info?.reason ?: 0}, id=$id).",
            )
            else -> ToolResult(
                true,
                "Загрузка «$url» поставлена в системную очередь и продолжается в фоне (id=$id). " +
                    "Файл появится в папке «Загрузки» — точный путь пока неизвестен.",
            )
        }
    }

    private data class DownloadInfo(val status: Int, val uri: String?, val reason: Int)

    private fun query(id: Long): DownloadInfo? = try {
        val manager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        val cursor = manager?.query(DownloadManager.Query().setFilterById(id))
        cursor?.use { c ->
            if (c.moveToFirst()) {
                val statusIndex = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val uriIndex = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                val reasonIndex = c.getColumnIndex(DownloadManager.COLUMN_REASON)
                DownloadInfo(
                    status = if (statusIndex >= 0) c.getInt(statusIndex) else -1,
                    uri = if (uriIndex >= 0) c.getString(uriIndex) else null,
                    reason = if (reasonIndex >= 0) c.getInt(reasonIndex) else 0,
                )
            } else {
                null
            }
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        private const val DOWNLOAD_WAIT_MS = 12_000L
        private const val DOWNLOAD_POLL_MS = 500L
    }
}
