package com.andharness.ru.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.andharness.ru.data.assist.OverlayAssistantStore
import com.andharness.ru.data.prefs.LicenseStore
import com.andharness.ru.domain.assist.overlay.OverlayAssistantPolicy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Re-shows the floating bubble after a reboot or a self-update, but ONLY when the user enabled the
 * feature, the EULA is still accepted and the overlay grant survived (it is a system-settings grant,
 * so it does).
 *
 * `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` are on the Android 8+ manifest-exemption list, so they
 * arrive even after process death. On Android 15 an app targeting API 35 may not start certain
 * foreground-service types from a boot receiver (`dataSync`, `camera`, `mediaPlayback`,
 * `phoneCall`, `mediaProjection`, `microphone`); this service uses `specialUse`, which is not in
 * that set, and a boot-time start is additionally one of the documented exemptions from the
 * background FGS-start restrictions.
 *
 * References:
 * - boot-completed broadcast exemptions —
 *   https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions
 * - Android 15 BOOT_COMPLETED FGS restrictions (type list) —
 *   https://developer.android.com/about/versions/15/behavior-changes-15
 * - exemptions from background FGS starts —
 *   https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
 */
@AndroidEntryPoint
class OverlayAssistantBootReceiver : BroadcastReceiver() {

    @Inject lateinit var store: OverlayAssistantStore
    @Inject lateinit var licenseStore: LicenseStore

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val permitted = OverlayAssistantPolicy.canShowBubble(
            enabled = store.isEnabled(),
            eulaAccepted = licenseStore.accepted(LicenseStore.LICENSE_VERSION),
            hasOverlayPermission = Settings.canDrawOverlays(context),
        )
        if (!permitted) {
            Log.i(TAG, "overlay assistant not restarted after $action")
            return
        }
        OverlayAssistantService.start(context)
    }

    private companion object {
        const val TAG = "OverlayAssistant"
    }
}
