package com.andharness.ru.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [UpdatePolicy]: build ordering, manifest validation and the mandatory rule.
 *
 * Reference: https://developer.android.com/studio/publish/versioning
 */
class UpdatePolicyTest {

    private val validSha = "a".repeat(UpdatePolicy.SHA256_HEX_LENGTH)

    private fun manifest(
        versionCode: Long = 42,
        url: String = "https://example.com/updates/meha-42.apk",
        sha256: String = validSha,
        minSupportedVersionCode: Long = 0,
        mandatory: Boolean = false,
    ) = UpdateManifest(
        versionCode = versionCode,
        url = url,
        sha256 = sha256,
        minSupportedVersionCode = minSupportedVersionCode,
        mandatory = mandatory,
    )

    @Test
    fun isNewerOnlyForStrictlyGreaterVersionCode() {
        assertTrue(UpdatePolicy.isNewer(remoteVersionCode = 43, installedVersionCode = 42))
        assertFalse(UpdatePolicy.isNewer(remoteVersionCode = 42, installedVersionCode = 42))
        assertFalse(UpdatePolicy.isNewer(remoteVersionCode = 41, installedVersionCode = 42))
    }

    @Test
    fun validateAcceptsWellFormedManifest() {
        assertNull(UpdatePolicy.validate(manifest()))
    }

    @Test
    fun validateRejectsNonPositiveVersionCode() {
        assertNotNull(UpdatePolicy.validate(manifest(versionCode = 0)))
        assertNotNull(UpdatePolicy.validate(manifest(versionCode = -1)))
    }

    @Test
    fun validateRejectsBlankOrHttpUrl() {
        assertNotNull(UpdatePolicy.validate(manifest(url = "")))
        assertNotNull(UpdatePolicy.validate(manifest(url = "http://example.com/a.apk")))
    }

    @Test
    fun validateRejectsBadSha256() {
        assertNotNull(UpdatePolicy.validate(manifest(sha256 = "")))
        assertNotNull(UpdatePolicy.validate(manifest(sha256 = "abc")))
        assertNotNull(UpdatePolicy.validate(manifest(sha256 = "z".repeat(UpdatePolicy.SHA256_HEX_LENGTH))))
    }

    @Test
    fun isMandatoryFlagForcesMandatory() {
        assertTrue(UpdatePolicy.isMandatory(manifest(mandatory = true), installedVersionCode = 42))
    }

    @Test
    fun isMandatoryWhenInstalledIsBelowMinSupported() {
        assertTrue(
            UpdatePolicy.isMandatory(
                manifest(minSupportedVersionCode = 50),
                installedVersionCode = 42,
            )
        )
        assertFalse(
            UpdatePolicy.isMandatory(
                manifest(minSupportedVersionCode = 42),
                installedVersionCode = 42,
            )
        )
        assertFalse(
            UpdatePolicy.isMandatory(
                manifest(minSupportedVersionCode = 50),
                installedVersionCode = 55,
            )
        )
    }

    @Test
    fun minSupportedOfZeroMeansNoForcedUpgrade() {
        assertFalse(UpdatePolicy.isMandatory(manifest(), installedVersionCode = 1))
    }

    @Test
    fun validateErrorReasonsAreRussianAndNonBlank() {
        val reasons = listOf(
            UpdatePolicy.validate(manifest(versionCode = 0)),
            UpdatePolicy.validate(manifest(url = "http://x")),
            UpdatePolicy.validate(manifest(sha256 = "bad")),
        ).filterNotNull()
        assertEquals(3, reasons.size)
        reasons.forEach { assertTrue("reason must be non-blank: «$it»", it.isNotBlank()) }
    }
}
