package com.andharness.ru.data.tools.network

import android.content.Context
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.data.tools.effectiveToolRoot
import com.andharness.ru.data.tools.files.sanitizeFileName
import com.andharness.ru.data.tools.readBounded
import com.andharness.ru.data.tools.resolveSafePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder

/** Default download size cap: 200 MiB. */
private const val DEFAULT_MAX_BYTES = 200L * 1024 * 1024

/** Cap on the body read by `http_request` (2 MiB): an unbounded read could exhaust memory. */
private const val HTTP_BODY_MAX_BYTES = 2L * 1024 * 1024

/** Copy buffer used while streaming the response body to disk. */
private const val COPY_BUFFER_SIZE = 8 * 1024

/**
 * Default destination directory for [download_file] when the caller omits `destination`:
 * the public «Загрузки» folder. Resolved relative to the allowed storage root, so this is the
 * value of `Environment.DIRECTORY_DOWNLOADS` (literally `"Download"`, no trailing slash).
 * Reference: https://developer.android.com/reference/android/os/Environment#DIRECTORY_DOWNLOADS
 */
private const val DEFAULT_DOWNLOAD_DIR = "Download"

/**
 * Builds one [ToolHandler] per network tool: [http_request] and [download_file].
 *
 * References:
 * - OkHttp `ResponseBody.byteStream()` (streaming, avoids loading the whole body into RAM):
 *   https://square.github.io/okhttp/
 * - `java.io.File#renameTo(java.io.File)`:
 *   https://developer.android.com/reference/java/io/File#renameTo(java.io.File)
 * - RFC 6266 (Content-Disposition, incl. `filename*` / RFC 5987 encoding):
 *   https://www.rfc-editor.org/rfc/rfc6266
 * - kotlinx.serialization `JsonObject` / `JsonPrimitive`:
 *   https://kotlinlang.org/docs/serialization.html
 */
class NetworkToolsHandler(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "http_request"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val method = ((arguments["method"] as? JsonPrimitive)?.content ?: "GET").uppercase()
                    val url = (arguments["url"] as? JsonPrimitive)?.content.orEmpty()
                    if (url.isBlank()) {
                        throw IllegalArgumentException("Отсутствует обязательный аргумент: url")
                    }

                    val headers = arguments["headers"] as? JsonObject ?: emptyMap()

                    val bodyText = (arguments["body"] as? JsonPrimitive)?.content
                    val requestBody = if (bodyText != null) {
                        val mediaType = if (headers["Content-Type"] != null) {
                            (headers["Content-Type"] as? JsonPrimitive)?.content?.toMediaTypeOrNull()
                        } else null
                        bodyText.toRequestBody(mediaType)
                    } else null

                    val builder = Request.Builder().url(url)
                    for ((key, value) in headers) {
                        builder.header(key, (value as? JsonPrimitive)?.content.orEmpty())
                    }
                    val request = when (method) {
                        "GET", "HEAD", "DELETE" -> {
                            if (requestBody != null) builder.method(method, requestBody)
                            else builder.method(method, null)
                        }
                        else -> builder.method(method, requestBody ?: byteArrayOf().toRequestBody("application/octet-stream".toMediaTypeOrNull()))
                    }.build()

                    okHttpClient.newCall(request).execute().use { resp ->
                        val body = resp.body
                        val declared = body?.contentLength() ?: -1L
                        val bounded = body?.byteStream()?.use { readBounded(it, HTTP_BODY_MAX_BYTES) }
                        val bytes = bounded?.bytes ?: ByteArray(0)
                        val truncated = (bounded?.truncated == true) || declared > HTTP_BODY_MAX_BYTES
                        val truncatedNote = if (truncated) {
                            "\n…(тело ответа обрезано: прочитано не более $HTTP_BODY_MAX_BYTES байт" +
                                (if (declared >= 0L) " из $declared" else "") + ")"
                        } else {
                            ""
                        }
                        ToolResult(
                            true,
                            "HTTP ${resp.code}\n${String(bytes, Charsets.UTF_8).take(4000)}$truncatedNote",
                        )
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "download_file"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val url = (arguments["url"] as? JsonPrimitive)?.content.orEmpty()
                    val requestedDestination = (arguments["destination"] as? JsonPrimitive)?.content.orEmpty()
                    val explicitFilename = (arguments["filename"] as? JsonPrimitive)?.content
                    val maxBytesRaw = (arguments["max_bytes"] as? JsonPrimitive)?.content
                    if (url.isBlank()) {
                        throw IllegalArgumentException("Отсутствует обязательный аргумент: url")
                    }
                    // No (or blank) destination → save to the public «Загрузки» folder by default.
                    // An explicitly provided path always wins over this default.
                    val destination = requestedDestination.trim().ifBlank { DEFAULT_DOWNLOAD_DIR }

                    val maxBytes = when {
                        maxBytesRaw.isNullOrBlank() -> DEFAULT_MAX_BYTES
                        else -> maxBytesRaw.trim().toLongOrNull()
                            ?: throw IllegalArgumentException("Некорректный max_bytes: «$maxBytesRaw».")
                    }
                    if (maxBytes <= 0L) {
                        throw IllegalArgumentException("max_bytes должен быть положительным: «$maxBytesRaw».")
                    }

                    // `destination` is a DIRECTORY, not the target file path.
                    val destDir = resolveSafePath(context.effectiveToolRoot(), destination)
                    if (destDir.exists() && !destDir.isDirectory) {
                        throw IllegalArgumentException("Путь назначения не является директорией: «$destination».")
                    }
                    if (!destDir.isDirectory && !destDir.mkdirs() && !destDir.isDirectory) {
                        throw IllegalArgumentException("Не удалось создать директорию назначения: «$destination».")
                    }

                    val request = Request.Builder().url(url).build()

                    okHttpClient.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw IOException("HTTP ${resp.code}")
                        }
                        val body = resp.body ?: throw IOException("Пустой ответ сервера")
                        val contentLength = body.contentLength()
                        val contentDisposition = resp.header("Content-Disposition")

                        val name = resolveFileName(
                            explicit = explicitFilename,
                            contentDisposition = contentDisposition,
                            url = url,
                        )
                        val target = uniqueTarget(destDir, name)
                        val temp = File(destDir, ".download-${System.nanoTime()}.part")

                        var written = 0L
                        try {
                            body.byteStream().use { input ->
                                FileOutputStream(temp).use { output ->
                                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read == -1) break
                                        written += read
                                        if (written > maxBytes) {
                                            throw IOException(
                                                "Файл превышает лимит в $maxBytes байт (прочитано не менее $written).",
                                            )
                                        }
                                        output.write(buffer, 0, read)
                                    }
                                    output.flush()
                                    output.fd.sync()
                                }
                            }
                        } catch (e: Exception) {
                            temp.delete()
                            throw e
                        }

                        // `File.renameTo` is atomic on the same filesystem —
                        // https://developer.android.com/reference/java/io/File#renameTo(java.io.File)
                        if (!temp.renameTo(target)) {
                            temp.delete()
                            throw IOException("Не удалось сохранить файл «${target.name}» в директории назначения.")
                        }

                        if (!target.exists() || target.length() <= 0L) {
                            target.delete()
                            throw IOException("Проверка не пройдена: файл «${target.name}» не создан или пуст.")
                        }
                        if (contentLength >= 0L && contentLength != written) {
                            target.delete()
                            throw IOException(
                                "Проверка не пройдена: сервер заявил $contentLength байт, записано $written.",
                            )
                        }

                        ToolResult(true, "Скачано: ${target.absolutePath} ($written байт)")
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
    )
}

/**
 * Resolves the download file name: explicit argument → `Content-Disposition` → URL path
 * segment, always sanitized. Falls back to `"download"` when nothing usable is found.
 */
private fun resolveFileName(explicit: String?, contentDisposition: String?, url: String): String =
    sanitizeFileName(explicit)
        ?: sanitizeFileName(filenameFromContentDisposition(contentDisposition))
        ?: sanitizeFileName(filenameFromUrl(url))
        ?: "download"

/**
 * Parses a `Content-Disposition` header per RFC 6266: prefers `filename*=UTF-8''…`
 * (RFC 5987 percent-encoded) over the plain `filename="…"` parameter.
 * https://www.rfc-editor.org/rfc/rfc6266
 */
private fun filenameFromContentDisposition(header: String?): String? {
    if (header.isNullOrBlank()) return null
    val parts = header.split(';').map { it.trim() }
    parts.firstOrNull { it.startsWith("filename*=", ignoreCase = true) }?.let { part ->
        val raw = part.substringAfter('=').trim().trim('"')
        val encoded = raw.substringAfter("''", raw)
        try {
            return URLDecoder.decode(encoded, "UTF-8")
        } catch (_: Exception) {
            return null
        }
    }
    parts.firstOrNull { it.startsWith("filename=", ignoreCase = true) }?.let { part ->
        val value = part.substringAfter('=').trim().trim('"')
        return value.ifBlank { null }
    }
    return null
}

/** Last path segment of [url], or `null` when the URL has no usable segment. */
private fun filenameFromUrl(url: String): String? =
    url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.ifBlank { null }

/**
 * Returns a non-existing [File] in [dir] named [name]; if the name is taken, appends
 * `" (N)"` before the extension instead of overwriting.
 */
private fun uniqueTarget(dir: File, name: String): File {
    val candidate = File(dir, name)
    if (!candidate.exists()) return candidate
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val extension = if (dot > 0) name.substring(dot) else ""
    var index = 1
    var next = File(dir, "$base ($index)$extension")
    while (next.exists()) {
        index++
        next = File(dir, "$base ($index)$extension")
    }
    return next
}
