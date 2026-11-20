package com.andharness.ru.data.update

/**
 * Static coordinates of the in-app updater. Kept in one place so the client, the downloader and the
 * UI agree on the manifest endpoint and the downloaded file name.
 */
object UpdateConfig {

    /**
     * Production manifest, served as an asset of the latest GitHub release.
     *
     * The project has no update server of its own: `/releases/latest` resolves to the newest
     * non-draft, non-prerelease release, so this URL never changes between versions. See
     * `GITHUB_PUBLISH.md` for the publishing procedure.
     */
    const val MANIFEST_URL =
        "https://github.com/mibazarow/meha/releases/latest/download/latest.json"

    /** File name used for the downloaded APK inside the updates cache directory. */
    const val APK_FILE_NAME = "meha-update.apk"
}
