package com.andharness.ru.domain.ui

/**
 * Pure mapping behind `key_event`: turns the requested key name (and the device API level) into
 * either a global action to perform or an honest rejection.
 *
 * It is deliberately free of Android imports so the mapping — including the screenshot API-level
 * guard — is unit-testable on the JVM; [com.andharness.ru.data.tools.ui.UiActionEngine] resolves
 * each [GlobalAction] to the matching `AccessibilityService.GLOBAL_ACTION_*` constant.
 *
 * Official references:
 * - `AccessibilityService.performGlobalAction`:
 *   https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#performGlobalAction(int)
 * - `GLOBAL_ACTION_LOCK_SCREEN` (added in API 28):
 *   https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#GLOBAL_ACTION_LOCK_SCREEN
 * - `GLOBAL_ACTION_TAKE_SCREENSHOT` (public for third-party accessibility services from Android 14):
 *   https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#GLOBAL_ACTION_TAKE_SCREENSHOT
 */
internal object KeyEventMapper {

    /**
     * `GLOBAL_ACTION_TAKE_SCREENSHOT` is only offered to third-party accessibility services from
     * Android 14 (API 34); on older platforms the request is rejected honestly instead of being
     * dispatched and silently ignored.
     */
    const val SCREENSHOT_MIN_SDK = 34

    /** The subset of `AccessibilityService.GLOBAL_ACTION_*` that `key_event` exposes. */
    enum class GlobalAction {
        BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK_SCREEN, SCREENSHOT
    }

    /** Mapping outcome: dispatch [action], or fail with an honest Russian [message]. */
    sealed interface Resolution {
        data class Send(val action: GlobalAction) : Resolution
        data class Reject(val message: String) : Resolution
    }

    private const val SUPPORTED =
        "back | home | recents | notifications | quick_settings | lock | screenshot"

    /** Resolves [key] (already user-facing text) for a device running API level [sdkInt]. */
    fun resolve(key: String, sdkInt: Int): Resolution = when (key.trim().lowercase()) {
        "back", "назад" -> Resolution.Send(GlobalAction.BACK)
        "home", "домой" -> Resolution.Send(GlobalAction.HOME)
        "recents", "недавние" -> Resolution.Send(GlobalAction.RECENTS)
        "notifications", "шторка" -> Resolution.Send(GlobalAction.NOTIFICATIONS)
        "quick_settings" -> Resolution.Send(GlobalAction.QUICK_SETTINGS)
        "lock", "блокировка", "заблокировать" -> Resolution.Send(GlobalAction.LOCK_SCREEN)
        "screenshot", "скриншот" ->
            if (sdkInt >= SCREENSHOT_MIN_SDK) {
                Resolution.Send(GlobalAction.SCREENSHOT)
            } else {
                Resolution.Reject(
                    "Клавиша «screenshot» (снимок экрана через спецвозможности) доступна с " +
                        "Android 14 (API 34); на этом устройстве API $sdkInt."
                )
            }
        else -> Resolution.Reject("key: $SUPPORTED.")
    }
}
