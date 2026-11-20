package com.andharness.ru.di

import com.andharness.ru.data.watch.FolderWatchRepositoryImpl
import com.andharness.ru.domain.watch.FolderWatchRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FolderWatchModule {

    @Binds
    @Singleton
    abstract fun bindFolderWatchRepository(impl: FolderWatchRepositoryImpl): FolderWatchRepository
}
