package com.andharness.ru.di

import android.content.Context
import com.andharness.ru.data.offline.OfflineActionQueue
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Provides the single file-backed [OfflineActionQueue] (§2 "Очередь действий offline"). The queue
 * itself is Android-free (it only takes a directory), so it lives under `filesDir` exactly like
 * `SkillStore` and is unit-tested on the JVM.
 *
 * Kept in its own module (rather than added to `ToolBindingsModule`) so the offline-queue slice
 * stays isolated from the tool catalog, which another task owns.
 */
@Module
@InstallIn(SingletonComponent::class)
object OfflineQueueModule {

    @Provides
    @Singleton
    fun provideOfflineActionQueue(@ApplicationContext context: Context): OfflineActionQueue =
        OfflineActionQueue(File(context.filesDir, OfflineActionQueue.DIR_NAME))
}
