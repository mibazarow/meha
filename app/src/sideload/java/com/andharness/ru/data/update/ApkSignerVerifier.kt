package com.andharness.ru.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.andharness.ru.domain.common.Hashing
import java.io.File

/**
 * Verifies that a downloaded APK is signed by the SAME certificate(s) as the installed app, so the
 * updater can never install a repackaged/malicious build. The comparison is over the set of SHA-256
 * digests of the signing certificates, not the raw encodings, and it fails closed (`false`) on any
 * null/parse/permission error.
 *
 * API notes:
 * - `PackageManager.GET_SIGNING_CERTIFICATES` / `PackageInfo.signingInfo` exist on API 28+; on
 *   API 26–27 the (deprecated) `GET_SIGNATURES` / `PackageInfo.signatures` are used instead.
 * - `apkContentsSigners` is the set of signers of the APK's contents (the identity an update must
 *   match); `signingCertificateHistory` would also match a rotated-history certificate, which is not
 *   what we want for a strict same-signer check.
 *
 * References:
 * - `PackageManager.getPackageArchiveInfo`:
 *   https://developer.android.com/reference/android/content/pm/PackageManager#getPackageArchiveInfo(java.lang.String,%20int)
 * - `PackageManager.GET_SIGNING_CERTIFICATES` / `PackageInfo.signingInfo` / `SigningInfo.apkContentsSigners`:
 *   https://developer.android.com/reference/android/content/pm/PackageManager#GET_SIGNING_CERTIFICATES
 *   https://developer.android.com/reference/android/content/pm/SigningInfo
 * - `PackageInfo.signatures` (pre-API 28):
 *   https://developer.android.com/reference/android/content/pm/PackageInfo#signatures
 * - `android.content.pm.Signature#toByteArray`:
 *   https://developer.android.com/reference/android/content/pm/Signature
 */
object ApkSignerVerifier {

    /** `true` only when every signer digest of [apkFile] matches the installed app's signer set. */
    fun hasSameSigner(context: Context, apkFile: File): Boolean = try {
        val pm = context.packageManager
        val flags = signatureFlags()
        val archive = packageArchiveInfo(pm, apkFile.absolutePath, flags)
        val installed = installedPackageInfo(pm, context.packageName, flags)
        val archiveDigests = archive?.let { signerDigests(it) }
        val installedDigests = installed?.let { signerDigests(it) }
        !archiveDigests.isNullOrEmpty() && archiveDigests == installedDigests
    } catch (_: Exception) {
        false
    }

    /** Pre-API 28 uses `GET_SIGNATURES`; from API 28 `GET_SIGNING_CERTIFICATES` returns `signingInfo`. */
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    /** Parses an APK file's manifest, or `null` when it cannot be read. */
    @Suppress("DEPRECATION")
    private fun packageArchiveInfo(pm: PackageManager, path: String, flags: Int): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageArchiveInfo(path, flags)
        }
    } catch (_: Exception) {
        null
    }

    /** Reads the installed package's [PackageInfo], or `null` when it is unavailable. */
    @Suppress("DEPRECATION")
    private fun installedPackageInfo(pm: PackageManager, pkg: String, flags: Int): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageInfo(pkg, flags)
        }
    } catch (_: Exception) {
        null
    }

    /** Set of lowercase-hex SHA-256 digests of [info]'s current signers, or `null` when absent. */
    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String>? {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return signatures?.map { Hashing.sha256Hex(it.toByteArray()) }?.toSet()
    }
}
