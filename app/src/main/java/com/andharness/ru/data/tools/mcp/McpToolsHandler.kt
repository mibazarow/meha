package com.andharness.ru.data.tools.mcp

import com.andharness.ru.data.mcp.McpClient
import com.andharness.ru.data.mcp.McpConnectResult
import com.andharness.ru.data.mcp.McpSecretStore
import com.andharness.ru.data.mcp.McpServerStore
import com.andharness.ru.data.mcp.McpWriteOutcome
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.mcp.McpDynamicTools
import com.andharness.ru.domain.mcp.McpServer
import com.andharness.ru.domain.mcp.McpServerRules
import com.andharness.ru.domain.mcp.McpTransport
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * The four MCP management tools, so the agent can configure MCP servers ENTIRELY FROM CHAT (there
 * is no dedicated settings screen):
 *
 * - `mcp_server_add`    — add/update a server (token stored encrypted, never echoed);
 * - `mcp_server_list`   — read-only view with a status hint;
 * - `mcp_server_remove` — delete a server and its stored token;
 * - `mcp_server_test`   — connect, `initialize`, `tools/list`; honest errors.
 *
 * None are dangerous and none touch system data: they only edit Меха's own bounded registry. The
 * remote `tools/call` is exposed to the agent through [McpDynamicTools] (the dynamic tool provider):
 * adding/removing a server invalidates its cached tool list, and a successful test seeds it with the
 * tools it just discovered, so a fresh server becomes callable without waiting for the cache TTL.
 */
class McpToolsHandler(
    private val store: McpServerStore,
    private val secrets: McpSecretStore,
    private val client: McpClient,
    private val dynamicTools: McpDynamicTools,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "mcp_server_add"

            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val name = arguments.str("name")
                        ?: return@withContext ToolResult(false, "Укажите name — короткое имя MCP-сервера.")
                    val url = arguments.str("url")
                        ?: return@withContext ToolResult(false, "Укажите url — полный адрес MCP-сервера.")
                    val transportRaw = arguments.str("transport")
                    val transport = McpTransport.parse(transportRaw)
                        ?: return@withContext ToolResult(
                            false,
                            "Неизвестный транспорт «$transportRaw»: используйте auto, streamable_http или sse."
                        )
                    val headerName = arguments.str("auth_header_name")
                    val headerValue = arguments.str("auth_header_value")
                    if (headerName == null && headerValue != null) {
                        return@withContext ToolResult(
                            false,
                            "Укажите auth_header_name вместе с auth_header_value (например, Authorization)."
                        )
                    }

                    val result = store.put(
                        id = null,
                        name = name,
                        url = url,
                        transport = transport,
                        authHeaderName = headerName,
                    )
                    val stored = result.server
                    when (result.outcome) {
                        McpWriteOutcome.INVALID -> ToolResult(
                            false,
                            "Некорректные данные: имя до ${McpServerRules.MAX_NAME_CHARS} символов, " +
                                "URL http(s):// с хостом, а имя заголовка — корректным (не Host/Content-Type)."
                        )
                        McpWriteOutcome.LIMIT -> ToolResult(
                            false,
                            "Достигнут предел в ${com.andharness.ru.domain.mcp.McpServerCodec.MAX_SERVERS} " +
                                "MCP-серверов. Удалите ненужный через mcp_server_remove."
                        )
                        McpWriteOutcome.NOT_FOUND -> ToolResult(false, "Сервер не найден.")
                        McpWriteOutcome.CREATED, McpWriteOutcome.UPDATED -> {
                            if (stored == null) {
                                return@withContext ToolResult(false, "Не удалось сохранить MCP-сервер.")
                            }
                            if (stored.authHeaderName == null) {
                                secrets.forget(stored.id)
                            } else if (headerValue != null) {
                                secrets.setAuthHeaderValue(stored.id, headerValue)
                            }
                            // The URL/transport/name may have changed: drop any cached tool list so
                            // the next turn reconnects and re-exposes the current tools.
                            dynamicTools.invalidate(stored.id)
                            val verb = if (result.outcome == McpWriteOutcome.CREATED) "добавлен" else "обновлён"
                            val tokenNote = when {
                                stored.authHeaderName == null -> ""
                                headerValue != null -> " Токен сохранён зашифрованно."
                                secrets.hasAuthHeaderValue(stored.id) -> " Используется ранее сохранённый токен."
                                else -> " Заголовок задан, но значение токена не указано."
                            }
                            ToolResult(
                                true,
                                "MCP-сервер «${stored.name}» $verb (id ${stored.id}, транспорт " +
                                    "${stored.transport.name.lowercase(Locale.ROOT)}).$tokenNote " +
                                    "Проверьте связь: mcp_server_test (name=\"${stored.name}\")."
                            )
                        }
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка добавления MCP-сервера: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "mcp_server_list"

            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val servers = store.list()
                    if (servers.isEmpty()) {
                        ToolResult(
                            true,
                            "MCP-серверов нет. Добавьте: mcp_server_add (name, url)."
                        )
                    } else {
                        val lines = servers.joinToString("\n") { describe(it) }
                        ToolResult(
                            true,
                            "MCP-серверы (${servers.size}/${com.andharness.ru.domain.mcp.McpServerCodec.MAX_SERVERS}):\n$lines"
                        )
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка чтения списка MCP-серверов: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "mcp_server_remove"

            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val key = arguments.str("name")
                        ?: return@withContext ToolResult(false, "Укажите name — имя (или id) MCP-сервера.")
                    val removed = store.delete(key)
                    if (removed == null) {
                        ToolResult(false, "MCP-сервер «$key» не найден — посмотрите mcp_server_list.")
                    } else {
                        secrets.forget(removed.id)
                        dynamicTools.invalidate(removed.id)
                        ToolResult(true, "MCP-сервер «${removed.name}» удалён (вместе с сохранённым токеном).")
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка удаления MCP-сервера: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "mcp_server_test"

            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val key = arguments.str("name")
                        ?: return@withContext ToolResult(false, "Укажите name — имя (или id) MCP-сервера.")
                    val server = store.get(key)
                        ?: return@withContext ToolResult(
                            false,
                            "MCP-сервер «$key» не найден — посмотрите mcp_server_list."
                        )
                    val token = server.authHeaderName?.let { secrets.authHeaderValue(server.id) }
                    val transport = server.transport.name.lowercase(Locale.ROOT)
                    when (val result = client.test(server, token)) {
                        is McpConnectResult.Ok -> {
                            // Seed the dynamic provider with the freshly discovered tools, so a
                            // just-tested server's tools become callable immediately.
                            dynamicTools.seed(server, result.tools)
                            val toolNames = result.tools.map { it.name }
                            val shown = toolNames.take(TOOL_NAME_LIMIT)
                            val tail = if (toolNames.size > shown.size) {
                                "\n…и ещё ${toolNames.size - shown.size}."
                            } else {
                                ""
                            }
                            val disabledNote = if (!server.enabled) " Сервер помечен выключенным." else ""
                            val body = if (toolNames.isEmpty()) {
                                "Сервер не объявил ни одного инструмента."
                            } else {
                                "Инструменты (${toolNames.size}): " + shown.joinToString(", ") + tail
                            }
                            ToolResult(
                                true,
                                "MCP-сервер «${server.name}» доступен.$disabledNote\n" +
                                    "Сервер: ${result.serverInfo.name} ${result.serverInfo.version} " +
                                    "(протокол ${result.serverInfo.protocolVersion}).\n" +
                                    "Транспорт: ${result.transport.name.lowercase(Locale.ROOT)} " +
                                    "(настроен: $transport).\n$body"
                            )
                        }
                        is McpConnectResult.Error -> ToolResult(
                            false,
                            "MCP-сервер «${server.name}» недоступен: ${result.message}"
                        )
                    }
                } catch (e: Exception) {
                    ToolResult(false, "Ошибка проверки MCP-сервера: ${e.message}")
                }
            }
        },
    )

    private fun describe(server: McpServer): String {
        val state = if (server.enabled) "включён" else "выключен"
        val auth = when {
            server.authHeaderName == null -> "без токена"
            secrets.hasAuthHeaderValue(server.id) -> "токен задан"
            else -> "токен не задан"
        }
        val hint = if (server.enabled) {
            "проверьте связь: mcp_server_test"
        } else {
            "выключен — re-add через mcp_server_add, чтобы включить"
        }
        return "- [${server.id}] ${server.name} — ${server.url} " +
            "(${server.transport.name.lowercase(Locale.ROOT)}, $state, $auth; $hint)"
    }

    private companion object {
        /** How many discovered tool names to print in one honest test report. */
        const val TOOL_NAME_LIMIT = 25
    }
}
