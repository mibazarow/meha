package com.andharness.ru.data.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the pure hint→field mapping ([AutofillHints]). They cover the Android hint
 * constants, the no-hint fallback and the "hints are authoritative" rule; the Android service that
 * consumes this helper is not exercised here.
 */
class AutofillHintsTest {

    @Test
    fun emailHintsMapToEmail() {
        assertEquals(AutofillField.EMAIL, AutofillHints.fieldForHints(arrayOf("emailAddress")))
        assertEquals(AutofillField.EMAIL, AutofillHints.fieldForHints(arrayOf("email")))
    }

    @Test
    fun phoneHintsMapToPhone() {
        for (hint in listOf("phone", "phoneNumber", "phoneNational", "tel", "tel-national")) {
            assertEquals(
                "hint '$hint' should map to PHONE",
                AutofillField.PHONE,
                AutofillHints.fieldForHints(arrayOf(hint)),
            )
        }
    }

    @Test
    fun nameHintsMapToName() {
        for (hint in listOf("name", "personName", "personFullName", "PERSON_FULL_NAME", "full-name")) {
            assertEquals(
                "hint '$hint' should map to NAME",
                AutofillField.NAME,
                AutofillHints.fieldForHints(arrayOf(hint)),
            )
        }
    }

    @Test
    fun unknownOrAbsentHintsYieldNone() {
        assertNull(AutofillHints.fieldForHints(arrayOf("notApplicable")))
        assertNull(AutofillHints.fieldForHints(emptyArray()))
        assertNull(AutofillHints.fieldForHints(null))
    }

    @Test
    fun declaredHintsAreAuthoritativeAndBlockTheTextFallback() {
        // The app explicitly typed this as a username; the label text must not override it.
        assertNull(AutofillHints.match(arrayOf("username"), "e-mail"))
    }

    @Test
    fun noHintsFallBackToLabelText() {
        assertNull(AutofillHints.fieldForHints(null))
        assertEquals(AutofillField.PHONE, AutofillHints.match(null, "Телефон"))
        assertEquals(AutofillField.EMAIL, AutofillHints.match(emptyArray(), "e-mail"))
        assertEquals(AutofillField.NAME, AutofillHints.match(null, "ФИО"))
    }

    @Test
    fun absentOrUnrelatedTextYieldsNone() {
        assertNull(AutofillHints.match(null, null))
        assertNull(AutofillHints.match(null, "Комментарий"))
        assertNull(AutofillHints.match(null, "   "))
    }

    @Test
    fun usernameLikeLabelsAreIgnored() {
        assertNull(AutofillHints.match(null, "Имя пользователя"))
        assertNull(AutofillHints.match(null, "Username"))
    }

    @Test
    fun firstMatchingHintWins() {
        assertEquals(
            AutofillField.EMAIL,
            AutofillHints.match(arrayOf("emailAddress", "name"), null),
        )
        assertEquals(
            AutofillField.NAME,
            AutofillHints.match(arrayOf("name", "phoneNumber"), null),
        )
    }
}
