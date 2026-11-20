package com.andharness.ru.data.tools

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.audit.ToolAuditChain
import com.andharness.ru.domain.audit.ToolAuditEntry
import com.andharness.ru.domain.audit.ToolAuditRepository
import com.andharness.ru.domain.mcp.McpDynamicTools
import com.andharness.ru.domain.mcp.McpServer
import com.andharness.ru.domain.mcp.McpToolInfo
import com.andharness.ru.domain.permission.DevicePermission
import com.andharness.ru.domain.permission.PermissionManager
import com.andharness.ru.domain.tools.Tool
import com.andharness.ru.domain.tools.ToolExecutionContext
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.tools.currentToolExecutionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SMS_LIST = "sms_list"
private const val READ_SMS = "android.permission.READ_SMS"

class ToolRegistryImplTest {

    private class FakeHandler(override val name: String, private val out: String) : ToolHandler {
        override suspend fun execute(arguments: JsonObject): ToolResult = ToolResult(true, out)
    }

    private class ThrowingHandler(override val name: String) : ToolHandler {
        override suspend fun execute(arguments: JsonObject): ToolResult =
            throw IllegalStateException("boom")
    }

    /** Simulates a missing optional runtime class (e.g. org.tukaani:xz for 7z). */
    private class LinkageErrorHandler(override val name: String) : ToolHandler {
        override suspend fun execute(arguments: JsonObject): ToolResult =
            throw NoClassDefFoundError("Failed resolution of: Lorg/tukaani/xz/LZMA2Options;")
    }

    /**
     * Reports the chat/model of the run it executes in. The short suspension forces the two
     * concurrent runs to overlap, so a shared "current chat" would be caught.
     */
    private class RunContextHandler : ToolHandler {
        override val name = "clipboard_get"
        override suspend fun execute(arguments: JsonObject): ToolResult {
            delay(5)
            val ctx = currentToolExecutionContext()
            return ToolResult(true, "chat=${ctx?.chatId};model=${ctx?.model}")
        }
    }

    /** Keeps the exact arguments it received, so coercion can be asserted. */
    private class CapturingHandler(override val name: String) : ToolHandler {
        var received: JsonObject? = null
        override suspend fun execute(arguments: JsonObject): ToolResult {
            received = arguments
            return ToolResult(true, "captured")
        }
    }

    private class FakePermissionManager(var granted: Boolean) : PermissionManager {
        override fun isGranted(permission: String?): Boolean = granted
        override fun all(): List<DevicePermission> = emptyList()
    }

    /**
     * In-memory audit sink: no-op reads, records the tool names and their run chat/model.
     * Copy-on-write lists because the concurrent-isolation test writes from several threads.
     */
    private class FakeAuditRepository : ToolAuditRepository {
        val recorded = java.util.concurrent.CopyOnWriteArrayList<String>()
        val auditChatIds = java.util.concurrent.CopyOnWriteArrayList<Long?>()
        val auditModels = java.util.concurrent.CopyOnWriteArrayList<String?>()

        override suspend fun record(
            at: Long,
            chatId: Long?,
            tool: String,
            argsJson: String,
            success: Boolean,
            resultText: String,
            durationMs: Long,
            model: String?
        ): Long {
            recorded += tool
            auditChatIds += chatId
            auditModels += model
            return recorded.size.toLong()
        }

        override suspend fun recent(limit: Int): List<ToolAuditEntry> = emptyList()
        override suspend fun all(): List<ToolAuditEntry> = emptyList()
        override suspend fun count(): Int = 0
        override suspend fun verify(): ToolAuditChain.VerifyResult =
            ToolAuditChain.VerifyResult(ok = true, checked = 0)

        override suspend fun prune(now: Long) = Unit
    }

    /** No dynamic MCP tools: `toolDefinitions` empty and every name is "not an MCP tool". */
    private object NoMcpTools : McpDynamicTools {
        override suspend fun toolDefinitions(): List<Tool> = emptyList()
        override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult? = null
        override fun invalidate(serverId: String?) = Unit
        override fun seed(server: McpServer, tools: List<McpToolInfo>) = Unit
    }

    /** Always answers with a fixed result for any dynamic MCP tool name. */
    private class StubMcpTools(private val result: ToolResult) : McpDynamicTools {
        override suspend fun toolDefinitions(): List<Tool> = emptyList()
        override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult = result
        override fun invalidate(serverId: String?) = Unit
        override fun seed(server: McpServer, tools: List<McpToolInfo>) = Unit
    }

    private fun registry(
        handlers: Set<ToolHandler>,
        pm: PermissionManager,
        audit: ToolAuditRepository = FakeAuditRepository(),
        mcpTools: McpDynamicTools = NoMcpTools
    ) = ToolRegistryImpl(handlers, pm, audit, mcpTools)

    @Test
    fun grantedPermissionExecutesHandler() = runTest {
        val handler = FakeHandler(SMS_LIST, "ok-sms")
        val pm = FakePermissionManager(granted = true)
        val registry = registry(setOf(handler), pm)
        val result = registry.execute(SMS_LIST, "{}")
        assertTrue(result.success)
        assertEquals("ok-sms", result.output)
    }

    @Test
    fun missingPermissionReturnsPermissionRequired() = runTest {
        val handler = FakeHandler(SMS_LIST, "ok-sms")
        val pm = FakePermissionManager(granted = false)
        val registry = registry(setOf(handler), pm)
        val result = registry.execute(SMS_LIST, "{}")
        assertFalse(result.success)
        assertEquals("PERMISSION_REQUIRED:$READ_SMS", result.output)
    }

    @Test
    fun unknownToolIsNotImplemented() = runTest {
        val handler = FakeHandler(SMS_LIST, "ok-sms")
        val registry = registry(setOf(handler), FakePermissionManager(granted = true))
        val result = registry.execute("no_such_tool", "{}")
        assertFalse(result.success)
        assertTrue(result.output.contains("ещё не реализован"))
    }

    @Test
    fun invalidJsonArgumentsReported() = runTest {
        val handler = FakeHandler(SMS_LIST, "ok-sms")
        val registry = registry(setOf(handler), FakePermissionManager(granted = true))
        val result = registry.execute(SMS_LIST, "this is not json")
        assertFalse(result.success)
        assertTrue(result.output.startsWith("Неверные аргументы"))
    }

    @Test
    fun permissionFreeToolRunsWithoutPermission() = runTest {
        // "clipboard_get" has no needsPermission in DefaultTools.
        val handler = FakeHandler("clipboard_get", "clip")
        val pm = FakePermissionManager(granted = false)
        val registry = registry(setOf(handler), pm)
        val result = registry.execute("clipboard_get", "{}")
        assertTrue("a tool with no needsPermission must run even when pm says not granted", result.success)
        assertEquals("clip", result.output)
    }

    @Test
    fun handlerExceptionBecomesFailedResult() = runTest {
        val registry = registry(
            setOf(ThrowingHandler("clipboard_get")),
            FakePermissionManager(granted = true),
        )
        val result = registry.execute("clipboard_get", "{}")
        assertFalse(result.success)
        assertTrue(result.output.contains("Ошибка инструмента clipboard_get"))
        assertTrue(result.output.contains("boom"))
    }

    @Test
    fun handlerLinkageErrorBecomesFailedResult() = runTest {
        val audit = FakeAuditRepository()
        val registry = registry(
            setOf(LinkageErrorHandler("clipboard_get")),
            FakePermissionManager(granted = true),
            audit,
        )
        val result = registry.execute("clipboard_get", "{}")
        assertFalse(result.success)
        assertTrue(result.output.contains("отсутствует зависимость"))
        assertTrue(result.output.contains("org/tukaani/xz"))
        assertEquals(listOf("clipboard_get"), audit.recorded)
    }

    @Test
    fun everyDispatchIsAppendedToTheAuditLog() = runTest {
        val audit = FakeAuditRepository()
        val registry = registry(
            setOf(FakeHandler("clipboard_get", "clip")),
            FakePermissionManager(granted = true),
            audit
        )
        registry.execute("clipboard_get", "{}")
        registry.execute("no_such_tool", "{}")
        assertEquals(listOf("clipboard_get", "no_such_tool"), audit.recorded)
    }

    @Test
    fun dynamicMcpToolIsDispatchedToTheProvider() = runTest {
        val registry = registry(
            setOf(FakeHandler("clipboard_get", "clip")),
            FakePermissionManager(granted = true),
            mcpTools = StubMcpTools(ToolResult(true, "mcp-ok")),
        )
        val result = registry.execute("mcp_demo_echo", """{"text":"hi"}""")
        assertTrue(result.success)
        assertEquals("mcp-ok", result.output)
    }

    @Test
    fun nonMcpNameIsNeverSentToTheMcpProvider() = runTest {
        val registry = registry(
            setOf(FakeHandler("clipboard_get", "clip")),
            FakePermissionManager(granted = true),
            // A provider that would report a distinctive result if (wrongly) consulted.
            mcpTools = StubMcpTools(ToolResult(true, "should-not-be-used")),
        )
        val result = registry.execute("no_such_tool", "{}")
        assertFalse(result.success)
        assertTrue(result.output.contains("ещё не реализован"))
    }

    /**
     * The core of the per-chat isolation fix: two runs for different chats executing at the same
     * time must each see their OWN chat id (and model), and their audit entries must carry it too.
     */
    @Test
    fun concurrentRunsInDifferentChatsNeverCrossTalk() = runTest {
        val audit = FakeAuditRepository()
        val registry = registry(
            setOf(RunContextHandler()),
            FakePermissionManager(granted = true),
            audit,
        )
        val outputs = coroutineScope {
            listOf(11L to "model-a", 22L to "model-b").map { (chatId, model) ->
                async(Dispatchers.Default) {
                    withContext(ToolExecutionContext(chatId, model)) {
                        registry.execute("clipboard_get", "{}").output
                    }
                }
            }.awaitAll()
        }
        assertEquals(
            listOf("chat=11;model=model-a", "chat=22;model=model-b"),
            outputs,
        )
        // The two runs finish in an arbitrary order, so compare the recorded chats/models as sets.
        assertEquals(setOf<Long?>(11L, 22L), audit.auditChatIds.toSet())
        assertEquals(setOf<String?>("model-a", "model-b"), audit.auditModels.toSet())
    }

    /** Outside an agent run there is no run context, and the audit entry is honestly untagged. */
    @Test
    fun runContextIsAbsentOutsideAnAgentRun() = runTest {
        assertNull(currentToolExecutionContext())
        val audit = FakeAuditRepository()
        val registry = registry(
            setOf(RunContextHandler()),
            FakePermissionManager(granted = true),
            audit,
        )
        val result = registry.execute("clipboard_get", "{}")
        assertTrue(result.success)
        assertEquals("chat=null;model=null", result.output)
        assertEquals(listOf<Long?>(null), audit.auditChatIds)
        assertNull(audit.auditModels.single())
    }

    // ---- schema-aware argument coercion (Python-style True/False/None from local models) ----
    // `files_write` declares path/content as `string` and overwrite as `boolean`, so it exercises
    // both the coerced and the untouched paths against a real tool schema.

    @Test
    fun booleanStringTrueIsCoercedToRealBoolean() = runTest {
        val handler = CapturingHandler("files_write")
        val registry = registry(setOf(handler), FakePermissionManager(granted = true))
        registry.execute(
            "files_write",
            """{"path":"/sdcard/a.txt","content":"hi","overwrite":"True"}""",
        )
        val overwrite = handler.received!!.getValue("overwrite") as JsonPrimitive
        assertFalse("a \"True\" string must become a real boolean", overwrite.isString)
        assertTrue(overwrite.boolean)
    }

    @Test
    fun booleanStringFalseIsCoercedToRealBoolean() = runTest {
        val handler = CapturingHandler("files_write")
        val registry = registry(setOf(handler), FakePermissionManager(granted = true))
        registry.execute(
            "files_write",
            """{"path":"/sdcard/a.txt","content":"hi","overwrite":"false"}""",
        )
        val overwrite = handler.received!!.getValue("overwrite") as JsonPrimitive
        assertFalse("a \"false\" string must become a real boolean", overwrite.isString)
        assertFalse(overwrite.boolean)
    }

    @Test
    fun alreadyCorrectBooleanIsLeftUnchanged() = runTest {
        val handler = CapturingHandler("files_write")
        val registry = registry(setOf(handler), FakePermissionManager(granted = true))
        registry.execute(
            "files_write",
            """{"path":"/sdcard/a.txt","content":"hi","overwrite":true}""",
        )
        val overwrite = handler.received!!.getValue("overwrite") as JsonPrimitive
        assertFalse(overwrite.isString)
        assertTrue(overwrite.boolean)
    }

    @Test
    fun stringTypedParamIsNotCoerced() = runTest {
        val handler = CapturingHandler("files_write")
        val registry = registry(setOf(handler), FakePermissionManager(granted = true))
        registry.execute(
            "files_write",
            """{"path":"/sdcard/a.txt","content":"True","overwrite":true}""",
        )
        val content = handler.received!!.getValue("content") as JsonPrimitive
        assertTrue("a string-typed param must stay a string", content.isString)
        assertEquals("True", content.content)
    }

    @Test
    fun noneStringBecomesNullForNonNullStringParam() = runTest {
        val handler = CapturingHandler("files_write")
        val registry = registry(setOf(handler), FakePermissionManager(granted = true))
        registry.execute(
            "files_write",
            """{"path":"/sdcard/a.txt","content":"hi","overwrite":"None"}""",
        )
        assertTrue(
            "a \"None\" for a non-string param must become JSON null",
            handler.received!!.getValue("overwrite") is JsonNull,
        )
    }
}
