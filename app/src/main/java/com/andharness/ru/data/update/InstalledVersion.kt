package com.andharness.ru.data.update


/**
 * Installed app version read from `PackageManager` (BuildConfig is disabled). [versionCode] is `0`
 * and [versionName] is blank when the package info cannot be read, so the updater degrades honestly
 * instead of crashing.
 */
data class InstalledVersion(
    val versionCode: Long,
    val versionName: String,
)
