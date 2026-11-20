package com.andharness.ru.domain.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure JSON-Schema → flat-param mapping used by dynamic MCP tool exposure:
 * required/optional marking, nested shapes carried as strings, invalid schemas tolerated, and
 * argument assembly (plain string vs JSON string vs typed scalars).
 */
class McpSchemaMapperTest {

    private fun schema(block: JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { block() }
        }

    @Test
    fun stringAndRequiredPropertiesAreMapped() {
        val input = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("city") {
                    put("type", "string")
                    put("description", "Город")
                }
                putJsonObject("units") {
                    put("type", "string")
                    putJsonArray("enum") { add(JsonPrimitive("c")); add(JsonPrimitive("f")) }
                }
            }
            putJsonArray("required") { add(JsonPrimitive("city")) }
        }

        val params = McpSchemaMapper.toParameterSchema(input)
        val props = params["properties"]!!.jsonObject
        assertEquals("string", props["city"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("Город", props["city"]!!.jsonObject["description"]!!.jsonPrimitive.content)
        assertEquals("string", props["units"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(
            "enum must be hinted: ${props["units"]}",
            props["units"]!!.jsonObject["description"]!!.jsonPrimitive.content.contains("Допустимые значения")
        )
        val required = params["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("city"), required)
    }

    @Test
    fun booleanAndIntegerPropertiesKeepScalarTypes() {
        val input = schema {
            putJsonObject("flag") { put("type", "boolean") }
            putJsonObject("count") { put("type", "integer") }
            putJsonObject("ratio") { put("type", "number") }
        }
        val props = McpSchemaMapper.toParameterSchema(input)["properties"]!!.jsonObject
        assertEquals("boolean", props["flag"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("integer", props["count"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("integer", props["ratio"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun nestedObjectAndArrayAreExposedAsStringsWithAJsonHint() {
        val input = schema {
            putJsonObject("filter") { put("type", "object") }
            putJsonObject("tags") { put("type", "array") }
        }
        val props = McpSchemaMapper.toParameterSchema(input)["properties"]!!.jsonObject
        assertEquals("string", props["filter"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("string", props["tags"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(props["filter"]!!.jsonObject["description"]!!.jsonPrimitive.content.contains("JSON"))
        assertTrue(props["tags"]!!.jsonObject["description"]!!.jsonPrimitive.content.contains("JSON"))
    }

    @Test
    fun invalidSchemaIsToleratedWithoutThrowing() {
        val empty = McpSchemaMapper.toParameterSchema(null)
        assertEquals("object", empty["type"]!!.jsonPrimitive.content)
        assertTrue((empty["properties"]!!.jsonObject).isEmpty())
        assertNull(empty["required"])

        // `properties` present but not an object → still an empty, valid schema.
        val broken = buildJsonObject {
            put("type", "object")
            put("properties", "not-an-object")
            putJsonArray("required") { add(JsonPrimitive("ghost")) }
        }
        val params = McpSchemaMapper.toParameterSchema(broken)
        assertTrue((params["properties"]!!.jsonObject).isEmpty())
        assertNull("a required name without a property must be dropped", params["required"])
    }

    @Test
    fun renderHintListsArgumentsAndRequiredMarker() {
        val input = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("q") { put("type", "string") }
                putJsonObject("limit") { put("type", "integer") }
            }
            putJsonArray("required") { add(JsonPrimitive("q")) }
        }
        val hint = McpSchemaMapper.renderHint(input)
        assertTrue(hint, hint.contains("q: string (обязательный)"))
        assertTrue(hint, hint.contains("limit: integer"))
        assertEquals("", McpSchemaMapper.renderHint(null))
        assertEquals("", McpSchemaMapper.renderHint(schema { }))
    }

    @Test
    fun buildArgumentsKeepsPlainStringsAndParsesJsonShapes() {
        val input = schema {
            putJsonObject("text") { put("type", "string") }
            putJsonObject("filter") { put("type", "object") }
            putJsonObject("count") { put("type", "integer") }
            putJsonObject("flag") { put("type", "boolean") }
        }
        val assembled = McpSchemaMapper.buildArguments(
            input,
            buildJsonObject {
                put("text", "hello world")
                put("filter", """{"a":1,"b":[2,3]}""")
                put("count", "5")
                put("flag", "true")
            }
        )

        // A string-typed param stays a string even when its content looks like a number.
        assertEquals("\"hello world\"", assembled["text"].toString())
        assertTrue(assembled["filter"] is JsonObject)
        assertEquals("""{"a":1,"b":[2,3]}""", assembled["filter"].toString())
        assertEquals("5", assembled["count"].toString())
        assertEquals(true, (assembled["flag"] as JsonPrimitive).booleanOrNull)
    }

    @Test
    fun buildArgumentsKeepsAStringValueThatIsNotJson() {
        val input = schema { putJsonObject("filter") { put("type", "object") } }
        val assembled = McpSchemaMapper.buildArguments(
            input,
            buildJsonObject { put("filter", "not json at all") }
        )
        assertEquals("\"not json at all\"", assembled["filter"].toString())
    }

    @Test
    fun buildArgumentsWithNoSchemaPassesValuesThrough() {
        val assembled = McpSchemaMapper.buildArguments(
            null,
            buildJsonObject { put("x", "1"); put("y", "keep") }
        )
        // Without a schema we cannot know the intended type, so plain strings are kept as-is.
        assertEquals("\"1\"", assembled["x"].toString())
        assertEquals("\"keep\"", assembled["y"].toString())
        assertTrue(McpSchemaMapper.buildArguments(null, buildJsonObject { }).isEmpty())
    }

    @Test
    fun arrayElementKeepsJsonArraysAsElements() {
        val input = schema { putJsonObject("items") { put("type", "array") } }
        val assembled = McpSchemaMapper.buildArguments(
            input,
            buildJsonObject { put("items", "[1,2,3]") }
        )
        assertTrue(
            "a JSON array string must become a real array: ${assembled["items"]}",
            assembled["items"] is JsonArray
        )
    }
}
