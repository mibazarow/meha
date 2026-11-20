package com.andharness.ru.domain.chat

import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.andharness.ru.domain.serialization.decodeOrNull

/**
 * How a `device_search` hit is presented in the chat: a real photo thumbnail, a document card, or —
 * when nothing classifies it — a plain tappable link. Kept as a three-value classification so the
 * preview UI has one exhaustive `when` and an honest fallback for anything unrecognised.
 */
enum class DeviceSearchCardKind { IMAGE, DOCUMENT, UNKNOWN }

/**
 * One renderable `device_search` hit carried from the tool to the chat UI. [uri] is the
 * `content://`/file address to open, [mimeType] drives the viewer, [snippet] is the matched document
 * text (empty for images) and [kind] is the presentation branch.
 */
data class DeviceSearchCard(
    val kind: DeviceSearchCardKind,
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val snippet: String,
)

/**
 * The structured "search results" attachment that rides along with the `device_search` tool result
 * (persisted on the `tool` message's `payload` column and rendered as cards in the chat).
 *
 * Pure and Android-free, so classification and the JSON round-trip are unit-testable on the JVM.
 * The encoding is a small, self-describing JSON object (`{"items":[…]}`) using kotlinx.serialization
 * (the project's existing JSON stack — `kotlinx-serialization-json`). Decoding is tolerant by
 * design: blank, malformed or unknown-shaped input yields an EMPTY list rather than throwing, so one
 * corrupt payload can never crash the transcript.
 */
object DeviceSearchPayload {

    /** The tool name whose result rows carry this payload. */
    const val TOOL_NAME = "device_search"

    @Serializable
    internal data class Envelope(val items: List<Item> = emptyList())

    @Serializable
    internal data class Item(
        val kind: String = "",
        val uri: String = "",
        @SerialName("name") val name: String = "",
        @SerialName("mime") val mime: String = "",
        val snippet: String = "",
    )

    /** `encodeDefaults` keeps the shape stable; unknown keys are ignored on the way back in. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Classifies a hit by its index kind ([indexKind]: `IMAGE`/`DOC_CHUNK` as stored, or the
     * lowercase form) and its [mimeType]. An `IMAGE` kind or an image MIME is an [IMAGE]; a
     * document kind or a recognised document MIME is a [DOCUMENT]; anything else is [UNKNOWN] (the
     * caller then renders it as a plain open link). Case-insensitive; never throws.
     */
    fun classify(indexKind: String?, mimeType: String?): DeviceSearchCardKind {
        val kind = indexKind?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val mime = mimeType?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (kind == "image" || mime.startsWith("image/")) return DeviceSearchCardKind.IMAGE
        if (kind == "doc_chunk" || kind == "document" || isDocumentMime(mime)) {
            return DeviceSearchCardKind.DOCUMENT
        }
        return DeviceSearchCardKind.UNKNOWN
    }

    /** Serialises [cards] to the payload JSON. An empty list still encodes to a valid envelope. */
    fun encode(cards: List<DeviceSearchCard>): String = json.encodeToString(
        Envelope.serializer(),
        Envelope(
            items = cards.map {
                Item(
                    kind = it.kind.name,
                    uri = it.uri,
                    name = it.displayName,
                    mime = it.mimeType,
                    snippet = it.snippet
                )
            }
        )
    )

    /**
     * Parses a `payload` column back into cards. Returns an empty list for `null`/blank input or any
     * malformed JSON; items with a blank URI are dropped. Never throws.
     */
    fun decode(raw: String?): List<DeviceSearchCard> =
        json.decodeOrNull<Envelope>(raw)
            ?.items
            ?.mapNotNull { it.toCard() }
            .orEmpty()

    /** MIME prefixes that are treated as openable documents rather than "unknown". */
    private fun isDocumentMime(mime: String): Boolean {
        if (mime.isEmpty()) return false
        return DOCUMENT_MIME_PREFIXES.any { mime.startsWith(it) }
    }

    private fun Item.toCard(): DeviceSearchCard? {
        if (uri.isBlank()) return null
        val cardKind = DeviceSearchCardKind.entries
            .firstOrNull { it.name.equals(kind, ignoreCase = true) }
            ?: classify(null, mime)
        return DeviceSearchCard(
            kind = cardKind,
            uri = uri,
            displayName = name,
            mimeType = mime,
            snippet = snippet
        )
    }

    private val DOCUMENT_MIME_PREFIXES = listOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument",
        "application/vnd.ms-",
        "application/vnd.oasis.opendocument",
        "application/rtf",
        "application/epub",
        "application/json",
        "application/xml",
        "text/"
    )
}
