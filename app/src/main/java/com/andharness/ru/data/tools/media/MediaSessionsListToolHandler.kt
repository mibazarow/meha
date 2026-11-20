package com.andharness.ru.data.tools.media

import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `media_sessions_list`: lists active media sessions with their state and metadata.
 *
 * Reference: `MediaSessionManager.getActiveSessions()` —
 * https://developer.android.com/reference/android/media/session/MediaSessionManager
 */
internal class MediaSessionsListToolHandler(
    private val sessions: MediaSessionSupport,
) : ToolHandler {

    override val name = "media_sessions_list"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val app = (arguments["app"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
            val active = sessions.activeMediaSessions()
                ?: return@withContext ToolResult(false, MediaSessionSupport.NOTIFICATION_ACCESS_HINT)
            val filtered = if (app == null) {
                active
            } else {
                active.filter {
                    it.packageName.equals(app, true) || it.packageName.contains(app, true)
                }
            }
            if (filtered.isEmpty()) {
                return@withContext ToolResult(
                    true,
                    if (app == null) "Активных медиа-сессий нет." else "Сессии приложения «$app» не найдены.",
                )
            }
            val lines = filtered.map { controller ->
                // `MediaMetadata.description` is null-safe; `getString` would throw on
                // non-string fields, so the description is used instead.
                val description = controller.metadata?.description
                val title = description?.title?.toString().orEmpty()
                val artist = description?.subtitle?.toString().orEmpty()
                val album = description?.description?.toString().orEmpty()
                buildString {
                    append(controller.packageName)
                    append(" | состояние: ")
                    append(sessions.playbackStateToString(controller.playbackState?.state))
                    if (title.isNotEmpty()) append(" | «$title»")
                    if (artist.isNotEmpty()) append(" | $artist")
                    if (album.isNotEmpty()) append(" | альбом: $album")
                }
            }
            ToolResult(true, lines.joinToString("\n"))
        } catch (e: SecurityException) {
            ToolResult(false, MediaSessionSupport.NOTIFICATION_ACCESS_HINT)
        } catch (e: Exception) {
            Log.e(AudioLog.TAG, "media_sessions_list failed", e)
            ToolResult(false, "Не удалось получить медиа-сессии: ${e.message}")
        }
    }
}
