package com.andharness.ru.data.tools.misc

import com.andharness.ru.data.db.entity.MessageEntity
import com.andharness.ru.domain.tools.ToolExecutionContext
import com.andharness.ru.domain.tools.currentToolChatId
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the chat-management helpers in `ChatManage.kt`: export rendering (md/txt/json),
 * format normalisation, the `confirm` gate of the destructive tools and the `pinned` flag default.
 */
class ChatManageTest {

    private fun msg(id: Long, role: String, content: String, toolName: String? = null) =
        MessageEntity(
            id = id,
            chatId = 1,
            role = role,
            content = content,
            toolName = toolName,
            createdAt = id * 1000,
        )

    private val sample = listOf(
        msg(1, "user", "привет"),
        msg(2, "assistant", "здравствуй"),
    )

    @Test
    fun markdownRendersAuthorAndText() {
        assertEquals(
            "**Пользователь:** привет\n\n**Меха:** здравствуй\n",
            renderChatExport(sample, "md")
        )
    }

    @Test
    fun textRendersAuthorAndText() {
        assertEquals(
            "Пользователь: привет\nМеха: здравствуй\n",
            renderChatExport(sample, "txt")
        )
    }

    @Test
    fun jsonIsValidAndCarriesFields() {
        val json = renderChatExport(sample, "json")
        val parsed = Json.parseToJsonElement(json) as JsonArray
        assertEquals("one JSON object per message", 2, parsed.size)

        val first = parsed[0] as JsonObject
        assertEquals("user", first["role"]!!.jsonPrimitive.content)
        assertEquals("Пользователь", first["author"]!!.jsonPrimitive.content)
        assertEquals("привет", first["content"]!!.jsonPrimitive.content)
        assertEquals(1000L, first["createdAt"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun jsonIncludesToolNameOnlyWhenPresent() {
        val parsed = Json.parseToJsonElement(
            renderChatExport(listOf(msg(1, "tool", "лог", toolName = "files_read")), "json")
        ) as JsonArray
        val obj = parsed[0] as JsonObject
        assertEquals("files_read", obj["toolName"]!!.jsonPrimitive.content)

        val plain = Json.parseToJsonElement(
            renderChatExport(listOf(msg(1, "user", "без инструмента")), "json")
        ) as JsonArray
        assertFalse((plain[0] as JsonObject).containsKey("toolName"))
    }

    @Test
    fun emptyChatIsHonest() {
        assertEquals("Пустой чат: сообщений нет.", renderChatExport(emptyList(), "md"))
        assertEquals("Пустой чат: сообщений нет.", renderChatExport(emptyList(), "txt"))
        val parsed = Json.parseToJsonElement(renderChatExport(emptyList(), "json")) as JsonArray
        assertEquals("empty chat exports an empty JSON array", 0, parsed.size)
    }

    @Test
    fun unknownFormatFallsBackToMarkdown() {
        assertEquals(
            renderChatExport(sample, "md"),
            renderChatExport(sample, "xml")
        )
    }

    @Test
    fun formatIsTrimmedAndLowercased() {
        assertEquals("json", normalizeChatExportFormat(" JSON "))
        assertEquals("txt", normalizeChatExportFormat("Txt"))
        assertEquals("md", normalizeChatExportFormat(null))
        assertEquals("md", normalizeChatExportFormat(""))
    }

    @Test
    fun exportExtensionMatchesFormat() {
        assertEquals("md", chatExportExtension("md"))
        assertEquals("md", chatExportExtension("unknown"))
        assertEquals("json", chatExportExtension("JSON"))
    }

    @Test
    fun defaultExportNameCarriesIdTimestampAndExtension() {
        assertEquals("chat-42-1700000000000.json", defaultChatExportFileName(42, 1_700_000_000_000, "json"))
        assertEquals("chat-7-5.md", defaultChatExportFileName(7, 5, "nonsense"))
    }

    @Test
    fun confirmMustBeExplicitlyTrue() {
        assertTrue(confirmIsTrue("true"))
        assertTrue(confirmIsTrue("TRUE"))
        assertFalse("missing confirm must refuse", confirmIsTrue(null))
        assertFalse("blank confirm must refuse", confirmIsTrue("  "))
        assertFalse("explicit false must refuse", confirmIsTrue("false"))
        assertFalse("any other value must refuse", confirmIsTrue("yes"))
    }

    @Test
    fun pinnedFlagDefaultsToTrue() {
        assertTrue(parsePinnedFlag(null))
        assertTrue(parsePinnedFlag(""))
        assertTrue(parsePinnedFlag("true"))
        assertFalse(parsePinnedFlag("false"))
        assertFalse(parsePinnedFlag("FALSE"))
    }

    @Test
    fun explicitChatIdWinsOverTheCurrentChat() {
        assertEquals(5L, resolveChatId(explicit = 5L, current = 9L))
        assertEquals(
            "an omitted chat_id must default to the current run's chat",
            9L,
            resolveChatId(explicit = null, current = 9L)
        )
        assertNull("no explicit id and no run context means no chat", resolveChatId(null, null))
    }

    /**
     * The handler obtains the current chat id via [currentToolChatId], which reads the per-run
     * [ToolExecutionContext] coroutine element installed by the orchestrator around the tool loop.
     * This proves the mechanism the `chat_*` tools rely on: inside a run it resolves to that run's
     * chat, outside a run it is simply null.
     */
    @Test
    fun currentToolChatIdComesFromTheRunContext() = runTest {
        assertEquals(11L, withContext(ToolExecutionContext(11L, "m")) { currentToolChatId() })
        assertNull("outside an agent run there is no current chat", currentToolChatId())
    }
}
