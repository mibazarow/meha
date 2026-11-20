package com.andharness.ru.data.index

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.andharness.ru.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Realtime trigger for the image indexer: registers a [ContentObserver] on the images collection so a
 * gallery change (a new photo, a delete, an edit) enqueues a prompt incremental indexing pass.
 *
 * A change almost always arrives as a burst (one row insert fires several notifications), so callbacks
 * are DEBOUNCED by [DEBOUNCE_MS] before a single pass is enqueued. Registration is idempotent and lives
 * for the process lifetime; every failure is logged and never thrown, and the periodic scheduler remains
 * the safety net if a notification is ever missed.
 *
 * References:
 * - `ContentObserver` + `ContentResolver.registerContentObserver(uri, notifyForDescendants, observer)` —
 *   https://developer.android.com/reference/android/database/ContentObserver
 *   https://developer.android.com/reference/android/content/ContentResolver#registerContentObserver(android.net.Uri,%20boolean,%20android.database.ContentObserver)
 * - `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` —
 *   https://developer.android.com/reference/android/provider/MediaStore.Images.Media
 * - Coroutine debounce via job cancellation —
 *   https://kotlinlang.org/docs/cancellation-and-timeouts.html
 */
@Singleton
class DeviceImageObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduler: DeviceImageIndexScheduler,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val lock = Any()
    private var registered = false
    private var pending: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = schedulePass()

        override fun onChange(selfChange: Boolean, uri: Uri?) = schedulePass()
    }

    /** Registers the observer once for the process lifetime. Idempotent and failure-safe. */
    fun start() {
        synchronized(lock) {
            if (registered) return
            runCatching {
                context.contentResolver.registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    observer,
                )
                registered = true
                Log.i(TAG, "Registered MediaStore image ContentObserver for realtime indexing")
            }.onFailure { Log.w(TAG, "Registering the image ContentObserver failed", it) }
        }
    }

    /** Debounced enqueue: each callback resets the timer, so a burst yields one pass. */
    private fun schedulePass() {
        synchronized(lock) {
            pending?.cancel()
            pending = scope.launch {
                delay(DEBOUNCE_MS)
                runCatching { scheduler.enqueueIncrementalNow() }
                    .onFailure { Log.w(TAG, "Realtime image index enqueue failed", it) }
            }
        }
    }

    companion object {
        private const val TAG = "Andharness"

        /** Burst coalescing window before a pass is enqueued. */
        const val DEBOUNCE_MS = 5_000L
    }
}
