package com.andharness.ru.domain.screenmap

import kotlinx.serialization.encodeToString
import com.andharness.ru.domain.serialization.AppJson
import com.andharness.ru.domain.serialization.decodeOrNull

/**
 * Pure encode/decode/normalisation for the per-app screen map. Depends only on
 * kotlinx.serialization, so it is exercised on the JVM (see `ScreenMapCodecTest`); the file-backed
 * [com.andharness.ru.data.screenmap.ScreenMapStore] only adds I/O on top.
 *
 * All bounds live here so the file, the in-memory state and the rendered output can never grow
 * unchecked, and a hand-edited/corrupt file degrades to an empty map instead of throwing.
 *
 * kotlinx.serialization Json reference: https://kotlinlang.org/api/kotlinx.serialization/
 */
object ScreenMapCodec {

    /** Hard cap on remembered app packages; the least-recently-seen app is dropped first. */
    const val MAX_APPS = 50

    /** Hard cap on screens remembered per app; the least-recently-seen screen is dropped first. */
    const val MAX_SCREENS_PER_APP = 12

    /** Hard cap on notable elements remembered per screen (after dedup). */
    const val MAX_ELEMENTS_PER_SCREEN = 40

    /** Cap of every stored string field, so a huge WebView label can never bloat the file. */
    const val MAX_FIELD_CHARS = 120

    private val json = AppJson.LenientEncodeDefaults

    fun encode(maps: List<AppScreenMap>): String = json.encodeToString(maps.takeLast(MAX_APPS))

    /**
     * Decodes the persisted map list. A corrupt/foreign/missing blob degrades to an empty list
     * (same policy as `SkillCodec`/`UiMacroCodec`), blank packages are dropped, and every record is
     * clamped by [sanitize] so a hand-edited file can never break the tools.
     */
    fun decode(raw: String?): List<AppScreenMap> =
        json.decodeOrNull<List<AppScreenMap>>(raw)
            ?.filter { it.appPackage.isNotBlank() }
            ?.map { sanitize(it) }
            ?.takeLast(MAX_APPS)
            ?: emptyList()

    /** Clamps one app map to every bound (packages/screens/elements/string lengths). */
    fun sanitize(map: AppScreenMap): AppScreenMap = map.copy(
        appPackage = map.appPackage.trim().take(MAX_FIELD_CHARS),
        screens = map.screens
            .filter { it.signature.isNotBlank() }
            .map { screen ->
                screen.copy(
                    signature = screen.signature.trim().take(MAX_FIELD_CHARS),
                    title = screen.title.normalizedOrNull(),
                    lastSeen = screen.lastSeen.coerceAtLeast(0L),
                    elements = dedupeAndCap(screen.elements),
                )
            }
            .takeLast(MAX_SCREENS_PER_APP),
    )

    /**
     * Trims fields, drops empty elements and removes duplicates (same text/desc/id/class), keeping the
     * first occurrence, then caps the result at [MAX_ELEMENTS_PER_SCREEN].
     */
    fun dedupeAndCap(elements: List<ScreenElementInfo>): List<ScreenElementInfo> {
        val seen = HashSet<String>()
        val out = ArrayList<ScreenElementInfo>(minOf(elements.size, MAX_ELEMENTS_PER_SCREEN))
        for (element in elements) {
            val clean = clean(element)
            if (clean.isEmpty) continue
            if (!seen.add(elementKey(clean))) continue
            out += clean
            if (out.size >= MAX_ELEMENTS_PER_SCREEN) break
        }
        return out
    }

    /** Normalises one element's fields (trim + length caps + role fallback). */
    fun clean(element: ScreenElementInfo): ScreenElementInfo = element.copy(
        text = element.text.normalizedOrNull(),
        contentDescription = element.contentDescription.normalizedOrNull(),
        resourceId = element.resourceId.normalizedOrNull(),
        className = element.className.normalizedOrNull(),
        role = element.role.trim().takeIf { it.isNotBlank() }?.take(32)
            ?: ScreenElementInfo.ROLE_TEXT,
    )

    /** Identity of an element for dedup: its four identifying fields, lower-cased. */
    fun elementKey(element: ScreenElementInfo): String =
        listOf(element.text, element.contentDescription, element.resourceId, element.className)
            .joinToString("\u001f") { it?.trim()?.lowercase() ?: "" }

    private val ScreenElementInfo.isEmpty: Boolean
        get() = text == null && contentDescription == null && resourceId == null && className == null

    private fun String?.normalizedOrNull(): String? =
        this?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_FIELD_CHARS)
}
