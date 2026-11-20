package com.andharness.ru.domain.license

/**
 * Чистое решение о том, принял ли пользователь актуальную версию лицензионного соглашения.
 *
 * Логика вынесена из `LicenseStore` (который завязан на Android `SharedPreferences`) в обычный
 * Kotlin-объект, чтобы её можно было проверить JVM-тестом без Android-рантайма.
 *
 * Правило: согласие «принято», если сохранённая версия не меньше текущей. Это же правило
 * обеспечивает повторный показ экрана после выпуска новой версии текста (см. п. 8 текста).
 */
object LicenseAcceptance {

    /** Значение «согласие ещё не дано» (в prefs отсутствует сохранённая версия). */
    const val NO_VERSION: Int = 0

    /**
     * @param storedVersion сохранённая версия принятого соглашения ([NO_VERSION] — не принято).
     * @param currentVersion текущая версия текста, которую нужно принять.
     */
    fun isAccepted(storedVersion: Int, currentVersion: Int): Boolean =
        storedVersion >= currentVersion
}
