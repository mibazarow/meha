package com.andharness.ru.domain.update

/**
 * Pure, Android-free update decisions shared by the client, the downloader and (later) the UI. Kept
 * side-effect free so every rule is exercised on the JVM by `UpdatePolicyTest`.
 *
 * References:
 * - Android versionCode ordering (higher code = newer build):
 *   https://developer.android.com/studio/publish/versioning
 * - SHA-256 hex representation (64 lowercase hex chars):
 *   https://developer.android.com/reference/java/security/MessageDigest
 */
object UpdatePolicy {

    /** Length of a SHA-256 digest in lowercase hex characters. */
    const val SHA256_HEX_LENGTH = 64

    /**
     * `true` when [remoteVersionCode] is a strictly newer build than [installedVersionCode].
     * Equal or older remote builds are not an update.
     */
    fun isNewer(remoteVersionCode: Long, installedVersionCode: Long): Boolean =
        remoteVersionCode > installedVersionCode

    /**
     * Validates a fetched [m]: returns `null` when it is safe to use, otherwise a short Russian
     * reason. Every field the downloader relies on (https URL, 64-hex SHA-256, positive versionCode)
     * must be present, so a corrupt or downgraded manifest is rejected before any network/file work.
     */
    fun validate(m: UpdateManifest): String? = when {
        m.versionCode <= 0L -> "Манифест обновления повреждён: некорректный versionCode."
        !m.url.startsWith("https://") -> "Манифест обновления: ссылка должна начинаться с https://."
        !isSha256Hex(m.sha256) -> "Манифест обновления: некорректная контрольная сумма SHA-256."
        else -> null
    }

    /**
     * An update is mandatory when the server flags it as such OR the installed build is older than the
     * minimum still supported ([UpdateManifest.minSupportedVersionCode], ignored when `<= 0`).
     */
    fun isMandatory(m: UpdateManifest, installedVersionCode: Long): Boolean =
        m.mandatory || (m.minSupportedVersionCode > 0L && installedVersionCode < m.minSupportedVersionCode)

    /** `true` when [value] is exactly [SHA256_HEX_LENGTH] hex characters (any case). */
    private fun isSha256Hex(value: String): Boolean {
        if (value.length != SHA256_HEX_LENGTH) return false
        return value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
}
