package com.andharness.ru.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.andharness.ru.domain.update.UpdateManifest
import com.andharness.ru.domain.update.UpdatePolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single owner of the in-app update lifecycle: check the manifest on a schedule, download and verify
 * the APK, then hand it to the system installer. Every entry point is best-effort — it never throws,
 * network/file failures surface as [UpdateState.Error] with an honest Russian reason, and installs
 * require the user's explicit consent through the system UI.
 *
 * The 12-hour check cache is persisted in a tiny `SharedPreferences` file so it survives process
 * restarts; a manual check passes `force = true` and always hits the network.
 *
 * References:
 * - `StateFlow` (observable UI state):
 *   https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/
 * - `PackageManager.getPackageInfo` / `PackageInfo.longVersionCode`:
 *   https://developer.android.com/reference/android/content/pm/PackageManager#getPackageInfo(java.lang.String,%20int)
 *   https://developer.android.com/reference/android/content/pm/PackageInfo#longVersionCode
 * - Unknown-sources gate (`PackageManager.canRequestPackageInstalls`):
 *   https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()
 * - `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`:
 *   https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updateClient: UpdateClient,
    private val updateDownloader: UpdateDownloader,
) : InAppUpdater {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Reads the installed app's version from `PackageManager` (`0`/blank when unavailable). */
    override fun installedVersion(): InstalledVersion = try {
        val info = packageInfo(context.packageManager)
        InstalledVersion(
            versionCode = info?.let { versionCodeOf(it) } ?: 0L,
            versionName = info?.versionName.orEmpty(),
        )
    } catch (_: Exception) {
        InstalledVersion(0L, "")
    }

    /**
     * Fetches the manifest and updates [state]. When [force] is `false` and a successful check ran
     * less than 12 hours ago, the cached state is returned without touching the network. Never throws.
     */
    override suspend fun check(force: Boolean): UpdateState {
        val current = _state.value
        if (!force && !isCheckStale()) return current
        _state.value = UpdateState.Checking
        val next = try {
            updateClient.fetchManifest().fold(
                onSuccess = { manifest ->
                    if (UpdatePolicy.isNewer(manifest.versionCode, installedVersion().versionCode)) {
                        UpdateState.Available(manifest)
                    } else {
                        UpdateState.UpToDate
                    }
                },
                onFailure = { error ->
                    UpdateState.Error(
                        error.message?.takeIf { it.isNotBlank() }
                            ?: "Не удалось проверить обновления."
                    )
                }
            )
        } catch (e: Exception) {
            UpdateState.Error(
                e.message?.takeIf { it.isNotBlank() } ?: "Не удалось проверить обновления."
            )
        }
        if (next !is UpdateState.Error) {
            prefs.edit().putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis()).apply()
        }
        _state.value = next
        return next
    }

    /**
     * Downloads and verifies the currently available manifest's APK (size + SHA-256 via the core,
     * then same-signer). On a signer mismatch the file is deleted, [state] becomes
     * [UpdateState.Error] and a [Result.failure] is returned, so a repackaged build can never be
     * installed. Never throws.
     */
    override suspend fun download(
        onProgress: (Long, Long) -> Unit,
    ): Result<File> {
        val manifest = currentManifest()
            ?: return Result.failure(
                IllegalStateException("Нет доступного обновления для загрузки.")
            )
        _state.value = UpdateState.Downloading(manifest, 0L, manifest.sizeBytes)
        val result = updateDownloader.downloadApk(manifest) { read, total ->
            _state.value = UpdateState.Downloading(manifest, read, total)
            onProgress(read, total)
        }
        return result.fold(
            onSuccess = { file ->
                if (ApkSignerVerifier.hasSameSigner(context, file)) {
                    Result.success(file)
                } else {
                    file.delete()
                    val message = "Подпись загруженного обновления не совпадает с установленным " +
                        "приложением — установка отменена."
                    _state.value = UpdateState.Error(message)
                    Result.failure(SecurityException(message))
                }
            },
            onFailure = { error ->
                _state.value = UpdateState.Error(
                    error.message?.takeIf { it.isNotBlank() }
                        ?: "Не удалось скачать обновление."
                )
                Result.failure(error)
            }
        )
    }

    /**
     * After a verified [file] is on disk, moves [state] to [UpdateState.InstallReady] when this app
     * may request installs, or [UpdateState.NeedsUnknownSources] when the user must grant that first.
     * Safe to call again after the user returns from the permission screen (it re-reads the gate).
     */
    override fun resolveInstall(file: File) {
        val manifest = currentManifest() ?: return
        _state.value = if (canInstall()) {
            UpdateState.InstallReady(manifest, file)
        } else {
            UpdateState.NeedsUnknownSources(manifest, file)
        }
    }

    /** `true` when this app may start an APK install (unknown-sources permission granted). */
    fun canInstall(): Boolean = AndroidInstallLauncher.canRequestPackageInstalls(context)

    /** Settings screen where the user grants this app permission to install unknown apps. */
    fun unknownSourcesIntent(): Intent = AndroidInstallLauncher.unknownSourcesIntent(context)

    /** Opens the system installer for [apkFile]; `false` when it could not be launched. */
    override fun install(apkFile: File): Boolean = AndroidInstallLauncher.install(context, apkFile)

    /** Returns to [UpdateState.Idle] (dismissing a dialog, or after the installer was opened). */
    override fun dismiss() {
        _state.value = UpdateState.Idle
    }

    /** The manifest of the update currently being offered/downloaded, if any. */
    private fun currentManifest(): UpdateManifest? = when (val current = _state.value) {
        is UpdateState.Available -> current.manifest
        is UpdateState.Downloading -> current.manifest
        is UpdateState.InstallReady -> current.manifest
        is UpdateState.NeedsUnknownSources -> current.manifest
        else -> null
    }

    /** `true` when no successful check was persisted, or it is older than [CHECK_INTERVAL_MILLIS]. */
    private fun isCheckStale(): Boolean {
        val last = prefs.getLong(KEY_LAST_CHECK_AT, 0L)
        if (last <= 0L) return true
        return System.currentTimeMillis() - last >= CHECK_INTERVAL_MILLIS
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(pm: PackageManager): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            pm.getPackageInfo(context.packageName, 0)
        }
    } catch (_: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }

    companion object {
        /** Preferences file backing the 12-hour check cache. */
        const val PREFS_NAME = "andharness_updates"

        /** Timestamp (millis) of the last successful check. */
        const val KEY_LAST_CHECK_AT = "last_check_at"

        /** How long a successful check is reused before the network is consulted again. */
        val CHECK_INTERVAL_MILLIS: Long = 12L * 60L * 60L * 1000L
    }
}
