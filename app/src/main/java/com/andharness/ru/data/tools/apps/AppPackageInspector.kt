package com.andharness.ru.data.tools.apps

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.andharness.ru.data.tools.formatTimestamp

/**
 * Read-only [PackageManager] helpers shared by the app-inspection tools: they load [PackageInfo]
 * across API levels and format version/install metadata, so the per-tool handlers stay free of
 * duplicated `Build.VERSION` branching.
 *
 * References:
 * - `PackageManager.getPackageInfo(String, int)` / `getPackageInfo(String, PackageInfoFlags)`:
 *   https://developer.android.com/reference/android/content/pm/PackageManager#getPackageInfo(java.lang.String,%20int)
 * - `PackageInfo.longVersionCode` / `versionCode`, `PackageInfo.applicationInfo`:
 *   https://developer.android.com/reference/android/content/pm/PackageInfo
 * - `PackageManager.getInstallSourceInfo(String)` / `getInstallerPackageName(String)`:
 *   https://developer.android.com/reference/android/content/pm/PackageManager
 */
internal class AppPackageInspector {

    /**
     * Loads [PackageInfo] with legacy int [flags], using the API 33+ `PackageInfoFlags` overload
     * when available (the int overload is deprecated there). Returns `null` for
     * [PackageManager.NameNotFoundException], i.e. the package is not installed or is hidden by
     * package visibility — callers surface that honestly to the model.
     */
    @Suppress("DEPRECATION")
    fun load(pm: PackageManager, pkg: String, flags: Int): PackageInfo? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                pm.getPackageInfo(pkg, flags)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

    @Suppress("DEPRECATION")
    fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    fun installer(pm: PackageManager, pkg: String): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                pm.getInstallSourceInfo(pkg).installingPackageName ?: "неизвестно"
            } catch (e: Exception) {
                "неизвестно"
            }
        } else {
            pm.getInstallerPackageName(pkg) ?: "неизвестно"
        }

    fun time(millis: Long): String =
        if (millis <= 0L) "неизвестно" else formatTimestamp(millis, "yyyy-MM-dd HH:mm:ss")

    fun components(label: String, names: List<String>?): String {
        if (names.isNullOrEmpty()) return "$label: нет"
        val shown = names.take(AppToolLimits.MAX_COMPONENTS)
        val suffix = if (names.size > AppToolLimits.MAX_COMPONENTS) {
            "\n  … и ещё ${names.size - AppToolLimits.MAX_COMPONENTS}"
        } else {
            ""
        }
        return "$label (${names.size}):\n  " + shown.joinToString("\n  ") + suffix
    }
}
