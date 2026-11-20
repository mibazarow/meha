package com.andharness.ru.data.tools.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the honest-UX decision logic for [wifi_set] / [bluetooth_set]: which SDK versions still
 * allow a programmatic toggle, the "verify state after attempt" rule, and that the blocked-case
 * message never claims success and stays actionable.
 */
class WifiBluetoothToggleTest {

    @Test
    fun wifiToggleAllowedOnlyBelowApi29() {
        assertTrue(WifiBluetoothToggle.toggleAllowed(WifiBluetoothToggle.Kind.WIFI, 28))
        assertFalse(WifiBluetoothToggle.toggleAllowed(WifiBluetoothToggle.Kind.WIFI, 29))
        assertFalse(WifiBluetoothToggle.toggleAllowed(WifiBluetoothToggle.Kind.WIFI, 36))
    }

    @Test
    fun bluetoothToggleAllowedOnlyBelowApi31() {
        assertTrue(WifiBluetoothToggle.toggleAllowed(WifiBluetoothToggle.Kind.BLUETOOTH, 30))
        assertFalse(WifiBluetoothToggle.toggleAllowed(WifiBluetoothToggle.Kind.BLUETOOTH, 31))
        assertFalse(WifiBluetoothToggle.toggleAllowed(WifiBluetoothToggle.Kind.BLUETOOTH, 36))
    }

    @Test
    fun successRequiresActualStateToMatchDesired() {
        assertTrue(WifiBluetoothToggle.succeeded(desiredOn = true, actualOn = true))
        assertTrue(WifiBluetoothToggle.succeeded(desiredOn = false, actualOn = false))
        assertFalse(WifiBluetoothToggle.succeeded(desiredOn = true, actualOn = false))
        assertFalse(WifiBluetoothToggle.succeeded(desiredOn = false, actualOn = true))
    }

    @Test
    fun disallowedMessageNeverClaimsSuccessAndExplainsWhy() {
        val msg = WifiBluetoothToggle.disallowedMessage(
            WifiBluetoothToggle.Kind.WIFI,
            desiredOn = true,
            panelOpened = true,
        )
        assertFalse("message must not claim success: $msg", msg.lowercase().contains("успех"))
        assertFalse("message must not claim the radio is on: $msg", msg.contains("включён."))
        assertTrue(msg.lowercase().contains("wi-fi"))
        assertTrue(msg.lowercase().contains("настройк"))
        assertTrue("message should say the panel was opened: $msg", msg.contains("открыл"))
    }

    @Test
    fun disallowedMessageReflectsPanelState() {
        val opened = WifiBluetoothToggle.disallowedMessage(
            WifiBluetoothToggle.Kind.BLUETOOTH,
            desiredOn = false,
            panelOpened = true,
        )
        val notOpened = WifiBluetoothToggle.disallowedMessage(
            WifiBluetoothToggle.Kind.BLUETOOTH,
            desiredOn = false,
            panelOpened = false,
        )
        assertTrue(opened.contains("открыл") || opened.contains("Открыл"))
        assertFalse(notOpened.contains("открыл"))
        assertFalse(notOpened.contains("Открыл"))
    }
}
