package com.andharness.ru.di

import com.andharness.ru.data.db.AppDatabase
import com.andharness.ru.data.db.dao.DeviceFactDao
import com.andharness.ru.data.db.dao.ExactAlarmDao
import com.andharness.ru.data.db.dao.FolderWatchDao
import com.andharness.ru.data.db.dao.SafGrantDao
import com.andharness.ru.data.db.dao.TrashEntryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Device/files DAO providers: device facts, exact alarms, folder watches, SAF grants, trash. */
@Module
@InstallIn(SingletonComponent::class)
object DeviceDbModule {

    @Provides
    fun provideDeviceFactDao(database: AppDatabase): DeviceFactDao = database.deviceFactDao()

    @Provides
    fun provideExactAlarmDao(database: AppDatabase): ExactAlarmDao = database.exactAlarmDao()

    @Provides
    fun provideFolderWatchDao(database: AppDatabase): FolderWatchDao = database.folderWatchDao()

    @Provides
    fun provideSafGrantDao(database: AppDatabase): SafGrantDao = database.safGrantDao()

    @Provides
    fun provideTrashEntryDao(database: AppDatabase): TrashEntryDao = database.trashEntryDao()
}
