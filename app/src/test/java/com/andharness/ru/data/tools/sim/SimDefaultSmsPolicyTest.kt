package com.andharness.ru.data.tools.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [SimDefaultSmsPolicy] — the honest refusal logic of `sim_set_default_sms`.
 *
 * The tool must never report success: changing the default SMS subscription requires the
 * signature|privileged `MODIFY_PHONE_STATE` permission.
 */
class SimDefaultSmsPolicyTest {

    @Test
    fun parsesAValidTrimmedId() {
        assertEquals(5, SimDefaultSmsPolicy.parseSubscriptionId("5"))
        assertEquals(7, SimDefaultSmsPolicy.parseSubscriptionId(" 7 "))
        assertEquals(0, SimDefaultSmsPolicy.parseSubscriptionId("0"))
    }

    @Test
    fun rejectsMissingOrMalformedIds() {
        assertNull(SimDefaultSmsPolicy.parseSubscriptionId(null))
        assertNull(SimDefaultSmsPolicy.parseSubscriptionId(""))
        assertNull(SimDefaultSmsPolicy.parseSubscriptionId("abc"))
        assertNull(SimDefaultSmsPolicy.parseSubscriptionId("-1"))
        assertNull(SimDefaultSmsPolicy.parseSubscriptionId("-5"))
    }

    @Test
    fun unknownIdMessageNamesTheId() {
        assertTrue(SimDefaultSmsPolicy.unknownIdMessage(42).contains("42"))
    }

    @Test
    fun refusalNamesTheMissingPrivilegeAndNeverClaimsSuccess() {
        val message = SimDefaultSmsPolicy.refusalMessage(3, isDefaultSmsApp = false)
        assertTrue(message.contains(SimDefaultSmsPolicy.MISSING_PRIVILEGE))
        assertTrue(message.contains("3"))
        assertTrue(message.contains("НЕ выполняет изменение"))
        assertTrue(message.contains("Роль SMS-приложения"))
    }

    @Test
    fun defaultSmsAppRoleIsExplicitlyNotEnough() {
        val message = SimDefaultSmsPolicy.refusalMessage(3, isDefaultSmsApp = true)
        assertTrue(message.contains("является SMS-приложением по умолчанию"))
        assertTrue(message.contains("не даёт права"))
    }
}
