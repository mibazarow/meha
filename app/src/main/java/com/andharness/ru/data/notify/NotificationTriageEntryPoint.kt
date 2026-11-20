package com.andharness.ru.data.notify

import android.content.Context
import com.andharness.ru.domain.notify.NotificationCaptureLog
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Hilt bridge for the framework-instantiated producers (the notification listener service and the
 * SMS receiver), which cannot use constructor injection.
 *
 * Reference: https://developer.android.com/training/dependency-injection/hilt-android#not-supported
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NotificationTriageEntryPoint {
    fun notificationTriageEngine(): NotificationTriageEngine
    fun notificationCaptureLog(): NotificationCaptureLog
}

/** Convenience accessor used by the listener/service/receiver. */
fun notificationTriageEntryPoint(context: Context): NotificationTriageEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, NotificationTriageEntryPoint::class.java)
