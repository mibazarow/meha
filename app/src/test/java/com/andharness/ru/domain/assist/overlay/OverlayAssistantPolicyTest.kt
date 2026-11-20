package com.andharness.ru.domain.assist.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [OverlayAssistantPolicy] — the toggle→permission rule and the bubble gate.
 *
 * They lock in the honest behaviour: enabling without `SYSTEM_ALERT_WINDOW` never turns the feature
 * on (it asks for the permission instead), and the bubble is shown only when all three approvals
 * line up.
 */
class OverlayAssistantPolicyTest {

    @Test
    fun enablingWithPermissionEnables() {
        assertEquals(
            OverlayAssistantPolicy.ToggleDecision.Enable,
            OverlayAssistantPolicy.decideToggle(desired = true, hasOverlayPermission = true),
        )
    }

    @Test
    fun enablingWithoutPermissionAsksForIt() {
        assertEquals(
            OverlayAssistantPolicy.ToggleDecision.RequestPermission,
            OverlayAssistantPolicy.decideToggle(desired = true, hasOverlayPermission = false),
        )
    }

    @Test
    fun disablingAlwaysDisablesEvenWithoutPermission() {
        assertEquals(
            OverlayAssistantPolicy.ToggleDecision.Disable,
            OverlayAssistantPolicy.decideToggle(desired = false, hasOverlayPermission = true),
        )
        assertEquals(
            OverlayAssistantPolicy.ToggleDecision.Disable,
            OverlayAssistantPolicy.decideToggle(desired = false, hasOverlayPermission = false),
        )
    }

    @Test
    fun bubbleNeedsAllThreeApprovals() {
        assertTrue(OverlayAssistantPolicy.canShowBubble(true, true, true))
        assertFalse("feature off", OverlayAssistantPolicy.canShowBubble(false, true, true))
        assertFalse("EULA not accepted", OverlayAssistantPolicy.canShowBubble(true, false, true))
        assertFalse("grant revoked", OverlayAssistantPolicy.canShowBubble(true, true, false))
        assertFalse(OverlayAssistantPolicy.canShowBubble(false, false, false))
    }

    // -----------------------------------------------------------------------------------------
    // BUG-030: process-start reconciliation of the persisted switch.
    // -----------------------------------------------------------------------------------------

    @Test
    fun startupWithFlagOffDoesNothing() {
        assertEquals(
            OverlayAssistantPolicy.StartupDecision.NoAction,
            OverlayAssistantPolicy.decideStartup(
                enabled = false,
                eulaAccepted = true,
                hasOverlayPermission = true,
            ),
        )
    }

    @Test
    fun startupRestartsTheServiceWhenEveryApprovalHolds() {
        assertEquals(
            OverlayAssistantPolicy.StartupDecision.StartService,
            OverlayAssistantPolicy.decideStartup(
                enabled = true,
                eulaAccepted = true,
                hasOverlayPermission = true,
            ),
        )
    }

    @Test
    fun startupClearsAStaleFlagWhenTheGrantIsGone() {
        assertEquals(
            OverlayAssistantPolicy.StartupDecision.DisableFlag,
            OverlayAssistantPolicy.decideStartup(
                enabled = true,
                eulaAccepted = true,
                hasOverlayPermission = false,
            ),
        )
    }

    @Test
    fun startupClearsAStaleFlagWhenTheEulaNoLongerApplies() {
        assertEquals(
            OverlayAssistantPolicy.StartupDecision.DisableFlag,
            OverlayAssistantPolicy.decideStartup(
                enabled = true,
                eulaAccepted = false,
                hasOverlayPermission = true,
            ),
        )
    }

    /**
     * Invariant: startup may only start the service in exactly the cases where the bubble is
     * permitted. If this ever drifts, the toggle could claim ON while the service immediately stops
     * itself (or vice versa).
     */
    @Test
    fun startupStartsTheServiceExactlyWhenTheBubbleIsPermitted() {
        for (enabled in listOf(true, false)) {
            for (eula in listOf(true, false)) {
                for (grant in listOf(true, false)) {
                    val decision = OverlayAssistantPolicy.decideStartup(enabled, eula, grant)
                    val permitted = OverlayAssistantPolicy.canShowBubble(enabled, eula, grant)
                    assertEquals(
                        "enabled=$enabled eula=$eula grant=$grant",
                        permitted,
                        decision == OverlayAssistantPolicy.StartupDecision.StartService,
                    )
                }
            }
        }
    }
}
