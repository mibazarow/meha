package com.andharness.ru.data.tools

import com.andharness.ru.domain.agent.LlmTool
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.audit.ToolAuditRepository
import com.andharness.ru.domain.mcp.McpDynamicTools
import com.andharness.ru.domain.permission.PermissionManager
import com.andharness.ru.data.tools.selftest.SelfTestCases
import com.andharness.ru.domain.locale.AppLanguage
import com.andharness.ru.domain.tools.DefaultTools
import com.andharness.ru.domain.tools.EnToolStrings
import com.andharness.ru.domain.tools.Tool
import com.andharness.ru.domain.tools.ToolErrorHints
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.tools.ToolRegistry
import com.andharness.ru.domain.tools.ToolRetriever
import com.andharness.ru.domain.tools.currentToolExecutionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.andharness.ru.domain.serialization.AppJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolRegistryImpl @Inject constructor(
    handlers: Set<@JvmSuppressWildcards ToolHandler>,
    private val permissionManager: PermissionManager,
    private val toolAudit: ToolAuditRepository,
    private val mcpTools: McpDynamicTools
) : ToolRegistry {

    private val handlerMap: Map<String, ToolHandler> = handlers.associateBy { it.name }
    private val toolDefinitions: List<Tool> = DefaultTools.all()
    private val definitions: List<LlmTool> = toolDefinitions.map { it.toLlmTool() }
    private val permissionByName: Map<String, String?> = toolDefinitions.associate { it.name to it.needsPermission }

    /** The declared `parameters` JSON Schema per tool, indexed by name (like [permissionByName]). */
    private val parametersByName: Map<String, JsonObject> = toolDefinitions.associate { it.name to it.parameters }

    private val parser = AppJson.Lenient

    override fun definitions(): List<LlmTool> = definitions

    /**
     * EN variants of the catalog, built once on first request. Lazy so a Russian-only session never
     * pays for — or even loads — [EnToolStrings]. Only the description STRINGS are swapped; the
     * parameter schema (`type`, `properties`, `required`) is reused from the RU [Tool.parameters].
     */
    private val englishDefinitions: List<LlmTool> by lazy {
        toolDefinitions.map { it.toEnglishLlmTool() }
    }

    /**
     * Language-aware overload (see [ToolRegistry.definitions]). Russian — and any tag that
     * [AppLanguage.normalize] maps to Russian (e.g. `fr`, or a null/blank/unknown value) — returns the
     * EXACT same list as [definitions] (byte-identical RU output). English returns the same tools with
     * their `description` and per-parameter `description` strings replaced from the EN catalog.
     */
    override fun definitions(language: String): List<LlmTool> =
        if (AppLanguage.normalize(language) == AppLanguage.ENGLISH) englishDefinitions else definitions

    /** One RU [Tool] turned into its EN [LlmTool]: same name/schema, EN description strings. */
    private fun Tool.toEnglishLlmTool(): LlmTool = LlmTool(
        name = name,
        description = EnToolStrings.descriptions[name] ?: description,
        parameters = englishParameters(name, parameters)
    )

    /**
     * Copy of a RU `parameters` JSON Schema with every declared property's `description` replaced by
     * its EN text. The structure — property keys and their order, `type`, `required`, and any other
     * keys — is preserved; only `description` string values change. A property with no EN entry (or a
     * non-object schema) keeps its RU text, so the result is always a valid superset of the RU shape.
     */
    private fun englishParameters(toolName: String, schema: JsonObject): JsonObject {
        val enDescriptions = EnToolStrings.paramDescriptions[toolName].orEmpty()
        val properties = schema["properties"] as? JsonObject ?: return schema
        val localizedProperties = buildJsonObject {
            properties.forEach { (paramName, paramSchema) ->
                val en = enDescriptions[paramName]
                if (en == null || paramSchema !is JsonObject) {
                    put(paramName, paramSchema)
                } else {
                    put(paramName, localizeDescription(paramSchema, en))
                }
            }
        }
        return JsonObject(schema + ("properties" to localizedProperties))
    }

    /** Replace one property schema's `description`, keeping its key order and every other key. */
    private fun localizeDescription(paramSchema: JsonObject, en: String): JsonObject =
        buildJsonObject {
            var replaced = false
            paramSchema.forEach { (key, value) ->
                if (key == "description") {
                    put(key, JsonPrimitive(en))
                    replaced = true
                } else {
                    put(key, value)
                }
            }
            if (!replaced) put("description", JsonPrimitive(en))
        }

    /**
     * Single tool-dispatch entry point. Every call — including unknown tools, bad arguments and
     * permission denials — is appended to the tamper-evident audit log (update.md idea #6). Audit
     * failures are swallowed so they can never break a tool execution.
     */
    override suspend fun execute(name: String, argumentsJson: String): ToolResult {
        val startedNanos = System.nanoTime()
        var result = when (name) {
            "selftest_run" -> runSelfTest()
            // Escape hatch of the two-stage tool selection (update.md idea #1): the model can ask
            // for tools that were not in its per-turn subset. Handled here (not as a handler) so it
            // sees the full catalog without a DI cycle.
            "tool_search" -> runToolSearch(argumentsJson)
            else -> executeInternal(name, argumentsJson)
        }
        // Enrich a failure with ONE actionable hint (grant a permission, install an app, check the
        // network). Done here, at the single dispatch point, so no individual handler needs to know
        // about it. The `PERMISSION_REQUIRED:` sentinel is left untouched: the orchestrator parses it
        // to open the right settings screen, and appending text would corrupt that token.
        if (!result.success && !result.output.startsWith(Tool.PERMISSION_REQUIRED)) {
            val hint = ToolErrorHints.hint(name, result.output)
            if (hint.isNotEmpty()) result = result.copy(output = result.output + "\n" + hint)
        }
        // The chat/model of THIS run come from the per-run [ToolExecutionContext] coroutine element
        // (installed by the orchestrator around the tool loop), never from a shared singleton — so
        // concurrent runs in different chats tag their audit entries correctly.
        val runContext = currentToolExecutionContext()
        runCatching {
            toolAudit.record(
                at = System.currentTimeMillis(),
                chatId = runContext?.chatId,
                tool = name,
                argsJson = argumentsJson,
                success = result.success,
                resultText = result.output,
                durationMs = (System.nanoTime() - startedNanos) / 1_000_000,
                model = runContext?.model
            )
        }
        return result
    }

    private suspend fun executeInternal(name: String, argumentsJson: String): ToolResult {
        val obj: JsonObject = try {
            parser.parseToJsonElement(argumentsJson).jsonObject
        } catch (e: Exception) {
            return ToolResult(false, "Неверные аргументы: ${e.message}")
        }
        // Schema-aware coercion of the model's arguments, at the single decode choke point shared
        // by the built-in and the dynamic MCP dispatch below. A tool without a declared schema
        // (e.g. a dynamic MCP tool) is left untouched. See [coerceArguments].
        val schema = parametersByName[name]
        val args = if (schema != null) coerceArguments(schema, obj) else obj
        val handler = handlerMap[name]
        if (handler == null) {
            // Dynamic MCP tools are not in the static handler set (they depend on the user's live
            // server registry), so they are dispatched to the MCP provider. `null` means the name is
            // not a dynamic MCP tool at all, so the ordinary "unknown tool" path still applies.
            if (name.startsWith(McpDynamicTools.PREFIX)) {
                val mcpResult = mcpTools.execute(name, args)
                if (mcpResult != null) return mcpResult
            }
            return ToolResult(false, "Инструмент «$name» ещё не реализован.")
        }
        val perm = permissionByName[name]
        if (perm != null && !permissionManager.isGranted(perm)) {
            return ToolResult(false, "${Tool.PERMISSION_REQUIRED}:$perm")
        }
        // Run every tool on the IO dispatcher. The agent loop runs on the Main dispatcher
        // (viewModelScope), and a tool handler that does blocking work (notably the files_*
        // handlers: disk read/write/copy/recursive delete) must never block the UI thread —
        // otherwise the app janks or risks ANR, which makes actions appear unreliable/slow.
        // A handler that throws must not break the agent loop: it is reported as a failed result.
        return withContext(Dispatchers.IO) {
            try {
                handler.execute(args)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolResult(false, "Ошибка инструмента $name: ${e.message}")
            } catch (e: LinkageError) {
                // A missing/incompatible runtime class (e.g. NoClassDefFoundError from an optional
                // library such as org.tukaani:xz for 7z) is an Error, not an Exception, so it would
                // otherwise escape this dispatcher and kill the agent loop with no honest result.
                // Only LinkageError is converted (it is recoverable: the tool cannot run in this
                // build); genuinely fatal Errors (OutOfMemoryError, StackOverflowError) still
                // propagate so real crashes are not hidden. java.lang.LinkageError:
                // https://developer.android.com/reference/java/lang/LinkageError
                // https://developer.android.com/reference/java/lang/NoClassDefFoundError
                ToolResult(
                    false,
                    "Ошибка инструмента $name: отсутствует зависимость среды выполнения " +
                        "(${e.message ?: e::class.simpleName})."
                )
            }
        }
    }

    /**
     * Tolerant, schema-aware coercion of the model-produced arguments at the single decode choke
     * point. Local models (and some providers) render boolean params as the strings `"True"`/
     * `"False"` and null as `"None"` (Python-style, from the chat template); without this they reach
     * the handlers as strings and fail silently. Only properties the tool's own JSON Schema declares
     * are touched, and only scalar STRING encodings:
     * - a `boolean` property whose value is the string `true`/`false`/`1`/`0`/`yes`/`no`
     *   (case-insensitive, trimmed) becomes a real JSON boolean;
     * - the strings `"None"`/`"null"` become JSON `null`, but ONLY for properties NOT declared
     *   `string` (a genuine string param is never rewritten);
     * - every other value passes through unchanged (strings stay strings, numbers-as-strings stay
     *   strings, already-correct booleans/objects are untouched).
     *
     * A missing schema (e.g. a dynamic MCP tool) or missing `properties` leaves the object as-is, so
     * already-correct JSON keeps its exact shape. Mirrors
     * [com.andharness.ru.domain.mcp.McpSchemaMapper.buildArguments]'s rules.
     *
     * References:
     * - JsonPrimitive.isString / content — kotlinx.serialization:
     *   https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json-primitive/is-string.html
     * - JsonNull (JSON null singleton):
     *   https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json-null/
     */
    private fun coerceArguments(schema: JsonObject, obj: JsonObject): JsonObject {
        if (obj.isEmpty()) return obj
        val properties = schema["properties"] as? JsonObject ?: return obj
        val coerced = buildJsonObject {
            obj.forEach { (key, value) ->
                put(key, coerceScalar(declaredType(properties, key), value))
            }
        }
        return if (coerced == obj) obj else coerced
    }

    /** Declared JSON Schema `type` of one property, lower-cased, or `null` if absent/non-scalar. */
    private fun declaredType(properties: JsonObject, key: String): String? =
        ((properties[key] as? JsonObject)?.get("type") as? JsonPrimitive)
            ?.contentOrNull?.trim()?.lowercase()

    private fun coerceScalar(declaredType: String?, value: JsonElement): JsonElement {
        val primitive = value as? JsonPrimitive ?: return value
        if (!primitive.isString) return value
        val text = primitive.content
        return when (declaredType) {
            "string" -> value
            "boolean" ->
                parseBoolean(text)?.let { JsonPrimitive(it) }
                    ?: if (isNullText(text)) JsonNull else value
            null -> value
            else -> if (isNullText(text)) JsonNull else value
        }
    }

    private fun isNullText(text: String): Boolean =
        text.trim().lowercase().let { it == "none" || it == "null" }

    private fun parseBoolean(text: String): Boolean? = when (text.trim().lowercase()) {
        "true", "1", "yes", "да" -> true
        "false", "0", "no", "нет" -> false
        else -> null
    }

    /**
     * Lexical (BM25) discovery of tools by free-text query — the escape hatch that guarantees the
     * per-turn subset can never hide a capability. Returns matching names with a one-line
     * description; it never executes anything.
     */
    private fun runToolSearch(argumentsJson: String): ToolResult {
        val query = try {
            val obj = parser.parseToJsonElement(argumentsJson).jsonObject
            (obj["query"] as? JsonPrimitive)?.content.orEmpty().trim()
        } catch (e: Exception) {
            ""
        }
        if (query.isBlank()) {
            return ToolResult(false, "Укажите query — что нужно сделать, например «отправить смс».")
        }
        val matches = ToolRetriever.search(definitions, query, TOOL_SEARCH_LIMIT)
        if (matches.isEmpty()) {
            return ToolResult(
                true,
                "По запросу «$query» инструменты не найдены. Переформулируйте запрос другими словами."
            )
        }
        val descriptionByName = definitions.associate { it.name to it.description }
        val text = buildString {
            appendLine("Инструменты по запросу «$query» (${matches.size}):")
            matches.forEach { name ->
                val description = descriptionByName[name].orEmpty().replace('\n', ' ').trim()
                appendLine("- $name — ${description.take(TOOL_SEARCH_SNIPPET_CHARS)}")
            }
        }
        return ToolResult(true, text.trim())
    }

    /** Deterministic health check of the non-interactive tools (see [SelfTestCases]). */
    private suspend fun runSelfTest(): ToolResult {
        SelfTestCases.SEED.forEach { runCatching { execute(it.name, it.args) } }
        val passed = ArrayList<String>()
        val failed = ArrayList<String>()
        for (case in SelfTestCases.CASES) {
            val result = try {
                execute(case.name, case.args)
            } catch (e: Exception) {
                ToolResult(false, "исключение: ${e.message}")
            }
            if (result.success) {
                passed.add(case.name)
            } else {
                failed.add("${case.name}: ${result.output.replace('\n', ' ').take(140)}")
            }
        }
        val report = buildString {
            appendLine("Самотест: успешно ${passed.size}, сбой ${failed.size}, пропущено ${SelfTestCases.SKIPPED.size}.")
            if (failed.isNotEmpty()) {
                appendLine()
                appendLine("НЕ СРАБОТАЛИ:")
                failed.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("Пропущены (интерактивные/опасные — вручную): ${SelfTestCases.SKIPPED.joinToString(", ")}")
        }
        return ToolResult(true, report.toString().trim())
    }

    private companion object {
        /** How many tools tool_search may return in one answer. */
        const val TOOL_SEARCH_LIMIT = 12

        /** Max characters of each matched tool description shown by tool_search. */
        const val TOOL_SEARCH_SNIPPET_CHARS = 160
    }
}
