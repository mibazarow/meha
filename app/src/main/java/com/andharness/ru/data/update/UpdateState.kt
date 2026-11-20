package com.andharness.ru.data.update

import com.andharness.ru.domain.update.UpdateManifest
import java.io.File

/**
 * Lifecycle of the in-app updater, owned and transitioned by [UpdateManager] and observed by the
 * settings UI. Modeling it as one sealed hierarchy (instead of a set of booleans) keeps the screen
 * from showing contradictory states, e.g. "downloading" together with "available".
 *
 * Progress: [Downloading] carries the byte counters so the UI can render a determinate
 * [androidx.compose.material3.LinearProgressIndicator]; [Downloading.totalBytes] is `-1` when the
 * server did not report a size.
 */
sealed interface UpdateState {

    /** Nothing checked yet in this process; the last check may still be cached on disk. */
    object Idle : UpdateState

    /** A manifest fetch is in flight. */
    object Checking : UpdateState

    /** The server advertises a strictly newer build; see `UpdatePolicy.isNewer`. */
    data class Available(val manifest: UpdateManifest) : UpdateState

    /** The installed build is current (remote versionCode is equal or older). */
    object UpToDate : UpdateState

    /** Any failure (network, HTTP, corrupt manifest, checksum/signer mismatch), with an honest reason. */
    data class Error(val message: String) : UpdateState

    /** The APK download is in flight. */
    data class Downloading(
        val manifest: UpdateManifest,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateState

    /** The download was verified (size, SHA-256, signer) and may be handed to the system installer. */
    data class InstallReady(val manifest: UpdateManifest, val file: File) : UpdateState

    /** The download is verified but the app may not yet request installs ("unknown sources"). */
    data class NeedsUnknownSources(val manifest: UpdateManifest, val file: File) : UpdateState
}

/** Bytes written so far as a `0f..1f` fraction, or `0f` while the total size is unknown. */
val UpdateState.Downloading.progressFraction: Float
    get() = if (totalBytes > 0L) {
        (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }

/** `true` when the server reported a positive content length, i.e. progress can be determinate. */
val UpdateState.Downloading.hasKnownTotal: Boolean
    get() = totalBytes > 0L
