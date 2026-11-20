package com.andharness.ru.data.tools.system

/**
 * Pure decision + message helpers for the [wifi_set] / [bluetooth_set] tools.
 *
 * Platform reality (official Android docs, no guessing):
 * - `WifiManager.setWifiEnabled(boolean)` is deprecated (API 29) and a silent no-op for normal
 *   apps on Android Q (API 29) and above:
 *   https://developer.android.com/reference/android/net/wifi/WifiManager#setWifiEnabled(boolean)
 * - `BluetoothAdapter.enable()` / `disable()` is deprecated (API 33) and blocked for normal apps
 *   on Android 12 (API 31) and above:
 *   https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#enable()
 *
 * So the programmatic radio toggle is only real on older Android. On newer Android the honest UX
 * is: refuse (never claim success), explain why, tell the user exactly what to do, and open the
 * matching system settings panel. Everything here is framework-free so it is unit-testable on the
 * JVM.
 */
internal object WifiBluetoothToggle {

    enum class Kind { WIFI, BLUETOOTH }

    // Programmatic toggle cutoffs (inclusive upper bound is one below):
    // - Wi-Fi: setWifiEnabled stops working for normal apps at Android 10 (API 29).
    // - Bluetooth: enable()/disable() is blocked at Android 12 (API 31).
    private const val WIFI_CUTOFF = 29
    private const val BT_CUTOFF = 31

    /** True when the platform still lets a normal app toggle the radio programmatically. */
    fun toggleAllowed(kind: Kind, sdkInt: Int): Boolean = when (kind) {
        Kind.WIFI -> sdkInt < WIFI_CUTOFF
        Kind.BLUETOOTH -> sdkInt < BT_CUTOFF
    }

    /**
     * Decide success purely from desired vs. actually-read state. We never report a nominal "done"
     * that did not actually happen — success requires the real state to match the request.
     */
    fun succeeded(desiredOn: Boolean, actualOn: Boolean): Boolean = desiredOn == actualOn

    /**
     * Honest, actionable message for the platform-blocked case. Never claims success, explains why
     * the API is unavailable, tells the user exactly what to do, and notes whether the settings
     * panel was opened for them.
     */
    fun disallowedMessage(kind: Kind, desiredOn: Boolean, panelOpened: Boolean): String {
        val verb = if (desiredOn) "включение" else "выключение"
        val (name, path) = when (kind) {
            Kind.WIFI -> "Wi-Fi" to "Настройки → Wi-Fi"
            Kind.BLUETOOTH -> "Bluetooth" to "Настройки → Bluetooth"
        }
        val panelHint = if (panelOpened)
            " Я уже открыл соответствующую панель настроек."
        else
            " Откройте её вручную."
        return "Программное $verb $name недоступно на этой версии Android: система запрещает " +
            "обычным приложениям менять $name напрямую (это делается в системных настройках)." +
            "$panelHint Действие: переключить $name — $path."
    }
}
