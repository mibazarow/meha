package com.andharness.ru.data.tools.media

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import com.andharness.ru.service.NotificationRelayService

/**
 * Access to other apps' active media sessions, shared by `media_control_other` and
 * `media_sessions_list`.
 *
 * Reference: `MediaSessionManager.getActiveSessions(ComponentName)` —
 * https://developer.android.com/reference/android/media/session/MediaSessionManager#getActiveSessions(android.content.ComponentName)
 */
internal class MediaSessionSupport(private val context: Context) {

    /**
     * Active sessions of every player, obtained for OUR notification listener component.
     * Returns `null` when notification access is not granted (the platform throws
     * `SecurityException`), so callers report the honest reason instead of an empty list.
     */
    fun activeMediaSessions(): List<MediaController>? {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return null
        val component = ComponentName(context, NotificationRelayService::class.java)
        return try {
            manager.getActiveSessions(component)
        } catch (e: SecurityException) {
            Log.e(AudioLog.TAG, "getActiveSessions denied", e)
            null
        } catch (e: Exception) {
            Log.e(AudioLog.TAG, "getActiveSessions failed", e)
            null
        }
    }

    fun pickSession(sessions: List<MediaController>, app: String?): MediaController? {
        val candidates = if (app == null) {
            sessions
        } else {
            sessions.filter { it.packageName.equals(app, true) || it.packageName.contains(app, true) }
        }
        return candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: candidates.firstOrNull()
    }

    fun playbackStateToString(state: Int?): String = when (state) {
        null -> "нет данных"
        PlaybackState.STATE_NONE -> "нет"
        PlaybackState.STATE_STOPPED -> "остановлен"
        PlaybackState.STATE_PAUSED -> "пауза"
        PlaybackState.STATE_PLAYING -> "играет"
        PlaybackState.STATE_FAST_FORWARDING -> "перемотка вперёд"
        PlaybackState.STATE_REWINDING -> "перемотка назад"
        PlaybackState.STATE_BUFFERING -> "буферизация"
        PlaybackState.STATE_ERROR -> "ошибка"
        PlaybackState.STATE_CONNECTING -> "подключение"
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "переход к предыдущему"
        PlaybackState.STATE_SKIPPING_TO_NEXT -> "переход к следующему"
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "переход к элементу очереди"
        else -> "неизвестно ($state)"
    }

    companion object {
        /** Honest reason when the listener component has no notification access. */
        const val NOTIFICATION_ACCESS_HINT =
            "Нужен доступ к уведомлениям: включите Меха в «Настройки → Уведомления → Доступ к уведомлениям»."
    }
}
