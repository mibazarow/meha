package com.andharness.ru.data.tools.wallpaper

import java.io.File
import java.util.Locale

/**
 * Разбирает аргумент `target`. Отсутствие значения (null/пустая строка) — цель по умолчанию
 * [WallpaperTarget.HOME]. Возвращает `null` для нераспознанного значения, чтобы обработчик
 * честно сообщил об ошибке и не поставил обои «куда попало».
 *
 * Все helpers в этом файле — чистый Kotlin без Android API, поэтому проверяются JVM-тестами.
 */
fun parseWallpaperTarget(raw: String?): WallpaperTarget? {
    if (raw.isNullOrBlank()) return WallpaperTarget.HOME
    return when (raw.trim().lowercase(Locale.ROOT)) {
        "home" -> WallpaperTarget.HOME
        "lock" -> WallpaperTarget.LOCK
        "both" -> WallpaperTarget.BOTH
        else -> null
    }
}

/** Какие категории обоев нужно выставить для [target]. */
fun wallpaperParts(target: WallpaperTarget): Set<WallpaperPart> = when (target) {
    WallpaperTarget.HOME -> setOf(WallpaperPart.HOME)
    WallpaperTarget.LOCK -> setOf(WallpaperPart.LOCK)
    WallpaperTarget.BOTH -> setOf(WallpaperPart.HOME, WallpaperPart.LOCK)
}

/** Человеческое название цели для текста результата (русский). */
fun wallpaperTargetDescription(target: WallpaperTarget): String = when (target) {
    WallpaperTarget.HOME -> "рабочий стол"
    WallpaperTarget.LOCK -> "экран блокировки"
    WallpaperTarget.BOTH -> "рабочий стол и экран блокировки"
}

/**
 * Ровно один из `path`/`url` обязателен. Возвращает [WallpaperSource.Invalid] с честным русским
 * текстом, когда не указан ни один источник или указаны оба сразу.
 */
fun selectWallpaperSource(path: String?, url: String?): WallpaperSource {
    val localPath = path?.trim()?.takeIf { it.isNotEmpty() }
    val remoteUrl = url?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        localPath != null && remoteUrl != null ->
            WallpaperSource.Invalid("Укажите только один источник: path или url (не оба сразу).")
        localPath != null -> WallpaperSource.LocalPath(localPath)
        remoteUrl != null -> WallpaperSource.RemoteUrl(remoteUrl)
        else -> WallpaperSource.Invalid(
            "Укажите источник картинки: path (локальный файл) или url (ссылка).",
        )
    }
}

/**
 * Проверяет локальный файл-источник обоев: он должен существовать и быть обычным файлом (не
 * каталогом или устройством). Возвращает `null`, если файл подходит, или готовый честный текст
 * ошибки, которую [wallpaper_set] передаёт пользователю. Размер файла проверяется отдельно —
 * для удалённых источников при загрузке (лимит в обработчике), для локальных — декодер с
 * подвыборкой уже не даст получить OOM.
 */
fun validateLocalWallpaperFile(file: File): String? =
    if (!file.exists()) "Файл не найден: «${file.name}»."
    else if (!file.isFile) "Не файл: «${file.name}»."
    else null

/**
 * Подбирает `BitmapFactory.Options.inSampleSize` — степень двойки, при которой подвыборка ещё
 * не меньше запрошенных размеров. Это документированный приём против OOM при декодировании
 * больших картинок; `inSampleSize == 4` даёт картинку в 1/4 ширины/высоты и 1/16 пикселей.
 * Документация:
 * - https://developer.android.com/reference/android/graphics/BitmapFactory.Options
 * - https://developer.android.com/topic/performance/graphics/load-bitmap
 * Любые неположительные размеры трактуются как «без подвыборки» (1).
 */
fun calculateInSampleSize(outWidth: Int, outHeight: Int, reqWidth: Int, reqHeight: Int): Int {
    if (outWidth <= 0 || outHeight <= 0 || reqWidth <= 0 || reqHeight <= 0) return 1
    var sampleSize = 1
    val halfWidth = outWidth / 2
    val halfHeight = outHeight / 2
    while (halfWidth / sampleSize >= reqWidth && halfHeight / sampleSize >= reqHeight) {
        sampleSize *= 2
    }
    return sampleSize
}

/**
 * Вписывает [width]x[height] в рамку [maxWidth]x[maxHeight] с сохранением пропорций и без
 * увеличения (меньшая картинка возвращается как есть). Итоговые размеры всегда >= 1.
 * Нужен, чтобы после `inSampleSize`-декодирования ещё дожать картинку до размера экрана
 * (второй шаг защиты от OOM: `Bitmap.createScaledBitmap`).
 */
fun fitWithin(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return 1 to 1
    if (maxWidth <= 0 || maxHeight <= 0) return width to height
    if (width <= maxWidth && height <= maxHeight) return width to height
    val scale = minOf(maxWidth.toDouble() / width, maxHeight.toDouble() / height)
    val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
    val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
    return scaledWidth to scaledHeight
}
