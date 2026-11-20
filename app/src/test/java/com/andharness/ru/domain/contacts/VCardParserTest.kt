package com.andharness.ru.domain.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [VCardParser]: single and multiple cards, folded lines, TEL/EMAIL values,
 * structured names and malformed cards being counted as skipped instead of producing empty contacts.
 */
class VCardParserTest {

    @Test
    fun parsesSingleCardWithNamePhoneAndEmail() {
        val text = """
            BEGIN:VCARD
            VERSION:3.0
            N:Doe;John;;;
            FN:John Doe
            TEL;TYPE=CELL:+7 911 961-79-98
            EMAIL;TYPE=INTERNET:john@example.com
            END:VCARD
        """.trimIndent()

        val result = VCardParser.parse(text)

        assertEquals(0, result.skipped)
        assertEquals(1, result.contacts.size)
        val card = result.contacts.first()
        assertEquals("John Doe", card.fullName)
        assertEquals("John", card.givenName)
        assertEquals("Doe", card.familyName)
        assertEquals(listOf("+7 911 961-79-98"), card.phones)
        assertEquals(listOf("john@example.com"), card.emails)
    }

    @Test
    fun parsesMultipleCardsInOneFile() {
        val text = "BEGIN:VCARD\r\nFN:Ann\r\nTEL:111\r\nEND:VCARD\r\n" +
            "BEGIN:VCARD\r\nFN:Bob\r\nEMAIL:bob@x.org\r\nEND:VCARD\r\n"

        val result = VCardParser.parse(text)

        assertEquals(2, result.contacts.size)
        assertEquals("Ann", result.contacts[0].fullName)
        assertEquals("Bob", result.contacts[1].fullName)
        assertEquals(0, result.skipped)
    }

    @Test
    fun unfoldsContinuationLines() {
        val text = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:John \r\n Doe\r\nTEL:123\r\nEND:VCARD\r\n"

        val result = VCardParser.parse(text)

        assertEquals(1, result.contacts.size)
        assertEquals("John Doe", result.contacts.first().fullName)
    }

    @Test
    fun derivesNameFromStructuredNWhenFnIsAbsent() {
        val text = "BEGIN:VCARD\r\nN:Иванов;Иван;;;\r\nTEL:+79001112233\r\nEND:VCARD\r\n"

        val card = VCardParser.parse(text).contacts.single()

        assertEquals("Иван Иванов", card.fullName)
        assertEquals("Иван", card.givenName)
        assertEquals("Иванов", card.familyName)
    }

    @Test
    fun skipsMalformedAndEmptyCards() {
        // First card is complete but carries nothing usable; the second has no END:VCARD.
        val text = "BEGIN:VCARD\r\nNOTE:only a note\r\nEND:VCARD\r\n" +
            "BEGIN:VCARD\r\nFN:Broken\r\nTEL:999\r\n"

        val result = VCardParser.parse(text)

        assertTrue(result.contacts.isEmpty())
        assertEquals(2, result.skipped)
    }

    @Test
    fun decodesEscapedValues() {
        val text = "BEGIN:VCARD\r\nFN:Smith\\, John\r\nTEL:111\r\nEND:VCARD\r\n"

        val card = VCardParser.parse(text).contacts.single()

        assertEquals("Smith, John", card.fullName)
    }

    @Test
    fun ignoresUnknownProperties() {
        val text = "BEGIN:VCARD\r\nFN:Ann\r\nORG:ACME\r\nBDAY:19900101\r\nTEL:1\r\nEND:VCARD\r\n"

        val card = VCardParser.parse(text).contacts.single()

        assertEquals("Ann", card.fullName)
        assertEquals(listOf("1"), card.phones)
    }
}
