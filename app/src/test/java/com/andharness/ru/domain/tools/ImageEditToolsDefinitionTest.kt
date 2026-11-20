package com.andharness.ru.domain.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the shape of the new image-editing tool definitions added for bug-item 10
 * (`image_crop`, `image_rotate`, `image_adjust`, `image_watermark`, `image_overlay_text`,
 * `image_collage`, `screen_annotate`).
 *
 * Complements [DefaultToolsSnapshotTest] (names + order) and [EnToolStringsCompletenessTest]
 * (EN strings) with a structural check: every tool must exist, declare every `required` key as a
 * property, and only use the RFC-style schema types the local server accepts (`string`/`boolean`).
 */
class ImageEditToolsDefinitionTest {

    private val newImageTools = listOf(
        "image_crop",
        "image_rotate",
        "image_adjust",
        "image_watermark",
        "image_overlay_text",
        "image_collage",
        "screen_annotate",
        "image_svg_render",
        "image_remove_background",
        "image_detect_objects",
    )

    private val catalog = DefaultTools.all().associateBy { it.name }

    @Test
    fun everyNewImageToolIsInTheCatalog() {
        val missing = newImageTools.filterNot { it in catalog }
        assertTrue("new image tools missing from the catalog: $missing", missing.isEmpty())
    }

    @Test
    fun everyRequiredParameterIsDeclaredAsAProperty() {
        for (name in newImageTools) {
            val tool = catalog.getValue(name)
            val properties = tool.parameters["properties"] as? JsonObject
                ?: error("$name has no properties object")
            val required = tool.parameters["required"] as? JsonArray ?: JsonArray(emptyList())
            for (element in required) {
                val key = (element as? JsonPrimitive)?.content.orEmpty()
                assertTrue(
                    "$name declares required «$key» but has no such property",
                    properties.containsKey(key),
                )
            }
        }
    }

    @Test
    fun onlyStringAndBooleanPropertyTypesAreUsed() {
        for (name in newImageTools) {
            val tool = catalog.getValue(name)
            val properties = tool.parameters["properties"] as? JsonObject ?: continue
            for ((param, schema) in properties) {
                val type = ((schema as? JsonObject)?.get("type") as? JsonPrimitive)?.content
                assertTrue(
                    "$name.$param has unexpected type «$type» (only string/boolean)",
                    type == "string" || type == "boolean",
                )
            }
        }
    }
}
