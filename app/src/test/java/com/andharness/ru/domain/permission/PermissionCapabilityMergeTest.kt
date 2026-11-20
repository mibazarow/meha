package com.andharness.ru.domain.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The «Все разрешения» screen must not render two identical rows: capabilities split into
 * `READ_`/`WRITE_` permissions are merged, and the subtitle must never repeat the title.
 */
class PermissionCapabilityMergeTest {

    @Test
    fun `merges READ and WRITE permissions of one capability into a single capability`() {
        val capabilities = PermissionRegistry.all().mergeIntoCapabilities()

        val contacts = capabilities.single { it.displayName == "Контакты" }
        assertEquals(
            listOf("android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS"),
            contacts.androidPermissions
        )
    }

    @Test
    fun `every capability title is unique`() {
        val names = PermissionRegistry.all().mergeIntoCapabilities().map { it.displayName }
        assertEquals(names.size, names.toSet().size)
    }

    /**
     * RECEIVE_SMS must be requestable from «Все разрешения» (BUG-006): it collapses into the same
     * «SMS» capability as READ_/SEND_SMS, so one row requests all three and no duplicate appears.
     */
    @Test
    fun `SMS capability includes receive read and send permissions`() {
        val sms = PermissionRegistry.all().mergeIntoCapabilities().single { it.displayName == "SMS" }

        assertEquals(
            listOf(
                "android.permission.READ_SMS",
                "android.permission.SEND_SMS",
                "android.permission.RECEIVE_SMS",
            ),
            sms.androidPermissions
        )
    }

    @Test
    fun `no subtitle repeats its title`() {
        PermissionRegistry.all().mergeIntoCapabilities().forEach { capability ->
            assertNotEquals(capability.displayName, capability.description)
            assertTrue(capability.description.isNotBlank())
        }
    }
}
