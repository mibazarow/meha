package com.andharness.ru.di

import com.andharness.ru.data.update.InAppUpdater
import com.andharness.ru.data.update.UpdateManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the in-app self-updater ([UpdateManager]) as [InAppUpdater]. It lives in the `sideload`
 * source set because the engine needs `REQUEST_INSTALL_PACKAGES`, which is declared in that
 * manifest (see `UPDATER.md`).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdaterModule {

    @Binds
    @Singleton
    abstract fun bindInAppUpdater(impl: UpdateManager): InAppUpdater
}
