package com.andharness.ru.di

import com.andharness.ru.domain.notify.NotificationCaptureLog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the in-memory notification capture buffer (update.md idea #17) as a singleton, so the
 * notification listener that fills it and the `notifications_digest` tool that reads it see the
 * same instance.
 */
@Module
@InstallIn(SingletonComponent::class)
object NotifyModule {

    @Provides
    @Singleton
    fun provideNotificationCaptureLog(): NotificationCaptureLog = NotificationCaptureLog()
}
