package com.andharness.ru.service.overlay

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.andharness.ru.R
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.service.OverlayAssistantService

/**
 * Builds the foreground notification for the floating assistant and its "Закрыть" action's
 * [PendingIntent].
 *
 * The stop action targets the service with [OverlayAssistantService.ACTION_STOP]; the service's own
 * `onStartCommand` persists the flag OFF so the Settings toggle cannot stay ON while the bubble is
 * gone (the same honesty rule BUG-030 enforces at process start).
 */
internal class OverlayNotificationFactory(private val context: Context) {

    fun build(): Notification =
        NotificationCompat.Builder(context, NotificationHelper.CHANNEL_TASKS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Плавающий помощник")
            .setContentText("Нажмите на пузырёк, чтобы спросить Меха")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Закрыть", stopPendingIntent())
            .build()

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(context, OverlayAssistantService::class.java)
            .setAction(OverlayAssistantService.ACTION_STOP)
        return PendingIntent.getService(
            context,
            REQUEST_STOP,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val REQUEST_STOP = 1015
    }
}
