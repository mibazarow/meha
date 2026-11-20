package com.andharness.ru.domain.contacts

/**
 * Pure, Android-free writer of vCard 3.0 (RFC 2426) text for the contact-export tool. Keeping it
 * free of `android.*` lets the exact output format be verified on the JVM, so an exported file is
 * guaranteed to round-trip through [VCardParser].
 *
 * Only the properties the app models are emitted (`FN`, `N`, `TEL`, `EMAIL`); values are escaped per
 * RFC 2426. Reference: RFC 2426 — https://datatracker.ietf.org/doc/html/rfc2426
 */
object VCardWriter {

    /** Serialises [contacts] into one vCard 3.0 document (CRLF line endings, one card per contact). */
    fun write(contacts: List<VCardContact>): String =
        buildString { contacts.forEach { append(writeContact(it)) } }

    /** Serialises a single [contact] as a complete `BEGIN:VCARD … END:VCARD` block. */
    fun writeContact(contact: VCardContact): String = buildString {
        append("BEGIN:VCARD\r\n")
        append("VERSION:3.0\r\n")
        val given = contact.givenName
        val family = contact.familyName
        if (!given.isNullOrBlank() || !family.isNullOrBlank()) {
            append("N:")
            append(escape(family.orEmpty()))
            append(';')
            append(escape(given.orEmpty()))
            append(";;;\r\n")
        }
        if (contact.fullName.isNotBlank()) {
            append("FN:").append(escape(contact.fullName)).append("\r\n")
        }
        contact.phones.filter { it.isNotBlank() }.forEach {
            append("TEL;TYPE=VOICE:").append(escape(it)).append("\r\n")
        }
        contact.emails.filter { it.isNotBlank() }.forEach {
            append("EMAIL;TYPE=INTERNET:").append(escape(it)).append("\r\n")
        }
        append("END:VCARD\r\n")
    }

    /** Escapes the RFC 2426 special characters in a single value. */
    private fun escape(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n' -> append("\\n")
                '\r' -> Unit
                else -> append(c)
            }
        }
    }
}
