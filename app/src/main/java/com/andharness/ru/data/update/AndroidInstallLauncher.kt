package com.andharness.ru.data.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Launches the system package installer for a downloaded APK and reports whether the user has
 * granted this app permission to request installs ("unknown sources"). Every entry point fails soft
 * (returns `false`) instead of throwing.
 *
 * References:
 * - `Intent.ACTION_INSTALL_PACKAGE` + `EXTRA_NOT_UNKNOWN_SOURCE`:
 *   https://developer.android.com/reference/android/content/Intent#ACTION_INSTALL_PACKAGE
 * - Unknown-sources request (`PackageManager.canRequestPackageInstalls`, `REQUEST_INSTALL_PACKAGES`):
 *   https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()
 * - `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`:
 *   https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES
 * - FileProvider URIs (`androidx.core.content.FileProvider`):
 *   https://developer.android.com/reference/androidx/core/content/FileProvider
 */
object AndroidInstallLauncher {

    /** `true` when this app may start an APK install (API 26+; `false` on any error). */
    fun canRequestPackageInstalls(context: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    } catch (_: Exception) {
        false
    }

    /** Settings screen where the user grants this app permission to install unknown apps. */
    fun unknownSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.fromParts("package", context.packageName, null))

    /**
     * Opens the system installer for [apkFile] via its FileProvider URI
     * (`${context.packageName}.fileprovider`, see `res/xml/file_paths.xml`). Returns `true` when the
     * installer activity was launched, `false` when no activity handled it or anything else failed.
     */
    fun install(context: Context, apkFile: File): Boolean = try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: Exception) {
        false
    }
}
