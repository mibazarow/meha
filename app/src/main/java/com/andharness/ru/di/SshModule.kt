package com.andharness.ru.di

import com.andharness.ru.data.ssh.SshServerRepositoryImpl
import com.andharness.ru.domain.ssh.SshServerRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the SSH server registry (update.md §5.2).
 *
 * The secret store and the known-hosts store need no binding: they are `@Singleton` classes with an
 * `@Inject` constructor that takes the application context
 * ([com.andharness.ru.data.ssh.SshSecretStore], [com.andharness.ru.data.ssh.KnownHostsStore]).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SshModule {

    @Binds
    @Singleton
    abstract fun bindSshServerRepository(impl: SshServerRepositoryImpl): SshServerRepository
}
