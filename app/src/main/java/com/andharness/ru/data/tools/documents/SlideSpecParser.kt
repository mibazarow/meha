package com.andharness.ru.data.tools.documents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses the `slides` JSON argument of `pptx_create` / `odp_create` / the presentation merge tools
 * into [PptxWriter.Slide] models.
 *
 * Accepted shapes (JSON passed as a string):
 * - an array of strings: each string is one slide — its first non-empty line becomes the title, the
 *   rest the bullet lines (`"Заголовок\n• пункт"` or `"Заголовок\nпункт"`);
 * - an array of objects: `{"title": "…", "lines": ["…", …]}` or `{"title": "…", "text": "a\nb"}`.
 *
 * Pure JVM (kotlinx.serialization), so the parser is unit-tested off-device.
 */
internal object SlideSpecParser {

    /** Parses [json] into slides. Throws [IllegalArgumentException] on malformed/degenerate input. */
    fun parse(json: String): List<PptxWriter.Slide> {
        val element = try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("slides: некорректный JSON (${e.message}).")
        }
        val array = element as? JsonArray
            ?: throw IllegalArgumentException(
                "slides: ожидался JSON-массив строк или объектов {\"title\": …, \"lines\": [...]}."
            )
        if (array.isEmpty()) throw IllegalArgumentException("slides: список слайдов пуст.")
        val slides = array.mapIndexed { index, item -> parseSlide(index, item) }
        if (slides.none { (it.title?.isNotBlank() == true) || it.lines.isNotEmpty() }) {
            throw IllegalArgumentException("slides: не найдено ни одного непустого слайда.")
        }
        return slides
    }

    private fun parseSlide(index: Int, element: JsonElement): PptxWriter.Slide = when (element) {
        is JsonPrimitive -> fromText(element.content)
        is JsonObject -> {
            val title = (element["title"] as? JsonPrimitive)
                ?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }
            val lines = when (val linesElement = element["lines"] ?: element["text"]) {
                is JsonArray -> linesElement.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
                is JsonPrimitive -> linesElement.content.split('\n')
                else -> emptyList()
            }.map { it.trim() }.filter { it.isNotEmpty() }
            PptxWriter.Slide(title = title, lines = lines)
        }
        else -> throw IllegalArgumentException("slides[$index]: ожидалась строка или объект.")
    }

    /** A plain-text slide: the first non-empty line is the title, the rest become bullet lines. */
    fun fromText(text: String): PptxWriter.Slide {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        return PptxWriter.Slide(title = lines.firstOrNull(), lines = lines.drop(1))
    }
}
