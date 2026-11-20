package com.andharness.ru.domain.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [VCardWriter]: RFC 2426 output and round-trip through [VCardParser]. */
class VCardWriterTest {

    @Test
    fun writesEnvelopeAndFields() {
        val card = VCardContact(
            fullName = "John Doe",
            givenName = "John",
            familyName = "Doe",
            phones = listOf("+79001112233"),
            emails = listOf("john@example.com"),
        )

        val text = VCardWriter.write(listOf(card))

        assertTrue(text.startsWith("BEGIN:VCARD\r\n"))
        assertTrue(text.endsWith("END:VCARD\r\n"))
        assertTrue(text.contains("VERSION:3.0\r\n"))
        assertTrue(text.contains("FN:John Doe\r\n"))
        assertTrue(text.contains("N:Doe;John;;;\r\n"))
        assertTrue(text.contains("TEL;TYPE=VOICE:+79001112233\r\n"))
        assertTrue(text.contains("EMAIL;TYPE=INTERNET:john@example.com\r\n"))
    }

    @Test
    fun roundTripsThroughParser() {
        val card = VCardContact(
            fullName = "Smith, John",
            givenName = "John",
            familyName = "Smith",
            phones = listOf("111", "222"),
            emails = listOf("a@b.c"),
        )

        val parsed = VCardParser.parse(VCardWriter.write(listOf(card))).contacts.single()

        assertEquals(card.fullName, parsed.fullName)
        assertEquals(card.givenName, parsed.givenName)
        assertEquals(card.familyName, parsed.familyName)
        assertEquals(card.phones, parsed.phones)
        assertEquals(card.emails, parsed.emails)
    }

    @Test
    fun writesMultipleCards() {
        val text = VCardWriter.write(
            listOf(VCardContact(fullName = "Ann"), VCardContact(fullName = "Bob")),
        )

        assertEquals(2, "BEGIN:VCARD".toRegex().findAll(text).count())
        assertEquals(2, "END:VCARD".toRegex().findAll(text).count())
    }

    @Test
    fun omitsBlankOptionalFields() {
        val text = VCardWriter.writeContact(VCardContact(fullName = "Solo"))

        assertTrue(text.contains("FN:Solo\r\n"))
        assertTrue(!text.contains("TEL"))
        assertTrue(!text.contains("EMAIL"))
        assertTrue(!text.contains("\r\nN:"))
    }
}
