package com.andharness.ru.ui.communications

import com.andharness.ru.domain.person.IdentityType
import com.andharness.ru.domain.person.PersonIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeopleFormatTest {

    @Test
    fun noIdentitiesYieldNoHint() {
        assertNull(identityHint(emptyList()))
    }

    @Test
    fun hintJoinsTheFirstTwoHandles() {
        val identities = listOf(
            identity(IdentityType.PHONE, "+7 999 000-00-00"),
            identity(IdentityType.EMAIL, "a@b.ru"),
            identity(IdentityType.MESSENGER, "@third"),
        )
        assertEquals("+7 999 000-00-00 · a@b.ru", identityHint(identities))
    }

    @Test
    fun blankHandlesAreSkipped() {
        val identities = listOf(
            identity(IdentityType.PHONE, "   "),
            identity(IdentityType.PHONE, "+7"),
        )
        assertEquals("+7", identityHint(identities))
    }

    @Test
    fun whitespaceIsTrimmed() {
        assertEquals("+7", identityHint(listOf(identity(IdentityType.PHONE, "  +7  "))))
    }

    private fun identity(type: IdentityType, value: String) = PersonIdentity(
        id = 0L,
        personId = 1L,
        type = type,
        value = value,
        appPackage = "",
        label = null,
    )
}
