package com.andharness.ru.domain.assist.overlay

/**
 * Android-free decisions of the floating-assistant («Плавающий помощник») feature.
 *
 * Kept outside the Service/ViewModel so the rules that actually matter are JVM-testable and
 * live in exactly one place:
 *  1. what a toggle flip means ([decideToggle]) — enabling REQUIRES the
 *     `SYSTEM_ALERT_WINDOW` grant, and without it the toggle must stay OFF and the user is sent to
 *     the system screen instead;
 *  2. whether the bubble may be shown at all ([canShowBubble]) — it needs three independent
 *     approvals: the feature flag, the accepted EULA and the overlay permission;
 *  3. what a fresh app process should do with a persisted ON switch ([decideStartup]) — the flag
 *     survives process death but the service does not, so a restart must either re-raise the
 *     bubble or clear the stale flag (BUG-030).
 *
 * Official references:
 * - `Settings.canDrawOverlays(Context)` / `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` (API 23+) —
 *   https://developer.android.com/reference/android/provider/Settings#canDrawOverlays(android.content.Context)
 *   https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_OVERLAY_PERMISSION
 * - `SYSTEM_ALERT_WINDOW` —
 *   https://developer.android.com/reference/android/Manifest.permission#SYSTEM_ALERT_WINDOW
 */
object OverlayAssistantPolicy {

    /** What the settings toggle should do for a requested value. */
    sealed interface ToggleDecision {
        /** Overlay permission is granted: persist `true` and start the foreground service. */
        data object Enable : ToggleDecision

        /** The user turned it off: persist `false` and stop the foreground service. */
        data object Disable : ToggleDecision

        /**
         * The user asked to turn it on but `SYSTEM_ALERT_WINDOW` is not granted: keep the flag
         * OFF and open `ACTION_MANAGE_OVERLAY_PERMISSION` so the user can grant it.
         */
        data object RequestPermission : ToggleDecision
    }

    fun decideToggle(desired: Boolean, hasOverlayPermission: Boolean): ToggleDecision = when {
        !desired -> ToggleDecision.Disable
        hasOverlayPermission -> ToggleDecision.Enable
        else -> ToggleDecision.RequestPermission
    }

    /**
     * What the app-process startup reconciliation should do with the persisted switch.
     *
     * The switch lives in [com.andharness.ru.data.assist.OverlayAssistantStore], i.e. it survives a
     * process kill, but the foreground service that actually draws the bubble does not. Without
     * this reconciliation a killed/restarted process leaves the toggle ON while nothing is on
     * screen (BUG-030). The rule is deliberately the same [canShowBubble] gate the service applies,
     * so the toggle and the bubble can never disagree:
     *  - flag off → nothing to do;
     *  - flag on and all three approvals hold → (re)start the service idempotently;
     *  - flag on but a gate is missing (EULA no longer accepted, grant revoked) → clear the flag so
     *    the switch stops claiming the feature is on.
     */
    sealed interface StartupDecision {
        /** Restart the foreground service (idempotent — a running instance re-shows its bubble). */
        data object StartService : StartupDecision

        /** The persisted flag is stale: write it back to `false` so the UI reflects reality. */
        data object DisableFlag : StartupDecision

        /** The feature is off; startup must not touch it. */
        data object NoAction : StartupDecision
    }

    fun decideStartup(
        enabled: Boolean,
        eulaAccepted: Boolean,
        hasOverlayPermission: Boolean,
    ): StartupDecision = when {
        !enabled -> StartupDecision.NoAction
        canShowBubble(
            enabled = true,
            eulaAccepted = eulaAccepted,
            hasOverlayPermission = hasOverlayPermission,
        ) -> StartupDecision.StartService
        else -> StartupDecision.DisableFlag
    }

    /**
     * The bubble is shown only when the user enabled the feature, the EULA is accepted (the same
     * gate `MainActivity` applies to every other inbound entry point) and the OS grant is present.
     * The permission can be revoked in system settings at any time, so this is re-checked on start.
     */
    fun canShowBubble(
        enabled: Boolean,
        eulaAccepted: Boolean,
        hasOverlayPermission: Boolean,
    ): Boolean = enabled && eulaAccepted && hasOverlayPermission
}
