package com.andharness.ru.data.tools.ssh

import com.andharness.ru.data.ssh.ServerResolution
import com.andharness.ru.data.ssh.SshServerResolver
import com.andharness.ru.domain.ssh.SshServer
import com.andharness.ru.domain.ssh.SshServerRepository
import kotlinx.serialization.json.JsonObject

/**
 * Resolves the server a tool call targets and renders the honest numbered-list question the
 * update.md §5.4 disambiguation rule requires. Shared by every SSH tool so no tool ever guesses.
 */
internal class SshServerTargeting(
    private val repository: SshServerRepository,
    private val args: SshToolArgs,
) {

    suspend fun resolve(arguments: JsonObject): ServerResolution {
        val name = args.string(arguments, "server") ?: args.string(arguments, "name")
        val id = args.long(arguments, "server_id")
        return SshServerResolver.resolve(repository.all(), name, id)
    }

    /**
     * Identifier resolution for the registry-editing tools: `server` is the identifier (a name or,
     * as a convenience, a numeric id) and `server_id` wins when both are given. Unlike [resolve]
     * there is deliberately **no** fallback to a `name` argument, because those tools carry their
     * own `name` parameter (the new name).
     */
    suspend fun resolveEditable(arguments: JsonObject): ServerResolution {
        val raw = args.string(arguments, "server")
        val id = args.long(arguments, "server_id") ?: raw?.toLongOrNull()
        return SshServerResolver.resolve(repository.all(), raw, id)
    }

    /** The numbered-list question the update.md §5.4 disambiguation rule requires. */
    suspend fun question(resolution: ServerResolution): String {
        val servers = repository.all()
        val head = when (resolution) {
            is ServerResolution.Ambiguous -> {
                "Имя «${resolution.candidates.joinToString(", ") { it.name }}» подходит к нескольким " +
                    "серверам. Уточни, для какого именно:"
            }

            ServerResolution.Unspecified -> "Не указан сервер. Уточни, для какого сервера действие:"

            ServerResolution.NotFound -> "Сервер с таким именем/id не найден. Доступные серверы:"

            is ServerResolution.Found -> return "Сервер определён однозначно."
        }
        if (servers.isEmpty()) {
            return "$head\nЗарегистрированных серверов нет — сначала добавьте сервер через ssh_server_add."
        }
        return buildString {
            appendLine(head)
            servers.forEach { appendLine(line(it)) }
            append("Передай server (имя) или server_id из списка. Ничего не выполнено.")
        }
    }

    fun line(server: SshServer): String =
        "- #${server.id} ${server.name} — ${server.endpoint} — ${server.authType.name}"
}
