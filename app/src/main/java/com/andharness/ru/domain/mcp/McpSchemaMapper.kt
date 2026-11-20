package com.andharness.ru.domain.mcp

import com.andharness.ru.domain.serialization.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Pure, side-effect-free mapping between a remote MCP tool's JSON Schema and our flat tool-param
 * model. Android-free, so every rule is unit-tested on the JVM (`McpSchemaMapperTest`).
 *
 * Our parameter model supports only flat `string` / `boolean` / `integer` properties (the MCP core
 * tests and `DefaultTools` never emit nested object/array params), so the input schema is
 * FLATTENED:
 * - each top-level property becomes one parameter; a `boolean` stays `boolean`, `integer`/`number`
 *   become `integer` (numbers arrive as text and are parsed back), and every other kind
 *   (`string`, `object`, `array`, missing) is exposed as `string`;
 * - `required` is copied from the schema (only for properties that actually exist);
 * - nested/unknown shapes are carried as JSON text: [buildArguments] parses a parameter string that
 *   is valid JSON for an `object`/`array` (or an untyped) property, converts `integer`/`number`/
 *   `boolean` text to real JSON scalars, and keeps every plain string untouched.
 *
 * Malformed or missing schemas are tolerated (the tool simply gets no parameters), never thrown.
 *
 * References:
 * - MCP tools / inputSchema — https://modelcontextprotocol.io/specification/2025-06-18/server/tools
 * - JSON Schema object — https://json-schema.org/understanding-json-schema/reference/object
 * - kotlinx.serialization JSON — https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/
 */
object McpSchemaMapper {

    /** Max top-level properties mapped from one remote tool (a bounded prompt is more important). */
    const val MAX_PROPERTIES = 40

    /** Bounded property description. */
    const val MAX_DESCRIPTION_CHARS = 240

    /** Bounded compact schema hint appended to a tool description. */
    const val MAX_HINT_CHARS = 300

    /** Bounded tool description handed to the model. */
    const val MAX_TOOL_DESCRIPTION_CHARS = 600

    /** How many enum values to echo in a property description. */
    private const val MAX_ENUM_VALUES = 12

    private val json = AppJson.LenientIsLenient

    /**
     * Builds the flat parameter schema exposed to the model for a remote tool's `inputSchema`.
     * A `null`/malformed schema yields an object-typed schema with no properties (the tool takes no
     * arguments), never an exception.
     */
    fun toParameterSchema(inputSchema: JsonObject?): JsonObject {
        val properties = inputSchema?.propertiesOrNull() ?: return emptySchema()
        val required = requiredNames(inputSchema)
        return buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                properties.entries.take(MAX_PROPERTIES).forEach { (name, element) ->
                    val property = element as? JsonObject
                    putJsonObject(name) {
                        put("type", parameterType(property))
                        val description = propertyDescription(property)
                        if (description.isNotBlank()) put("description", description)
                    }
                }
            }
            val requiredPresent = properties.keys.filter { it in required }.take(MAX_PROPERTIES)
            if (requiredPresent.isNotEmpty()) {
                putJsonArray("required") { requiredPresent.forEach { add(JsonPrimitive(it)) } }
            }
        }
    }

    /**
     * Compact one-line hint of the expected arguments (name: type, required marker) for the tool
     * description, or "" when the tool takes no arguments. Bounded by [MAX_HINT_CHARS].
     */
    fun renderHint(inputSchema: JsonObject?): String {
        val properties = inputSchema?.propertiesOrNull() ?: return ""
        if (properties.isEmpty()) return ""
        val required = requiredNames(inputSchema)
        val parts = properties.entries.take(MAX_PROPERTIES).map { (name, element) ->
            val type = parameterType(element as? JsonObject)
            val marker = if (name in required) " (обязательный)" else ""
            "$name: $type$marker"
        }
        return ("Аргументы — " + parts.joinToString("; ") + ".").take(MAX_HINT_CHARS)
    }

    /**
     * Assembles the `arguments` JSON object sent to the remote tool from the flat arguments the
     * model produced. Values are converted per the property's declared schema type:
     * `integer`/`number` text becomes a real number, `boolean` text becomes a real boolean,
     * `object`/`array` (or untyped) text that parses as JSON becomes that JSON, and every other
     * value is passed through unchanged (plain strings stay plain strings).
     */
    fun buildArguments(inputSchema: JsonObject?, provided: JsonObject): JsonObject {
        if (provided.isEmpty()) return buildJsonObject { }
        val properties = inputSchema?.propertiesOrNull()
        return buildJsonObject {
            provided.forEach { (key, value) ->
                val declaredType = (properties?.get(key) as? JsonObject)?.let(::rawType)
                put(key, convert(declaredType, value))
            }
        }
    }

    private fun emptySchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }

    /** The declared flat type for one property (never object/array — those are carried as string). */
    private fun parameterType(property: JsonObject?): String = when (rawType(property)) {
        "boolean" -> "boolean"
        "integer", "number" -> "integer"
        else -> "string"
    }

    private fun rawType(property: JsonObject?): String? =
        ((property?.get("type") as? JsonPrimitive)?.contentOrNull)?.trim()?.lowercase()

    private fun JsonObject.propertiesOrNull(): JsonObject? = this["properties"] as? JsonObject

    private fun requiredNames(schema: JsonObject?): Set<String> =
        (schema?.get("required") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet()
            .orEmpty()

    private fun propertyDescription(property: JsonObject?): String {
        val base = (property?.get("description") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val notes = mutableListOf<String>()
        val type = rawType(property)
        if (type == "object" || type == "array") {
            notes += "Передай как JSON-строку."
        }
        val enumValues = (property?.get("enum") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { value -> value.isNotBlank() } }
            .orEmpty()
        if (enumValues.isNotEmpty()) {
            notes += "Допустимые значения: " + enumValues.take(MAX_ENUM_VALUES).joinToString(", ") + "."
        }
        return (listOf(base) + notes)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .take(MAX_DESCRIPTION_CHARS)
    }

    private fun convert(declaredType: String?, value: JsonElement): JsonElement {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return value
        return when (declaredType) {
            "string" -> value
            "boolean" -> parseBoolean(text)?.let { JsonPrimitive(it) } ?: value
            "integer" -> text.trim().toLongOrNull()?.let { JsonPrimitive(it) } ?: value
            "number" -> text.trim().toDoubleOrNull()?.let { JsonPrimitive(it) } ?: value
            "object", "array" -> parseJsonElement(text) ?: value
            else -> {
                val parsed = parseJsonElement(text)
                if (parsed is JsonObject || parsed is JsonArray) parsed else value
            }
        }
    }

    private fun parseJsonElement(text: String): JsonElement? =
        runCatching { json.parseToJsonElement(text) }.getOrNull()

    private fun parseBoolean(text: String): Boolean? = when (text.trim().lowercase()) {
        "true", "1", "yes", "да" -> true
        "false", "0", "no", "нет" -> false
        else -> null
    }
}
