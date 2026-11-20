package com.andharness.ru.domain.localserver

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [LocalModelStateResolver] — the rule that the persisted status is only a HINT
 * and the FILES on disk decide the honest state. The regression this guards: prefs still say `done`
 * after the model was deleted on-device, which used to display «Модель загружена и проверена».
 */
class LocalModelStateResolverTest {

    private val donePath = "/data/user/0/com.andharness.ru/files/models/meha.gguf"

    private fun resolve(filesComplete: Boolean, status: String?, reason: String? = null) =
        LocalModelStateResolver.resolve(
            filesComplete = filesComplete,
            storedStatus = status,
            storedReason = reason,
            donePath = donePath,
        )

    @Test
    fun staleDonePrefsWithFilesMissingResolveToNotDownloaded() {
        // THE regression: the persisted flag says done, but nothing is on disk.
        assertEquals(
            ModelDownloadState.NotDownloaded,
            resolve(filesComplete = false, status = LocalModelStateResolver.STATUS_DONE),
        )
    }

    @Test
    fun filesCompleteResolvesToDoneEvenWithoutPersistedFlag() {
        assertEquals(
            ModelDownloadState.Done(donePath),
            resolve(filesComplete = true, status = null),
        )
    }

    @Test
    fun filesCompleteWinsOverAStaleFailedFlag() {
        assertEquals(
            ModelDownloadState.Done(donePath),
            resolve(filesComplete = true, status = LocalModelStateResolver.STATUS_FAILED, reason = "boom"),
        )
    }

    @Test
    fun failedFlagIsHonestWhenFilesAreStillMissing() {
        assertEquals(
            ModelDownloadState.Failed("сеть недоступна"),
            resolve(
                filesComplete = false,
                status = LocalModelStateResolver.STATUS_FAILED,
                reason = "сеть недоступна",
            ),
        )
    }

    @Test
    fun unknownOrNullStatusWithFilesMissingResolvesToNotDownloaded() {
        assertEquals(ModelDownloadState.NotDownloaded, resolve(filesComplete = false, status = "active"))
        assertEquals(ModelDownloadState.NotDownloaded, resolve(filesComplete = false, status = null))
    }

    @Test
    fun onDiskTruncationUsesTheSameRuleAsTheStore() {
        // Mirrors the store wiring: a truncated file makes the set incomplete, so a done flag is
        // distrusted and the honest state is NotDownloaded.
        val dir = File(System.getProperty("java.io.tmpdir"), "lmsr-${System.nanoTime()}").apply { mkdirs() }
        val truncated = File(dir, "local-chat.gguf").apply {
            outputStream().use { out -> out.write(ByteArray(64)) }
        }
        assertTrue(truncated.isFile)
        // The expected size is far larger than the 64 bytes written, so the file is incomplete.
        val complete = LocalModelReadiness.allComplete(listOf(truncated to 1000L))
        assertEquals(
            ModelDownloadState.NotDownloaded,
            resolve(filesComplete = complete, status = LocalModelStateResolver.STATUS_DONE),
        )
    }
}
