package com.andharness.ru.data.memory

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.andharness.ru.bg.DeviceFactsSweepWorker
import com.andharness.ru.domain.memory.DeviceFactRepository
import com.andharness.ru.domain.memory.DeviceFactSpecs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the device-fact cache honest (update.md §12.2): it turns the available non-root Android
 * events into cache invalidations.
 *
 * - `ACTION_PACKAGE_ADDED/REMOVED/REPLACED` → `installed_apps`, `default_roles`, `home_launcher`;
 * - `ACTION_MEDIA_MOUNTED/UNMOUNTED/EJECT` and `StorageManager.registerStorageVolumeCallback`
 *   (API 30+) → `storage_volumes`, `storage_space`;
 * - `ConnectivityManager.registerDefaultNetworkCallback` → `current_network`;
 * - `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` (see [DeviceFactsBootReceiver]) → every fact;
 * - a periodic [DeviceFactsSweepWorker] as a safety net for missed events / expired TTLs.
 *
 * Idempotent — safe to call from `Application.onCreate` and from the boot receiver.
 *
 * References:
 * - https://developer.android.com/develop/background-work/background-tasks/broadcasts
 * - https://developer.android.com/reference/android/os/storage/StorageManager#registerStorageVolumeCallback(java.util.concurrent.Executor,%20android.os.storage.StorageManager.StorageVolumeCallback)
 * - https://developer.android.com/reference/android/net/ConnectivityManager#registerDefaultNetworkCallback(android.net.ConnectivityManager.NetworkCallback)
 * - https://developer.android.com/reference/androidx/work/PeriodicWorkRequest
 */
@Singleton
class DeviceFactsInvalidationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DeviceFactRepository,
    private val store: DeviceFactStore
) {

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var packageReceiver: BroadcastReceiver? = null
    private var mediaReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var storageCallback: Any? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        registerPackageReceiver()
        registerMediaReceiver()
        registerNetworkCallback()
        registerStorageVolumeCallback()
        scheduleSweep()
    }

    /** Reboot / self-update: invalidate everything, then repopulate the cache in the background. */
    fun onBootOrUpdate(reason: String) {
        scope.launch { refreshAfterBootOrUpdate(reason) }
    }

    /**
     * Suspending form of [onBootOrUpdate], so a `BroadcastReceiver` holding a `goAsync()` pending
     * result can await the refresh before finishing (otherwise the process could be killed first).
     */
    suspend fun refreshAfterBootOrUpdate(reason: String) {
        runCatching {
            repository.invalidateAll(reason)
            store.refreshAll()
        }.onFailure { Log.e(TAG, "Refreshing device facts after $reason failed", it) }
    }

    /** Package install/uninstall/replace: the app inventory and default roles may have changed. */
    fun invalidateForPackages(reason: String) {
        scope.launch { invalidateForPackagesNow(reason) }
    }

    /** Suspending form of [invalidateForPackages], for a receiver's bounded `goAsync()` work. */
    suspend fun invalidateForPackagesNow(reason: String) {
        invalidateNow(
            listOf(
                DeviceFactSpecs.INSTALLED_APPS,
                DeviceFactSpecs.DEFAULT_ROLES,
                DeviceFactSpecs.HOME_LAUNCHER
            ),
            reason
        )
    }

    /** Storage volume mounted/unmounted/ejected: volumes and free space may have changed. */
    fun invalidateForStorage(reason: String) {
        invalidate(
            listOf(DeviceFactSpecs.STORAGE_VOLUMES, DeviceFactSpecs.STORAGE_SPACE),
            reason
        )
    }

    /** Default network changed. */
    fun invalidateNetwork(reason: String) {
        invalidate(listOf(DeviceFactSpecs.CURRENT_NETWORK), reason)
    }

    private fun invalidate(keys: List<String>, reason: String) {
        scope.launch { invalidateNow(keys, reason) }
    }

    /** The actual (suspending) invalidation loop; awaits completion when called from `goAsync()`. */
    private suspend fun invalidateNow(keys: List<String>, reason: String) {
        keys.forEach { key ->
            runCatching { repository.invalidate(key, reason) }
                .onFailure { Log.e(TAG, "Invalidating $key ($reason) failed", it) }
        }
    }

    private fun registerPackageReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                invalidateForPackages(intent?.action.orEmpty())
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            packageReceiver = receiver
        } catch (e: Exception) {
            Log.e(TAG, "Registering package receiver failed", e)
        }
    }

    private fun registerMediaReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                invalidateForStorage(intent?.action.orEmpty())
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            mediaReceiver = receiver
        } catch (e: Exception) {
            Log.e(TAG, "Registering media receiver failed", e)
        }
    }

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                invalidateNetwork("NETWORK_AVAILABLE")
            }

            override fun onLost(network: android.net.Network) {
                invalidateNetwork("NETWORK_LOST")
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.e(TAG, "Registering default network callback failed", e)
        }
    }

    private fun registerStorageVolumeCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val sm = context.getSystemService(StorageManager::class.java) ?: return
        val callback = object : StorageManager.StorageVolumeCallback() {
            override fun onStateChanged(volume: StorageVolume) {
                invalidateForStorage("STORAGE_VOLUME_STATE")
            }
        }
        try {
            val executor = Executor { it.run() }
            sm.registerStorageVolumeCallback(executor, callback)
            storageCallback = callback
        } catch (e: Exception) {
            Log.e(TAG, "Registering storage volume callback failed", e)
        }
    }

    private fun scheduleSweep() {
        try {
            val request = PeriodicWorkRequestBuilder<DeviceFactsSweepWorker>(
                SWEEP_INTERVAL_MINUTES, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                SWEEP_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } catch (e: Exception) {
            Log.e(TAG, "Scheduling device-fact sweep failed", e)
        }
    }

    companion object {
        private const val TAG = "Andharness"
        const val SWEEP_WORK_NAME = "device_facts_sweep"
        private const val SWEEP_INTERVAL_MINUTES = 15L
    }
}
