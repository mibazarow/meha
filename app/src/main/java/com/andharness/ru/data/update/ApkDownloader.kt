package com.andharness.ru.data.update

import com.andharness.ru.domain.common.Hashing
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Pure, Android-free core of the APK download: stream, verify size + SHA-256, then move the verified
 * `.part` into place. It only touches java.io + OkHttp, so it is exercised on the JVM by
 * `ApkDownloaderTest` (MockWebServer). Blocking by design — callers wrap it in `Dispatchers.IO`.
 *
 * References:
 * - Streaming a response body to a file:
 *   https://square.github.io/okhttp/recipes/
 * - `File.renameTo` atomic move within one filesystem:
 *   https://developer.android.com/reference/java/io/File#renameTo(java.io.File)
 * - SHA-256 verification before install:
 *   https://developer.android.com/reference/java/security/MessageDigest
 */
object ApkDownloader {

    private const val BUFFER_BYTES = 64 * 1024

    /**
     * Downloads [url] into `destDir/<fileName>.part`, verifies it and atomically renames it to
     * `destDir/<fileName>`.
     *
     * @param expectedSizeBytes exact byte size required when `> 0` (otherwise only SHA-256 is checked).
     * @param expectedSha256 lowercase/uppercase 64-hex digest required when non-blank.
     * @param onProgress invoked with `(bytesRead, total)`; `total` is `-1` when unknown.
     * @return the verified, renamed file.
     * @throws IOException on any network/HTTP/size/checksum/rename failure; the partial file is
     *   deleted before the exception is thrown so a corrupt download never survives.
     */
    fun downloadTo(
        client: OkHttpClient,
        url: String,
        expectedSha256: String,
        expectedSizeBytes: Long,
        destDir: File,
        fileName: String = UpdateConfig.APK_FILE_NAME,
        onProgress: (bytesRead: Long, total: Long) -> Unit,
    ): File {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw IOException("Не удалось создать папку загрузки «${destDir.absolutePath}».")
        }
        if (!destDir.isDirectory) {
            throw IOException("Путь «${destDir.absolutePath}» не является папкой.")
        }
        val partial = File(destDir, "$fileName.part")
        val target = File(destDir, fileName)
        // A fresh attempt must not resume a corrupt/old partial.
        if (partial.exists() && !partial.delete()) {
            throw IOException("Не удалось очистить прежнюю загрузку «${partial.absolutePath}».")
        }
        if (target.exists()) target.delete()

        var downloaded = 0L
        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Сервер обновлений ответил HTTP ${response.code} при загрузке APK.")
                }
                val body = response.body
                    ?: throw IOException("Сервер обновлений вернул пустой ответ при загрузке APK.")
                val contentLength = body.contentLength()
                val total = contentLength.takeIf { it > 0L } ?: expectedSizeBytes

                body.byteStream().use { input ->
                    FileOutputStream(partial, false).use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }
            }

            if (expectedSizeBytes > 0L && partial.length() != expectedSizeBytes) {
                throw IOException(
                    "Размер загруженного APK (${partial.length()} Б) не совпал с ожидаемым " +
                        "($expectedSizeBytes Б)."
                )
            }
            if (expectedSha256.isNotBlank()) {
                val actual = partial.inputStream().use { Hashing.sha256Hex(it) }
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    throw IOException("Контрольная сумма загруженного APK не совпала (SHA-256).")
                }
            }
            if (!partial.renameTo(target)) {
                throw IOException("Не удалось переименовать загруженный APK в «${target.absolutePath}».")
            }
            return target
        } catch (e: IOException) {
            if (partial.exists()) partial.delete()
            throw e
        } catch (e: Exception) {
            if (partial.exists()) partial.delete()
            throw IOException(e.message?.takeIf { it.isNotBlank() } ?: "Ошибка загрузки APK", e)
        }
    }
}
