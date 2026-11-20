package com.andharness.ru.data.tools.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactGroupsTest {

    private val groups = listOf(
        ContactGroups.GroupRef(1L, "Друзья", "com.google", "me@gmail.com"),
        ContactGroups.GroupRef(2L, "Работа", "com.google", "me@gmail.com"),
        ContactGroups.GroupRef(3L, "Семья", null, null),
    )

    // ---- account resolution ----

    @Test
    fun requestedAccountWinsWhenBothPartsGiven() {
        val result = ContactGroups.resolveGroupAccount(
            requestedType = "com.google",
            requestedName = "user@gmail.com",
            fallbackType = "local",
            fallbackName = "Local",
        )

        assertEquals(
            ContactGroups.AccountResolution.Resolved(
                ContactGroups.Account("com.google", "user@gmail.com"),
            ),
            result,
        )
    }

    @Test
    fun halfSpecifiedAccountIsRejected() {
        val onlyType = ContactGroups.resolveGroupAccount("com.google", null, null, null)
        val onlyName = ContactGroups.resolveGroupAccount(null, "user@gmail.com", null, null)

        assertTrue(onlyType is ContactGroups.AccountResolution.Invalid)
        assertTrue(onlyName is ContactGroups.AccountResolution.Invalid)
    }

    @Test
    fun blankAccountFallsBackToDiscoveredAccount() {
        val result = ContactGroups.resolveGroupAccount("  ", "", "com.google", "me@gmail.com")

        assertEquals(
            ContactGroups.AccountResolution.Resolved(ContactGroups.Account("com.google", "me@gmail.com")),
            result,
        )
    }

    @Test
    fun noAccountAndNoFallbackResolvesToLocal() {
        val result = ContactGroups.resolveGroupAccount(null, null, null, null)

        assertEquals(
            ContactGroups.AccountResolution.Resolved(ContactGroups.Account(null, null)),
            result,
        )
    }

    @Test
    fun requestedAccountIsTrimmed() {
        val result = ContactGroups.resolveGroupAccount(" com.google ", " me@gmail.com ", null, null)

        assertEquals(
            ContactGroups.AccountResolution.Resolved(
                ContactGroups.Account("com.google", "me@gmail.com"),
            ),
            result,
        )
    }

    // ---- group resolution ----

    @Test
    fun resolvesGroupByExplicitId() {
        val result = ContactGroups.resolveGroupId(groupId = "2", title = null, groups = groups)

        assertEquals(ContactGroups.GroupResolution.Resolved(2L), result)
    }

    @Test
    fun rejectsUnknownExplicitId() {
        val result = ContactGroups.resolveGroupId(groupId = "99", title = null, groups = groups)

        assertTrue(result is ContactGroups.GroupResolution.Invalid)
    }

    @Test
    fun rejectsNonNumericId() {
        val result = ContactGroups.resolveGroupId(groupId = "abc", title = null, groups = groups)

        assertTrue(result is ContactGroups.GroupResolution.Invalid)
    }

    @Test
    fun resolvesGroupByTitleCaseInsensitively() {
        val result = ContactGroups.resolveGroupId(groupId = null, title = "друзья", groups = groups)

        assertEquals(ContactGroups.GroupResolution.Resolved(1L), result)
    }

    @Test
    fun missingTitleIsRejectedWithActionableMessage() {
        val result = ContactGroups.resolveGroupId(groupId = null, title = "Нет такой", groups = groups)

        assertTrue(result is ContactGroups.GroupResolution.Invalid)
        assertTrue((result as ContactGroups.GroupResolution.Invalid).message.contains("contact_group_create"))
    }

    @Test
    fun ambiguousTitleMustUseId() {
        val duplicate = groups + ContactGroups.GroupRef(4L, "Работа", "local", "Local")

        val result = ContactGroups.resolveGroupId(groupId = null, title = "работа", groups = duplicate)

        assertTrue(result is ContactGroups.GroupResolution.Invalid)
    }

    @Test
    fun noGroupIdentifierAtAllIsRejected() {
        val result = ContactGroups.resolveGroupId(groupId = null, title = null, groups = groups)

        assertTrue(result is ContactGroups.GroupResolution.Invalid)
    }

    // ---- membership decision ----

    @Test
    fun addingANewMembershipInserts() {
        assertEquals(
            ContactGroups.MembershipDecision.Insert,
            ContactGroups.decideMembership(listOf(2L, 3L), groupId = 1L, remove = false),
        )
    }

    @Test
    fun addingAnExistingMembershipIsIdempotent() {
        assertEquals(
            ContactGroups.MembershipDecision.AlreadyMember,
            ContactGroups.decideMembership(listOf(1L), groupId = 1L, remove = false),
        )
    }

    @Test
    fun removingAnExistingMembershipDeletes() {
        assertEquals(
            ContactGroups.MembershipDecision.Delete,
            ContactGroups.decideMembership(listOf(1L, 3L), groupId = 1L, remove = true),
        )
    }

    @Test
    fun removingANonMembershipIsIdempotent() {
        assertEquals(
            ContactGroups.MembershipDecision.NotMember,
            ContactGroups.decideMembership(listOf(2L, 3L), groupId = 1L, remove = true),
        )
    }

    @Test
    fun decideMembershipOnNoMemberships() {
        assertEquals(
            ContactGroups.MembershipDecision.Insert,
            ContactGroups.decideMembership(emptyList(), groupId = 7L, remove = false),
        )
        assertEquals(
            ContactGroups.MembershipDecision.NotMember,
            ContactGroups.decideMembership(emptyList(), groupId = 7L, remove = true),
        )
    }

    // ---- raw-contact selection ----

    @Test
    fun picksRawContactOfTheGroupsOwnAccount() {
        val rawContacts = listOf(
            ContactGroups.RawContactRef(10L, null, null),
            ContactGroups.RawContactRef(11L, "com.google", "me@gmail.com"),
        )

        val picked = ContactGroups.pickRawContactForGroup(rawContacts, "com.google", "me@gmail.com")

        assertEquals(11L, picked)
    }

    @Test
    fun localGroupPrefersLocalRawContact() {
        val rawContacts = listOf(
            ContactGroups.RawContactRef(10L, "com.google", "me@gmail.com"),
            ContactGroups.RawContactRef(11L, null, null),
        )

        val picked = ContactGroups.pickRawContactForGroup(rawContacts, null, null)

        assertEquals(11L, picked)
    }

    @Test
    fun fallsBackToFirstRawContactWhenAccountCannotBeMatched() {
        val rawContacts = listOf(
            ContactGroups.RawContactRef(10L, "com.google", "me@gmail.com"),
            ContactGroups.RawContactRef(11L, "local", "Local"),
        )

        val picked = ContactGroups.pickRawContactForGroup(rawContacts, "com.android.contacts", "phone")

        assertEquals(10L, picked)
    }

    @Test
    fun noRawContactsYieldsNull() {
        assertNull(ContactGroups.pickRawContactForGroup(emptyList(), "com.google", "me@gmail.com"))
    }
}
