package com.andharness.ru.data.update

import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * Seam for the in-app self-updater (see `UPDATER.md`).
 *
 * The engine ([UpdateManager], in the `sideload` source set) checks the update manifest, downloads
 * and verifies the APK, then hands it to the system installer. It is kept OUT of `main` on purpose:
 * this interface is the only thing the shared UI and application wiring know about, so the engine
 * stays in the source set whose manifest declares the install permission it needs.
 */
interface InAppUpdater {

    /** Observable update lifecycle, owned by the implementation. */
    val state: StateFlow<UpdateState>

    /** Installed app version read from `PackageManager` (`0`/blank when unavailable). */
    fun installedVersion(): InstalledVersion

    /**
     * Fetches the manifest and updates [state]. When [force] is `false` a recently cached check may
     * be reused. Never throws.
     */
    suspend fun check(force: Boolean = false): UpdateState

    /**
     * Downloads and verifies the currently offered APK. Progress is carried by [state]. Never
     * throws — failures are returned as [Result.failure].
     */
    suspend fun download(onProgress: (Long, Long) -> Unit = { _, _ -> }): Result<File>

    /**
     * After a verified [file] is on disk, moves [state] to the install-ready (or needs-permission)
     * state. Safe to call again after the user returns from the permission screen.
     */
    fun resolveInstall(file: File)

    /** Opens the system installer for [apkFile]; `false` when it could not be launched. */
    fun install(apkFile: File): Boolean

    /** Returns [state] to [UpdateState.Idle] (dismissing a dialog, or after the installer opened). */
    fun dismiss()
}
