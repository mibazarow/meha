package com.andharness.ru.data.tools.wallpaper


/**
 * Одна категория обоев. Соответствие флагам `WallpaperManager` задаётся в обработчике:
 * [HOME] -> `WallpaperManager.FLAG_SYSTEM`, [LOCK] -> `WallpaperManager.FLAG_LOCK`.
 * Значения флагов (API 24+): `FLAG_SYSTEM = 1`, `FLAG_LOCK = 2`.
 * Документация: https://developer.android.com/reference/android/app/WallpaperManager
 */
enum class WallpaperPart { HOME, LOCK }
