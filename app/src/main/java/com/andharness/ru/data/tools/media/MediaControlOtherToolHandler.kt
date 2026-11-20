package com.andharness.ru.data.tools.media

import android.media.session.PlaybackState
import android.util.Log
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `media_control_other`: drives another app's player through its active media session.
 *
 * Reference: `MediaController` / `TransportControls` —
 * https://developer.android.com/reference/android/media/session/MediaController
 */
internal class MediaControlOtherToolHandler(
    private val sessions: MediaSessionSupport,
) : ToolHandler {

    override val name = "media_control_other"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val action = (arguments["action"] as? JsonPrimitive)?.content?.trim()?.lowercase().orEmpty()
            if (action !in CONTROL_ACTIONS) {
                return@withContext ToolResult(
                    false,
                    "Недопустимый action «$action». Разрешены: ${CONTROL_ACTIONS.joinToString(", ")}.",
                )
            }
            val app = (arguments["app"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
            val active = sessions.activeMediaSessions()
                ?: return@withContext ToolResult(false, MediaSessionSupport.NOTIFICATION_ACCESS_HINT)
            val controller = sessions.pickSession(active, app)
                ?: return@withContext ToolResult(
                    false,
                    if (app != null) {
                        "Активная медиа-сессия приложения «$app» не найдена."
                    } else {
                        "Активных медиа-сессий нет — управлять нечем."
                    },
                )

            val controls = controller.transportControls
            val before = sessions.playbackStateToString(controller.playbackState?.state)
            try {
                when (action) {
                    "play" -> controls.play()
                    "pause" -> controls.pause()
                    "next" -> controls.skipToNext()
                    "previous" -> controls.skipToPrevious()
                    "stop" -> controls.stop()
                    "toggle" -> if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                        controls.pause()
                    } else {
                        controls.play()
                    }
                }
            } catch (e: Exception) {
                Log.e(AudioLog.TAG, "media_control_other action failed", e)
                return@withContext ToolResult(
                    false,
                    "Медиа-сессия «${controller.packageName}» отклонила команду $action: ${e.message}",
                )
            }

            // The state change is asynchronous: give the player a bounded moment, then report
            // what actually happened instead of assuming the command took effect.
            delay(ACTION_SETTLE_MS)
            val after = sessions.playbackStateToString(controller.playbackState?.state)
            ToolResult(
                true,
                "Команда $action отправлена в «${controller.packageName}»: " +
                    "состояние было «$before», стало «$after».",
            )
        } catch (e: SecurityException) {
            ToolResult(false, MediaSessionSupport.NOTIFICATION_ACCESS_HINT)
        } catch (e: Exception) {
            Log.e(AudioLog.TAG, "media_control_other failed", e)
            ToolResult(false, "Не удалось управлять медиаплеером: ${e.message}")
        }
    }

    private companion object {
        /** `media_control_other`: bounded wait before reading the new playback state. */
        private const val ACTION_SETTLE_MS = 600L

        private val CONTROL_ACTIONS = listOf("play", "pause", "next", "previous", "stop", "toggle")
    }
}
