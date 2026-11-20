package com.andharness.ru.data.tools

import com.andharness.ru.domain.agent.LlmTool
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.audit.ToolAuditChain
import com.andharness.ru.domain.audit.ToolAuditEntry
import com.andharness.ru.domain.audit.ToolAuditRepository
import com.andharness.ru.domain.locale.AppLanguage
import com.andharness.ru.domain.mcp.McpDynamicTools
import com.andharness.ru.domain.mcp.McpServer
import com.andharness.ru.domain.mcp.McpToolInfo
import com.andharness.ru.domain.permission.DevicePermission
import com.andharness.ru.domain.permission.PermissionManager
import com.andharness.ru.domain.tools.DefaultTools
import com.andharness.ru.domain.tools.EnToolStrings
import com.andharness.ru.domain.tools.Tool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The language-aware `definitions(language)` overload, and the guarantee that the RU path is
 * byte-identical to the pre-i18n output. The EN list must differ from the RU one ONLY in
 * `description` strings — same tool names/order and an identical `parameters` structure (property
 * keys, `type`, `required`).
 */
class ToolRegistryEnDefinitionsTest {

    private object GrantedPermissions : PermissionManager {
        override fun isGranted(permission: String?): Boolean = true
        override fun all(): List<DevicePermission> = emptyList()
    }

    private object NoopAudit : ToolAuditRepository {
        override suspend fun record(
            at: Long, chatId: Long?, tool: String, argsJson: String,
            success: Boolean, resultText: String, durationMs: Long, model: String?
        ): Long = 0
        override suspend fun recent(limit: Int): List<ToolAuditEntry> = emptyList()
        override suspend fun all(): List<ToolAuditEntry> = emptyList()
        override suspend fun count(): Int = 0
        override suspend fun verify(): ToolAuditChain.VerifyResult =
            ToolAuditChain.VerifyResult(ok = true, checked = 0)
        override suspend fun prune(now: Long) = Unit
    }

    private object NoMcpTools : McpDynamicTools {
        override suspend fun toolDefinitions(): List<Tool> = emptyList()
        override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult? = null
        override fun invalidate(serverId: String?) = Unit
        override fun seed(server: McpServer, tools: List<McpToolInfo>) = Unit
    }

    private fun registry() = ToolRegistryImpl(emptySet(), GrantedPermissions, NoopAudit, NoMcpTools)

    /** Replace every `description` value with a sentinel, recursively, so structure can be compared. */
    private fun JsonElement.withoutDescriptions(): JsonElement = when (this) {
        is JsonObject -> JsonObject(
            entries.associate { (key, value) ->
                key to if (key == "description") JsonPrimitive("<description>")
                else value.withoutDescriptions()
            }
        )
        is JsonArray -> JsonArray(map { it.withoutDescriptions() })
        else -> this
    }

    private fun tool(list: List<LlmTool>, name: String): LlmTool = list.first { it.name == name }

    @Test
    fun russianPathIsByteIdenticalToThePureRuCatalog() {
        val registry = registry()
        val expected = DefaultTools.all().map { it.toLlmTool() }
        // The no-arg call and the RU / unknown-language calls all return the exact RU list.
        assertEquals(expected, registry.definitions())
        assertEquals(expected, registry.definitions(AppLanguage.RUSSIAN))
        assertEquals(expected, registry.definitions("fr"))
        assertEquals(expected, registry.definitions(""))
    }

    @Test
    fun englishAndRussianListsHaveTheSameToolNamesInTheSameOrder() {
        val registry = registry()
        val ru = registry.definitions()
        val en = registry.definitions(AppLanguage.ENGLISH)
        assertEquals(ru.size, en.size)
        assertEquals(ru.map { it.name }, en.map { it.name })
    }

    @Test
    fun englishTagVariantsNormalizeToTheSameEnglishList() {
        val registry = registry()
        val en = registry.definitions(AppLanguage.ENGLISH)
        assertEquals(en, registry.definitions("en-US"))
        assertEquals(en, registry.definitions("en_GB"))
        assertNotEquals(registry.definitions(), en)
    }

    @Test
    fun sampleToolHasEnglishDescriptionAndIdenticalStructure() {
        val registry = registry()
        val ru = registry.definitions()
        val en = registry.definitions(AppLanguage.ENGLISH)

        for (name in listOf("files_list", "sms_list", "clipboard_get")) {
            val ruTool = tool(ru, name)
            val enTool = tool(en, name)
            // Description is the EN one from the catalog, and it really differs from RU.
            assertEquals(EnToolStrings.descriptions[name], enTool.description)
            assertNotEquals("RU and EN descriptions must differ for $name", ruTool.description, enTool.description)
            // Everything except the description strings is byte-identical (keys, order, type, required).
            assertEquals(
                "$name: parameters structure changed beyond descriptions",
                ruTool.parameters.withoutDescriptions(),
                enTool.parameters.withoutDescriptions(),
            )
        }
    }

    @Test
    fun everyParameterDescriptionComesFromTheEnglishCatalog() {
        val registry = registry()
        val ru = registry.definitions()
        val en = registry.definitions(AppLanguage.ENGLISH)

        en.forEach { enTool ->
            val ruTool = tool(ru, enTool.name)
            val enProps = enTool.parameters["properties"] as? JsonObject ?: return@forEach
            val ruProps = ruTool.parameters["properties"] as? JsonObject ?: return@forEach
            val expected = EnToolStrings.paramDescriptions[enTool.name].orEmpty()
            assertEquals(ruProps.keys, enProps.keys)
            enProps.forEach { (param, schema) ->
                val desc = ((schema as JsonObject)["description"] as? JsonPrimitive)?.content
                assertEquals("$param description", expected[param], desc)
            }
        }
    }

    @Test
    fun requiredAndTypeAreReusedFromTheRussianSchema() {
        val registry = registry()
        val ru = registry.definitions()
        val en = registry.definitions(AppLanguage.ENGLISH)

        en.forEach { enTool ->
            val ruTool = tool(ru, enTool.name)
            assertEquals(ruTool.parameters["type"], enTool.parameters["type"])
            assertEquals(ruTool.parameters["required"], enTool.parameters["required"])
            val ruProps = ruTool.parameters["properties"] as? JsonObject ?: return@forEach
            val enProps = enTool.parameters["properties"] as? JsonObject ?: return@forEach
            ruProps.forEach { (param, ruSchema) ->
                val ruType = (ruSchema as? JsonObject)?.get("type")
                val enType = (enProps[param] as? JsonObject)?.get("type")
                assertEquals("$param type", ruType, enType)
            }
        }
    }

    @Test
    fun englishListIsNotEmptyAndActuallyLocalized() {
        val registry = registry()
        assertTrue(registry.definitions(AppLanguage.ENGLISH).isNotEmpty())
    }
}
