package com.andharness.ru.data.tools.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.andharness.ru.data.tools.allowedToolRoots
import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Жёсткий лимит скачиваемой картинки: 32 МиБ (честно отклоняем всё большее). */
private const val MAX_DOWNLOAD_BYTES = 32L * 1024 * 1024

/** Полный таймаут загрузки (connect + чтение тела): не даём зависнуть на «молчащем» сервере. */
private const val DOWNLOAD_TIMEOUT_SEC = 60L

/** Буфер потоковой записи тела ответа на диск. */
private const val COPY_BUFFER_SIZE = 8 * 1024

/** Префикс временного файла загрузки в cacheDir приложения. */
private const val DOWNLOAD_TEMP_PREFIX = "wallpaper-"

/**
 * Строит [ToolHandler] для инструмента [wallpaper_set] — установка картинки на обои.
 *
 * Документация:
 * - `WallpaperManager` (`getInstance`, `setBitmap`, `FLAG_SYSTEM`, `FLAG_LOCK`, `isSetWallpaperAllowed`,
 *   `getDesiredMinimumWidth/Height`):
 *   https://developer.android.com/reference/android/app/WallpaperManager
 * - `BitmapFactory.decodeFile(String, Options)`, `Options.inJustDecodeBounds`, `Options.outWidth` /
 *   `outHeight`, `Options.inSampleSize`:
 *   https://developer.android.com/reference/android/graphics/BitmapFactory.Options
 * - Эффективное декодирование больших картинок (подвыборка + дожатие):
 *   https://developer.android.com/topic/performance/graphics/load-bitmap
 * - `Bitmap.createScaledBitmap(Bitmap, int, int, boolean)`:
 *   https://developer.android.com/reference/android/graphics/Bitmap#createScaledBitmap(android.graphics.Bitmap,%20int,%20int,%20boolean)
 * - Скачивание тела ответа потоком и таймауты OkHttp:
 *   https://square.github.io/okhttp/recipes/
 *
 * Замечание про OEM: `setBitmap` может вернуться без исключения, но часть вендорных прошивок
 * (MIUI, EMUI и т.п.) игнорирует стороннюю установку обоев экрана блокировки. Проверить это
 * программно нельзя — инструмент сообщает только о том, что системный вызов принят.
 */
class WallpaperToolsHandler(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "wallpaper_set"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                var downloaded: File? = null
                var bitmap: Bitmap? = null
                try {
                    val rawTarget = arguments.str("target")
                    val target = parseWallpaperTarget(rawTarget)
                    if (target == null) {
                        return@withContext ToolResult(
                            false,
                            "Некорректный target: «$rawTarget». Допустимо: home, lock, both.",
                        )
                    }

                    // Ровно один источник: path или url.
                    val source = when (
                        val selected = selectWallpaperSource(arguments.str("path"), arguments.str("url"))
                    ) {
                        is WallpaperSource.Invalid -> return@withContext ToolResult(false, selected.message)
                        else -> selected
                    }

                    val wallpaperManager = WallpaperManager.getInstance(context)
                    if (!wallpaperManager.isSetWallpaperAllowed) {
                        return@withContext ToolResult(
                            false,
                            "Система запрещает установку обоев этому приложению (isSetWallpaperAllowed = false).",
                        )
                    }

                    val sourceFile: File = when (source) {
                        is WallpaperSource.LocalPath -> {
                            val file = try {
                                resolveSafePath(context.allowedToolRoots(), source.path)
                            } catch (e: IllegalArgumentException) {
                                return@withContext ToolResult(false, e.message ?: "Некорректный путь.")
                            }
                            validateLocalWallpaperFile(file)?.let {
                                return@withContext ToolResult(false, it)
                            }
                            file
                        }
                        is WallpaperSource.RemoteUrl -> {
                            val url = source.url.toHttpUrlOrNull()
                                ?: return@withContext ToolResult(
                                    false,
                                    "Некорректная ссылка: «${source.url}».",
                                )
                            val temp = try {
                                downloadToTempFile(url)
                            } catch (e: Exception) {
                                Log.e(TAG, "wallpaper_set download failed", e)
                                return@withContext ToolResult(false, "Не удалось скачать картинку: ${e.message}")
                            }
                            downloaded = temp
                            temp
                        }
                        is WallpaperSource.Invalid ->
                            return@withContext ToolResult(false, source.message)
                    }

                    val (screenWidth, screenHeight) = wallpaperTargetSize(wallpaperManager)
                    val decoded = decodeDownscaled(sourceFile, screenWidth, screenHeight)
                    if (decoded == null) {
                        return@withContext ToolResult(
                            false,
                            "Не удалось распознать изображение: «${sourceFile.name}» " +
                                "(не картинка, формат не поддержан или файл повреждён).",
                        )
                    }
                    bitmap = decoded

                    // Флаги: HOME -> FLAG_SYSTEM, LOCK -> FLAG_LOCK, BOTH -> оба (FLAG_SYSTEM | FLAG_LOCK).
                    // setBitmap(Bitmap, Rect, boolean, int) требует SET_WALLPAPER и бросает IOException /
                    // SecurityException при ошибке — их перевод в честный текст ниже.
                    // https://developer.android.com/reference/android/app/WallpaperManager
                    val which = wallpaperParts(target).fold(0) { flags, part ->
                        flags or when (part) {
                            WallpaperPart.HOME -> WallpaperManager.FLAG_SYSTEM
                            WallpaperPart.LOCK -> WallpaperManager.FLAG_LOCK
                        }
                    }
                    // visibleCropHint = null: система сама кадрирует картинку под обои.
                    wallpaperManager.setBitmap(decoded, null, true, which)

                    ToolResult(
                        true,
                        "Обои установлены (${wallpaperTargetDescription(target)}, " +
                            "${decoded.width}×${decoded.height})",
                    )
                } catch (e: SecurityException) {
                    Log.e(TAG, "wallpaper_set denied", e)
                    ToolResult(false, "Не удалось установить обои: нет разрешения SET_WALLPAPER (${e.message}).")
                } catch (e: Exception) {
                    Log.e(TAG, "wallpaper_set failed", e)
                    ToolResult(false, "Не удалось установить обои: ${e.message}")
                } finally {
                    bitmap?.recycle()
                    downloaded?.delete()
                }
            }
        },
    )

    /**
     * Скачивает картинку в временный файл в [Context.getCacheDir] с жёстким лимитом
     * [MAX_DOWNLOAD_BYTES] и полным таймаутом [DOWNLOAD_TIMEOUT_SEC]. Тело читается потоком,
     * поэтому в память целиком не попадает. Ссылка: https://square.github.io/okhttp/recipes/
     */
    private fun downloadToTempFile(url: okhttp3.HttpUrl): File {
        val temp = File(context.cacheDir, "$DOWNLOAD_TEMP_PREFIX${System.nanoTime()}.img")
        val client = okHttpClient.newBuilder()
            .callTimeout(DOWNLOAD_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Пустой ответ сервера")
                val declared = body.contentLength()
                if (declared > MAX_DOWNLOAD_BYTES) {
                    throw IOException("файл слишком большой: заявлено $declared байт, лимит $MAX_DOWNLOAD_BYTES.")
                }
                var written = 0L
                body.byteStream().use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            written += read
                            if (written > MAX_DOWNLOAD_BYTES) {
                                throw IOException(
                                    "файл превышает лимит $MAX_DOWNLOAD_BYTES байт " +
                                        "(прочитано не менее $written).",
                                )
                            }
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
        if (!temp.exists() || temp.length() <= 0L) {
            temp.delete()
            throw IOException("сервер вернул пустой файл.")
        }
        return temp
    }

    /**
     * Размер, под который стоит уменьшать картинку. Сначала спрашиваем у системы желаемый минимум
     * обоев ([WallpaperManager.getDesiredMinimumWidth] / `getDesiredMinimumHeight`), при нуле —
     * берём размеры экрана. Ссылка: https://developer.android.com/reference/android/app/WallpaperManager
     */
    private fun wallpaperTargetSize(wallpaperManager: WallpaperManager): Pair<Int, Int> {
        val desiredWidth = wallpaperManager.desiredMinimumWidth
        val desiredHeight = wallpaperManager.desiredMinimumHeight
        if (desiredWidth > 0 && desiredHeight > 0) return desiredWidth to desiredHeight
        val metrics = context.resources.displayMetrics
        val width = if (metrics.widthPixels > 0) metrics.widthPixels else FALLBACK_SCREEN_WIDTH
        val height = if (metrics.heightPixels > 0) metrics.heightPixels else FALLBACK_SCREEN_HEIGHT
        return width to height
    }

    /**
     * Декодирует картинку с подвыборкой ([calculateInSampleSize]) и дожимает её до рамки экрана
     * ([fitWithin] + `Bitmap.createScaledBitmap`) — чтобы не словить OOM на больших фото.
     * Возвращает `null`, если файл не является декодируемым изображением.
     * `inJustDecodeBounds` не выделяет память и лишь заполняет `outWidth`/`outHeight`
     * (`-1` при ошибке декодирования).
     * Ссылки:
     * - https://developer.android.com/reference/android/graphics/BitmapFactory.Options
     * - https://developer.android.com/topic/performance/graphics/load-bitmap
     */
    private fun decodeDownscaled(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null

        val (width, height) = fitWithin(decoded.width, decoded.height, reqWidth, reqHeight)
        if (width == decoded.width && height == decoded.height) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, width, height, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private companion object {
        const val TAG = "WallpaperToolsHandler"
        const val FALLBACK_SCREEN_WIDTH = 1080
        const val FALLBACK_SCREEN_HEIGHT = 1920
    }
}
