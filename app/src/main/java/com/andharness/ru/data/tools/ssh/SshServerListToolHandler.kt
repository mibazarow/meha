package com.andharness.ru.data.tools.ssh

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.ssh.SshServerRepository
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `ssh_server_list`: lists registered servers without ever exposing secrets. */
internal class SshServerListToolHandler(
    private val repository: SshServerRepository,
    private val targeting: SshServerTargeting,
) : ToolHandler {

    override val name = "ssh_server_list"

    override suspend fun execute(arguments: JsonObject): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                ToolResult(true, listServers())
            } catch (e: Exception) {
                ToolResult(false, "Не удалось прочитать список серверов: ${e.message}")
            }
        }

    private suspend fun listServers(): String {
        val servers = repository.all()
        if (servers.isEmpty()) {
            return "Зарегистрированных SSH-серверов нет. Добавьте сервер через ssh_server_add " +
                "(name, host, port, user, auth_type и password или key_ref)."
        }
        return buildString {
            appendLine("SSH-серверы (${servers.size}):")
            servers.forEach { appendLine(targeting.line(it)) }
            append(
                "Честно: показаны только имена, адреса и тип аутентификации. Пароли, парольные фразы " +
                    "и приватные ключи не хранятся в базе и никогда не показываются."
            )
        }
    }
}
