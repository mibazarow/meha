package com.andharness.ru.domain.diagnostics

/**
 * Pure Kotlin mapping of `android.app.ApplicationExitInfo` reason codes to Russian labels
 * (update.md idea #19, "самонаблюдаемость").
 *
 * The integer values mirror the framework constants exactly; they are declared here (instead of
 * referencing `ApplicationExitInfo.REASON_*`) so the mapping is testable on the JVM without the
 * Android framework and stays stable even on API < 30 where the class does not exist.
 *
 * Official reference (the authoritative source of the values):
 * https://developer.android.com/reference/android/app/ApplicationExitInfo#constants_1
 */
object ExitReasonCatalog {

    const val REASON_UNKNOWN = 0
    const val REASON_EXIT_SELF = 1
    const val REASON_SIGNALED = 2
    const val REASON_LOW_MEMORY = 3
    const val REASON_CRASH = 4
    const val REASON_CRASH_NATIVE = 5
    const val REASON_ANR = 6
    const val REASON_INITIALIZATION_FAILURE = 7
    const val REASON_PERMISSION_CHANGE = 8
    const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
    const val REASON_USER_REQUESTED = 10
    const val REASON_USER_STOPPED = 11
    const val REASON_DEPENDENCY_DIED = 12
    const val REASON_OTHER = 13
    const val REASON_FREEZER = 14
    const val REASON_PACKAGE_STATE_CHANGE = 15
    const val REASON_PACKAGE_UPDATED = 16

    /** Human label for a reason code; unknown codes are reported honestly, never guessed. */
    fun label(code: Int): String = when (code) {
        REASON_UNKNOWN -> "неизвестно"
        REASON_EXIT_SELF -> "нормальное завершение процесса"
        REASON_SIGNALED -> "убит сигналом"
        REASON_LOW_MEMORY -> "убит системой из-за нехватки памяти"
        REASON_CRASH -> "падение (Java/Kotlin-исключение)"
        REASON_CRASH_NATIVE -> "падение нативного кода"
        REASON_ANR -> "ANR (приложение не отвечало)"
        REASON_INITIALIZATION_FAILURE -> "ошибка инициализации"
        REASON_PERMISSION_CHANGE -> "изменение разрешений"
        REASON_EXCESSIVE_RESOURCE_USAGE -> "убит за чрезмерное потребление ресурсов"
        REASON_USER_REQUESTED -> "завершён по запросу пользователя"
        REASON_USER_STOPPED -> "остановлен пользователем"
        REASON_DEPENDENCY_DIED -> "умер зависимый процесс"
        REASON_OTHER -> "другая причина"
        REASON_FREEZER -> "заморожен системой"
        REASON_PACKAGE_STATE_CHANGE -> "изменение состояния пакета"
        REASON_PACKAGE_UPDATED -> "пакет обновлён"
        else -> "неизвестная причина (код $code)"
    }

    /**
     * True for reasons that describe an actual failure of our own process (as opposed to a
     * deliberate exit, a user action or a system housekeeping kill).
     */
    fun isFailure(code: Int): Boolean = when (code) {
        REASON_CRASH, REASON_CRASH_NATIVE, REASON_ANR, REASON_LOW_MEMORY,
        REASON_INITIALIZATION_FAILURE, REASON_EXCESSIVE_RESOURCE_USAGE -> true
        else -> false
    }

    /** True for exits the user (or the platform on the user's behalf) asked for. */
    fun isUserInitiated(code: Int): Boolean = when (code) {
        REASON_EXIT_SELF, REASON_USER_REQUESTED, REASON_USER_STOPPED -> true
        else -> false
    }

    /** Compact one-line grouping used by the diagnostic summary. */
    fun category(code: Int): String = when {
        isFailure(code) -> "сбой"
        isUserInitiated(code) -> "по воле пользователя"
        else -> "системное событие"
    }
}
