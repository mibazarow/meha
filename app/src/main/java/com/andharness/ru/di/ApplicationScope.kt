package com.andharness.ru.di

import javax.inject.Qualifier

/**
 * Qualifies the process-lifetime [kotlinx.coroutines.CoroutineScope] the app owns (as opposed to a
 * caller's scope such as `viewModelScope`).
 *
 * It exists so a background agent turn can be launched into a scope that is NOT a child of the
 * screen that started it: navigating away from a chat cancels `viewModelScope`, and a turn that ran
 * in that scope was cancelled with it. The application scope survives navigation and is cancelled
 * only when the process dies.
 *
 * Reference (structured concurrency / why a child is cancelled with its parent):
 * https://kotlinlang.org/docs/coroutines-basics.html#structured-concurrency
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
