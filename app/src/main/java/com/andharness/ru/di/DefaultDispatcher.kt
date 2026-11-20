package com.andharness.ru.di

import javax.inject.Qualifier

/**
 * Qualifies the CPU-bound [kotlinx.coroutines.CoroutineDispatcher] used for off-main-thread work that
 * is NOT I/O (list mapping, ranking, …). Bound to [kotlinx.coroutines.Dispatchers.Default] in
 * production; tests inject their own dispatcher so work stays on the test scheduler and does not leak
 * past the test (a real `Dispatchers.Default` thread resuming on a torn-down `Dispatchers.Main`
 * would throw).
 *
 * Reference: https://developer.android.com/kotlin/coroutines#dependency-injection
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher
