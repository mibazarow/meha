package com.andharness.ru.domain.contacts

import java.util.Locale

/**
 * A contact card decoded from a vCard 3.0 file, reduced to the fields this app supports.
 *
 * Unsupported vCard properties (ADR, ORG, TITLE, BDAY, PHOTO, URL, …) are intentionally ignored:
 * this parser only lifts the name, phone numbers and e-mail addresses described by RFC 2426.
 */
data class VCardContact(
    val fullName: String,
    val givenName: String? = null,
    val familyName: String? = null,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
) {
    /** Whether the card carries anything worth inserting as a contact. */
    val isUsable: Boolean
        get() = fullName.isNotBlank() ||
            !givenName.isNullOrBlank() ||
            !familyName.isNullOrBlank() ||
            phones.any { it.isNotBlank() } ||
            emails.any { it.isNotBlank() }
}

/** Outcome of [VCardParser.parse]: the usable [contacts] plus how many cards were [skipped]. */
data class VCardParseResult(
    val contacts: List<VCardContact>,
    val skipped: Int,
)

/**
 * Pure, Android-free parser for the subset of vCard 3.0 (RFC 2426) this app imports: the
 * `BEGIN:VCARD` / `END:VCARD` envelope, `FN`, `N`, `TEL` and `EMAIL` properties, including folded
 * (multi-line) properties and files holding several cards. It is deliberately JVM-testable so the
 * parsing rules can be verified without the contacts provider.
 *
 * Deliberate limits (reported honestly by the caller): property parameters such as
 * `TEL;TYPE=CELL:…` are accepted but not retained, escaped characters are decoded, and any other
 * property is skipped. A card that carries no name, phone or e-mail is counted as skipped rather
 * than producing an empty contact.
 *
 * Reference: RFC 2426 (vCard MIME Directory Profile) — https://datatracker.ietf.org/doc/html/rfc2426
 */
object VCardParser {

    /** Parses [text] into the usable cards it contains plus a skip count. Never throws. */
    fun parse(text: String): VCardParseResult {
        val contacts = ArrayList<VCardContact>()
        var skipped = 0
        var current: MutableCard? = null

        for (line in unfold(text)) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            when {
                trimmed.equals("BEGIN:VCARD", ignoreCase = true) -> current = MutableCard()
                trimmed.equals("END:VCARD", ignoreCase = true) -> {
                    current?.let { card ->
                        if (card.toContact().isUsable) contacts.add(card.toContact()) else skipped++
                    }
                    current = null
                }
                // Content outside a card envelope is not a card; ignore it silently.
                current == null -> Unit
                else -> current.applyProperty(trimmed)
            }
        }
        // An unterminated BEGIN:VCARD is a malformed card.
        if (current != null) skipped++
        return VCardParseResult(contacts, skipped)
    }

    /**
     * Unfolds RFC 2426 line folding: a physical line that starts with a single space or tab is a
     * continuation of the previous logical line and the leading whitespace is removed. The input's
     * `CRLF`, `LF` and lone `CR` line breaks are all accepted.
     */
    private fun unfold(text: String): List<String> {
        val out = ArrayList<String>()
        for (line in text.split(LINE_BREAKS)) {
            if (line.isNotEmpty() && (line[0] == ' ' || line[0] == '\t') && out.isNotEmpty()) {
                out[out.size - 1] = out.last() + line.substring(1)
            } else {
                out.add(line)
            }
        }
        return out
    }

    private class MutableCard {
        var fullName: String? = null
        var givenName: String? = null
        var familyName: String? = null
        val phones = ArrayList<String>()
        val emails = ArrayList<String>()

        fun applyProperty(line: String) {
            val colon = line.indexOf(':')
            if (colon <= 0) return
            val namePart = line.substring(0, colon)
            val value = line.substring(colon + 1)
            // Property group / parameters are separated from the name by ';' (e.g. TEL;TYPE=CELL).
            val name = namePart.substringBefore(';').trim().uppercase(Locale.ROOT)
            when (name) {
                "FN" -> fullName = unescape(value)
                "N" -> {
                    val parts = splitUnescaped(value, ';')
                    familyName = parts.getOrNull(0)?.let(::unescape)?.takeIf { it.isNotBlank() }
                    givenName = parts.getOrNull(1)?.let(::unescape)?.takeIf { it.isNotBlank() }
                }
                "TEL" -> unescape(value).takeIf { it.isNotBlank() }?.let(phones::add)
                "EMAIL" -> unescape(value).takeIf { it.isNotBlank() }?.let(emails::add)
                // Any other property (ADR, ORG, BDAY, PHOTO, …) is intentionally ignored.
            }
        }

        fun toContact(): VCardContact {
            val name = fullName?.takeIf { it.isNotBlank() }
                ?: buildString {
                    if (!givenName.isNullOrBlank()) append(givenName)
                    if (!givenName.isNullOrBlank() && !familyName.isNullOrBlank()) append(' ')
                    if (!familyName.isNullOrBlank()) append(familyName)
                }.takeIf { it.isNotBlank() }
                ?: ""
            return VCardContact(
                fullName = name,
                givenName = givenName,
                familyName = familyName,
                phones = phones.toList(),
                emails = emails.toList(),
            )
        }
    }

    /** Splits [value] on [delim] while honouring RFC 2426 backslash escaping. */
    private fun splitUnescaped(value: String, delim: Char): List<String> {
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                sb.append(c).append(value[i + 1])
                i += 2
                continue
            }
            if (c == delim) {
                parts.add(sb.toString())
                sb.setLength(0)
            } else {
                sb.append(c)
            }
            i++
        }
        parts.add(sb.toString())
        return parts
    }

    /** Decodes the RFC 2426 escaped characters `\n`, `\,`, `\;` and `\\`. */
    private fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    ',', ';', '\\' -> sb.append(next)
                    else -> {
                        sb.append(c)
                        sb.append(next)
                    }
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private val LINE_BREAKS = Regex("\r\n|\r|\n")
}
