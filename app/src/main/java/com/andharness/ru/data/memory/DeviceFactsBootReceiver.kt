package com.andharness.ru.data.memory

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Manifest receiver for the device-fact lifecycle events that must survive a cold start
 * (update.md §12.2):
 *
 * - `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` → invalidate every fact and repopulate the cache;
 * - `PACKAGE_FULLY_REMOVED` → invalidate the app inventory and default roles.
 *
 * It also (re-)arms the runtime invalidation hooks, since the boot broadcast may have started a
 * fresh process. Registered in `AndroidManifest.xml`.
 * Reference: https://developer.android.com/develop/background-work/background-tasks/broadcasts
 */
@AndroidEntryPoint
class DeviceFactsBootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var invalidationManager: DeviceFactsInvalidationManager

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        // The invalidation writes to Room and a process death here would silently drop them, so hold
        // the receiver alive with goAsync() until the suspending work really finishes.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                invalidationManager.start()
                when (action) {
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED ->
                        invalidationManager.refreshAfterBootOrUpdate(action)

                    Intent.ACTION_PACKAGE_FULLY_REMOVED ->
                        invalidationManager.invalidateForPackagesNow(action)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Handling $action failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        private const val TAG = "Andharness"
    }
}
