package com.andharness.ru.data.tools.sim

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [EsimStatusFormatter] — the pure, Android-free formatting of `esim_status`,
 * including the honest carrier-privilege failure mapping (the EID must never be fabricated).
 */
class EsimStatusFormatterTest {

    @Test
    fun unsupportedDeviceIsReportedHonestly() {
        val output = EsimStatusFormatter.format(
            EsimStatusInfo(supported = false, enabled = false, eid = null, eidBlockedReason = null, osVersion = null)
        )
        assertTrue(output.contains("eSIM поддерживается: нет"))
        assertTrue(output.contains("EID: недоступно (устройство без eUICC)"))
        assertTrue(output.contains("Версия eUICC OS: недоступно"))
    }

    @Test
    fun enabledDeviceWithCarrierPrivilegesShowsTheEid() {
        val output = EsimStatusFormatter.format(
            EsimStatusInfo(
                supported = true,
                enabled = true,
                eid = "89049032000000000000000000000001",
                eidBlockedReason = null,
                osVersion = "2.2.0",
            )
        )
        assertTrue(output.contains("eSIM включена: да"))
        assertTrue(output.contains("89049032000000000000000000000001"))
        assertTrue(output.contains("Версия eUICC OS: 2.2.0"))
    }

    @Test
    fun missingCarrierPrivilegesMapToAnHonestReasonWithoutFabricatingAnEid() {
        val output = EsimStatusFormatter.format(
            EsimStatusInfo(
                supported = true,
                enabled = true,
                eid = null,
                eidBlockedReason = EsimStatusFormatter.CARRIER_PRIVILEGE_REASON,
                osVersion = null,
            )
        )
        assertTrue(output.contains(EsimStatusFormatter.CARRIER_PRIVILEGE_REASON))
        assertTrue(output.contains("carrier privileges"))
        assertFalse(output.contains("8904"))
    }

    @Test
    fun notReadyEuiccFallsBackToAnHonestMarker() {
        val output = EsimStatusFormatter.format(
            EsimStatusInfo(supported = true, enabled = false, eid = null, eidBlockedReason = null, osVersion = null)
        )
        assertTrue(output.contains("EID: недоступно (eUICC не готов)"))
    }
}
