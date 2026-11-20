package com.andharness.ru.bg

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import com.andharness.ru.R
import com.andharness.ru.data.notifications.NotificationHelper

/**
 * Foreground-service notifications of [ScreenRecordService]: the recording banner and the
 * single-frame capture banner, both on [NotificationHelper.CHANNEL_TASKS].
 *
 * Stateless builder helpers only — the service owns the foreground lifecycle.
 */
internal object ScreenRecordNotifications {

    /** Ongoing "recording the screen (and sound)" banner. */
    fun recording(context: Context, withAudio: Boolean): Notification =
        NotificationCompat.Builder(context, NotificationHelper.CHANNEL_TASKS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Меха")
            .setContentText(if (withAudio) "Записываю экран и звук…" else "Записываю экран…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    /** Ongoing "capturing one frame" banner. */
    fun capture(context: Context): Notification =
        NotificationCompat.Builder(context, NotificationHelper.CHANNEL_TASKS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Меха")
            .setContentText("Снимаю кадр экрана…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
}
