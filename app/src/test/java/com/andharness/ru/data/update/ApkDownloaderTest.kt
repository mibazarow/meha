package com.andharness.ru.data.update

import com.andharness.ru.domain.common.Hashing
import java.io.File
import java.io.IOException
import java.nio.file.Files
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure [ApkDownloader.downloadTo] core using MockWebServer: a matching payload is
 * verified and moved into place, while a checksum mismatch deletes the partial and throws.
 *
 * References:
 * - MockWebServer: https://square.github.io/okhttp/4/okhttp/okhttp3/mockwebserver/MockWebServer/
 * - Okio `Buffer`: https://square.github.io/okio/3.x/okio/okio/-Buffer/
 * - SHA-256 verification: https://developer.android.com/reference/java/security/MessageDigest
 */
class ApkDownloaderTest {

    private val payload = ByteArray(64 * 1024 + 123) { (it % 251).toByte() }
    private val sha = Hashing.sha256Hex(payload)

    private fun serverServing(bytes: ByteArray): MockWebServer {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        return server
    }

    @Test
    fun verifiesAndFinalizesMatchingDownload() {
        val server = serverServing(payload)
        val destDir = Files.createTempDirectory("apk-download-ok").toFile()
        try {
            var lastRead = 0L
            var lastTotal = -1L
            val result = ApkDownloader.downloadTo(
                client = OkHttpClient(),
                url = server.url("/meha-update.apk").toString(),
                expectedSha256 = sha,
                expectedSizeBytes = payload.size.toLong(),
                destDir = destDir,
                onProgress = { read, total -> lastRead = read; lastTotal = total },
            )

            assertTrue("final file must exist", result.isFile)
            assertEquals(UpdateConfig.APK_FILE_NAME, result.name)
            assertEquals(payload.size.toLong(), result.length())
            assertEquals(payload.size.toLong(), lastRead)
            assertEquals(payload.size.toLong(), lastTotal)
            assertFalse("partial file must be gone", File(destDir, "${UpdateConfig.APK_FILE_NAME}.part").exists())
            assertEquals("/meha-update.apk", server.takeRequest().path)
        } finally {
            server.shutdown()
            destDir.deleteRecursively()
        }
    }

    @Test
    fun deletesFileAndThrowsOnChecksumMismatch() {
        val server = serverServing(payload)
        val destDir = Files.createTempDirectory("apk-download-bad-sha").toFile()
        try {
            val thrown = try {
                ApkDownloader.downloadTo(
                    client = OkHttpClient(),
                    url = server.url("/meha-update.apk").toString(),
                    expectedSha256 = "0".repeat(64),
                    expectedSizeBytes = payload.size.toLong(),
                    destDir = destDir,
                    onProgress = { _, _ -> },
                )
                null
            } catch (e: IOException) {
                e
            }

            assertTrue("a checksum mismatch must throw", thrown != null)
            assertEquals("no final file may survive", 0, destDir.listFiles().orEmpty().size)
        } finally {
            server.shutdown()
            destDir.deleteRecursively()
        }
    }

    @Test
    fun deletesFileAndThrowsOnSizeMismatch() {
        val server = serverServing(payload)
        val destDir = Files.createTempDirectory("apk-download-bad-size").toFile()
        try {
            val thrown = try {
                ApkDownloader.downloadTo(
                    client = OkHttpClient(),
                    url = server.url("/meha-update.apk").toString(),
                    expectedSha256 = sha,
                    expectedSizeBytes = payload.size + 1L,
                    destDir = destDir,
                    onProgress = { _, _ -> },
                )
                null
            } catch (e: IOException) {
                e
            }

            assertTrue("a size mismatch must throw", thrown != null)
            assertEquals("no final file may survive", 0, destDir.listFiles().orEmpty().size)
        } finally {
            server.shutdown()
            destDir.deleteRecursively()
        }
    }
}
