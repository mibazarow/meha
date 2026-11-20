package com.andharness.ru.data.tools.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure decision logic behind `app_install_apk`: the path-vs-url choice, safe APK
 * file-name derivation, local-file validation, and the honest result texts. No Android classes are
 * touched, so these run off-device.
 */
class AppInstallApkPolicyTest {

    // --- source decision (path vs url) ---------------------------------------------------------

    @Test
    fun localPathWinsWhenBothPathAndUrlAreGiven() {
        val source = AppInstallApkPolicy.decideSource("/sdcard/a.apk", "https://x/a.apk")
        assertEquals(AppInstallApkPolicy.Source.LocalFile("/sdcard/a.apk"), source)
    }

    @Test
    fun urlIsUsedWhenPathIsAbsent() {
        val source = AppInstallApkPolicy.decideSource(null, "https://x/a.apk")
        assertEquals(AppInstallApkPolicy.Source.Remote("https://x/a.apk"), source)
    }

    @Test
    fun blankPathFallsBackToUrl() {
        val source = AppInstallApkPolicy.decideSource("   ", "https://x/a.apk")
        assertEquals(AppInstallApkPolicy.Source.Remote("https://x/a.apk"), source)
    }

    @Test
    fun blankPathAndUrlAreTrimmed() {
        val source = AppInstallApkPolicy.decideSource("  /sdcard/a.apk  ", null)
        assertEquals(AppInstallApkPolicy.Source.LocalFile("/sdcard/a.apk"), source)
    }

    @Test
    fun missingBothInputsIsInvalid() {
        val source = AppInstallApkPolicy.decideSource(null, null)
        assertTrue(source is AppInstallApkPolicy.Source.Invalid)
        assertTrue((source as AppInstallApkPolicy.Source.Invalid).message.isNotBlank())
    }

    @Test
    fun whitespaceOnlyInputsAreInvalid() {
        val source = AppInstallApkPolicy.decideSource("  ", "\t")
        assertTrue(source is AppInstallApkPolicy.Source.Invalid)
    }

    // --- file-name validation ------------------------------------------------------------------

    @Test
    fun apkExtensionIsRecognizedCaseInsensitively() {
        assertTrue(AppInstallApkPolicy.isApkFileName("Meha.apk"))
        assertTrue(AppInstallApkPolicy.isApkFileName("MEHA.APK"))
        assertTrue(AppInstallApkPolicy.isApkFileName("  Meha.Apk  "))
    }

    @Test
    fun nonApkNamesAreRejected() {
        assertFalse(AppInstallApkPolicy.isApkFileName("Meha.aab"))
        assertFalse(AppInstallApkPolicy.isApkFileName("Meha"))
        assertFalse(AppInstallApkPolicy.isApkFileName(".apk"))
        assertFalse(AppInstallApkPolicy.isApkFileName(""))
    }

    // --- URL → file name -----------------------------------------------------------------------

    @Test
    fun fileNameIsTakenFromUrlPath() {
        assertEquals("Meha.apk", AppInstallApkPolicy.apkFileNameFromUrl("https://host/dir/Meha.apk"))
    }

    @Test
    fun queryStringIsIgnored() {
        assertEquals(
            "Meha.apk",
            AppInstallApkPolicy.apkFileNameFromUrl("https://host/dir/Meha.apk?token=abc"),
        )
    }

    @Test
    fun missingExtensionGetsApkSuffix() {
        assertEquals("download.apk", AppInstallApkPolicy.apkFileNameFromUrl("https://host/download"))
    }

    @Test
    fun existingApkExtensionKeepsItsCase() {
        assertEquals("Meha.APK", AppInstallApkPolicy.apkFileNameFromUrl("https://host/Meha.APK"))
    }

    @Test
    fun unparseableOrSegmentlessUrlFallsBackToDefault() {
        assertEquals("install.apk", AppInstallApkPolicy.apkFileNameFromUrl("not a url"))
        assertEquals("install.apk", AppInstallApkPolicy.apkFileNameFromUrl("https://host/"))
    }

    // --- local-file validation -----------------------------------------------------------------

    @Test
    fun validApkPasses() {
        val result = AppInstallApkPolicy.validateLocalApk("/sdcard/a.apk", true, true, 1024L)
        assertTrue(result.ok)
        assertEquals("", result.message)
    }

    @Test
    fun missingFileFailsHonestly() {
        val result = AppInstallApkPolicy.validateLocalApk("/sdcard/a.apk", false, false, 0L)
        assertFalse(result.ok)
        assertTrue(result.message.contains("не найден"))
    }

    @Test
    fun directoryInsteadOfFileFailsHonestly() {
        val result = AppInstallApkPolicy.validateLocalApk("/sdcard/dir.apk", true, false, 0L)
        assertFalse(result.ok)
        assertTrue(result.message.contains("не является файлом"))
    }

    @Test
    fun wrongExtensionFailsHonestly() {
        val result = AppInstallApkPolicy.validateLocalApk("/sdcard/a.zip", true, true, 1024L)
        assertFalse(result.ok)
        assertTrue(result.message.contains("не является APK"))
    }

    @Test
    fun emptyFileFailsHonestly() {
        val result = AppInstallApkPolicy.validateLocalApk("/sdcard/a.apk", true, true, 0L)
        assertFalse(result.ok)
        assertTrue(result.message.contains("пуст"))
    }

    // --- honest result texts -------------------------------------------------------------------

    @Test
    fun notAllowedResultCarriesTheSpecialAccessToken() {
        assertEquals(
            "PERMISSION_REQUIRED:special:install_unknown_apps",
            AppInstallApkPolicy.notAllowedResult(),
        )
    }

    @Test
    fun dialogShownMessageIsHonestAboutTheUserConfirmation() {
        val message = AppInstallApkPolicy.dialogShownMessage("Meha.apk")
        assertTrue(message.contains("Meha.apk"))
        assertTrue(message.contains("подтверд"))
        assertTrue(message.contains("не знает"))
        assertTrue(message.contains("app_is_installed"))
        // It must never claim the app was actually installed.
        assertFalse(message.contains("установлено", ignoreCase = true))
        assertFalse(message.contains("успешно установлен", ignoreCase = true))
    }

    @Test
    fun downloadedMessageNamesFileSizeAndPath() {
        val message = AppInstallApkPolicy.downloadedMessage(
            fileName = "Meha.apk",
            absolutePath = "/data/cache/apk_install/Meha.apk",
            sizeBytes = 2L * 1024 * 1024,
        )
        assertTrue(message.contains("Meha.apk"))
        assertTrue(message.contains("2.0 МБ"))
        assertTrue(message.contains("/data/cache/apk_install/Meha.apk"))
    }

    @Test
    fun downloadCapIsBounded() {
        assertTrue(AppInstallApkPolicy.MAX_DOWNLOAD_BYTES > 0L)
        assertEquals(200L * 1024 * 1024, AppInstallApkPolicy.MAX_DOWNLOAD_BYTES)
    }
}
