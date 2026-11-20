package com.andharness.ru.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.andharness.ru.domain.license.LicenseAcceptance
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Хранит факт принятия лицензионного соглашения (EULA) и версию принятого текста.
 *
 * Обычные (не секретные) [SharedPreferences] — тот же паттерн, что у `ThemeStore`: флаг должен
 * читаться синхронно на старте, до построения первого кадра, чтобы сразу выбрать стартовый экран
 * без мигания. Значение `>= [LICENSE_VERSION]` означает «принято»; после изменения текста
 * достаточно увеличить [LICENSE_VERSION] — экран покажется снова (п. 8 текста).
 *
 * Ссылка (официальная документация):
 * https://developer.android.com/reference/android/content/SharedPreferences
 */
@Singleton
class LicenseStore @Inject constructor(
    @ApplicationContext context: Context
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** Сохранённая версия принятого соглашения или [LicenseAcceptance.NO_VERSION]. */
    fun acceptedVersion(): Int = prefs.getInt(KEY_ACCEPTED_VERSION, LicenseAcceptance.NO_VERSION)

    /** true, если версия [version] (или более новая) уже принята. */
    fun accepted(version: Int): Boolean =
        LicenseAcceptance.isAccepted(acceptedVersion(), version)

    /** Фиксирует принятие версии [version]. */
    fun accept(version: Int) {
        prefs.edit().putInt(KEY_ACCEPTED_VERSION, version).apply()
    }

    companion object {
        /** Версия текста соглашения; увеличивать при существенном изменении условий. */
        const val LICENSE_VERSION: Int = 1

        const val PREFS_FILE: String = "andharness_license"
        private const val KEY_ACCEPTED_VERSION: String = "accepted_version"
    }
}
