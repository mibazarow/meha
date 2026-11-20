package com.andharness.ru.data.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure [AutofillProfileCodec] and the value semantics of [AutofillProfile].
 * `AutofillProfileStore` only adds SharedPreferences I/O on top and is not exercised here.
 */
class AutofillProfileCodecTest {

    @Test
    fun roundTripPreservesFields() {
        val profile = AutofillProfile(fullName = "Иван Иванов", phone = "+7 900 000-00-00", email = "i@e.ru")
        assertEquals(profile, AutofillProfileCodec.decode(AutofillProfileCodec.encode(profile)))
    }

    @Test
    fun corruptOrMissingOrForeignDataDegradesToEmpty() {
        assertTrue(AutofillProfileCodec.decode(null).isEmpty)
        assertTrue(AutofillProfileCodec.decode("").isEmpty)
        assertTrue(AutofillProfileCodec.decode("this is not json").isEmpty)
        assertTrue(AutofillProfileCodec.decode("{\"unexpected\":true}").isEmpty)
        assertTrue(AutofillProfileCodec.decode("\"x\"").isEmpty)
    }

    @Test
    fun unknownFieldsAreIgnoredButKnownOnesKept() {
        val decoded = AutofillProfileCodec.decode("{\"fullName\":\"A\",\"extra\":1}")
        assertEquals("A", decoded.valueFor(AutofillField.NAME))
    }

    @Test
    fun valueForTrimsAndTreatsBlankAsAbsent() {
        val profile = AutofillProfile(fullName = "  Иван  ", phone = "", email = "   ")
        assertEquals("Иван", profile.valueFor(AutofillField.NAME))
        assertNull(profile.valueFor(AutofillField.PHONE))
        assertNull(profile.valueFor(AutofillField.EMAIL))
        assertFalse(profile.isEmpty)
    }

    @Test
    fun isEmptyOnlyWhenEveryFieldIsBlank() {
        assertTrue(AutofillProfile().isEmpty)
        assertTrue(AutofillProfile(fullName = " ", phone = null, email = "").isEmpty)
        assertFalse(AutofillProfile(email = "a@b.c").isEmpty)
    }
}
