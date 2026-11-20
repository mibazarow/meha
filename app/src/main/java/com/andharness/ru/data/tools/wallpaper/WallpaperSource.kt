package com.andharness.ru.data.tools.wallpaper


/** Источник картинки, выбранный из аргументов `path`/`url`. */
sealed interface WallpaperSource {
    /** Локальный файл (резолвится через `resolveSafePath`). */
    data class LocalPath(val path: String) : WallpaperSource

    /** Ссылка: картинку сначала надо скачать (OkHttp). */
    data class RemoteUrl(val url: String) : WallpaperSource

    /** Некорректная комбинация источников; [message] — готовый честный текст для пользователя. */
    data class Invalid(val message: String) : WallpaperSource
}
