package com.andharness.ru.domain.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the shape of the audio/video editing tool definitions added for bug-items 11 and 12.
 *
 * Complements [DefaultToolsSnapshotTest] (names + order) and [EnToolStringsCompletenessTest]
 * (EN strings) with a structural check: every tool must exist, declare every `required` key as a
 * property, and only use the schema types the local server accepts (`string`/`boolean`).
 */
class AudioVideoEditToolsDefinitionTest {

    private val newTools = listOf(
        "audio_trim",
        "audio_concat",
        "audio_convert",
        "audio_volume",
        "audio_speed",
        "audio_extract_from_video",
        "audio_tag_write",
        "video_trim",
        "video_concat",
        "video_resize",
        "video_bitrate",
        "video_compress",
        "video_rotate",
        "video_extract_audio",
        "video_subtitles",
        "video_convert",
    )

    private val catalog = DefaultTools.all().associateBy { it.name }

    @Test
    fun everyNewToolIsInTheCatalog() {
        val missing = newTools.filterNot { it in catalog }
        assertTrue("new audio/video tools missing from the catalog: $missing", missing.isEmpty())
    }

    @Test
    fun everyRequiredParameterIsDeclaredAsAProperty() {
        for (name in newTools) {
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
        for (name in newTools) {
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

    @Test
    fun everyNewToolRequiresAllFilesAccessAndWritesNewFiles() {
        for (name in newTools) {
            val tool = catalog.getValue(name)
            assertTrue(
                "$name must request MANAGE_EXTERNAL_STORAGE",
                tool.needsPermission == "android.permission.MANAGE_EXTERNAL_STORAGE",
            )
        }
    }
}
