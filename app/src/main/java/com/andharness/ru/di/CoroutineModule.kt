package com.andharness.ru.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Provides the application-lifetime [CoroutineScope] used to own long-running work that must OUTLIVE
 * the UI that started it (SPEC §20: an agent turn keeps running while the user navigates).
 *
 * [SupervisorJob] so one failed child cannot cancel the scope (and with it every other in-flight
 * turn); [Dispatchers.Default] because the turn body switches to IO itself where needed (Room,
 * OkHttp). Nothing here is tied to a screen, so leaving a chat screen cannot cancel a turn.
 *
 * Reference (coroutine scope / SupervisorJob semantics):
 * https://kotlinlang.org/docs/exception-handling.html#supervision-scope
 * Reference (structured concurrency):
 * https://kotlinlang.org/docs/coroutines-basics.html#structured-concurrency
 */
@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * CPU-bound dispatcher for off-main-thread work that is not I/O. [Singleton] + stateless, so the
     * same pool is reused. Injectable so unit tests can supply a deterministic test dispatcher.
     */
    @Provides
    @Singleton
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
