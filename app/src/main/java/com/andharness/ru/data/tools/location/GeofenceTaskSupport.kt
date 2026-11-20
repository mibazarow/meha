package com.andharness.ru.data.tools.location

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Bridges Play Services geofencing tasks to suspend calls, shared by the geofence tools. */
internal object GeofenceTaskSupport {

    /**
     * Bridges a Play Services [Task] to a suspend call so the tool can await the real
     * success/failure of the registration instead of reporting optimistically.
     */
    suspend fun awaitTask(task: Task<Void>): Result<Unit> =
        suspendCancellableCoroutine { cont ->
            task.addOnCompleteListener { completed ->
                if (cont.isCompleted) return@addOnCompleteListener
                val error = completed.exception
                if (completed.isSuccessful || error == null) {
                    cont.resume(Result.success(Unit))
                } else {
                    cont.resume(Result.failure(error))
                }
            }
        }
}
