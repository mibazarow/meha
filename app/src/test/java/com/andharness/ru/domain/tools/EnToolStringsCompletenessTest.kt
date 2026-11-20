package com.andharness.ru.domain.tools

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EN-completeness guard for [EnToolStrings]: every tool the app actually ships (i.e. every entry of
 * [DefaultTools.all]) must have an EN description AND an EN description for every one of its
 * declared parameters. This is what lets [com.andharness.ru.data.tools.ToolRegistryImpl] swap RU
 * strings for EN without ever falling back to Russian mid-schema.
 *
 * Pure JVM test (no Context/Robolectric): [EnToolStrings] is an in-memory map.
 */
class EnToolStringsCompletenessTest {

    @Test
    fun everyCatalogToolHasAnEnglishDescription() {
        val missing = DefaultTools.all().map { it.name }.filter { name ->
            EnToolStrings.descriptions[name].isNullOrBlank()
        }
        assertTrue("tools without an EN description: $missing", missing.isEmpty())
    }

    @Test
    fun everyDeclaredParameterHasAnEnglishDescription() {
        val missing = mutableListOf<String>()
        DefaultTools.all().forEach { tool ->
            val properties = tool.parameters["properties"] as? JsonObject ?: return@forEach
            val enForTool = EnToolStrings.paramDescriptions[tool.name]
            properties.keys.forEach { param ->
                if (enForTool?.get(param).isNullOrBlank()) missing += "${tool.name}.$param"
            }
        }
        assertTrue("parameters without an EN description: $missing", missing.isEmpty())
    }

    @Test
    fun englishDescriptionsCoverExactlyTheCatalogTools() {
        val catalogNames = DefaultTools.all().map { it.name }.toSet()
        assertEquals(
            "the EN description map must cover exactly the shipped catalog",
            catalogNames,
            EnToolStrings.descriptions.keys,
        )
    }
}
