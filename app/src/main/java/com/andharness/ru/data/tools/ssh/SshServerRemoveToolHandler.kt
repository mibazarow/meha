package com.andharness.ru.data.tools.ssh

import com.andharness.ru.data.ssh.KnownHostsStore
import com.andharness.ru.data.ssh.ServerResolution
import com.andharness.ru.data.ssh.SshSecretStore
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.ssh.SshServerRepository
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `ssh_server_remove`: deletes a server together with its secret and known_hosts entry. */
internal class SshServerRemoveToolHandler(
    private val repository: SshServerRepository,
    private val secretStore: SshSecretStore,
    private val knownHostsStore: KnownHostsStore,
    private val targeting: SshServerTargeting,
) : ToolHandler {

    override val name = "ssh_server_remove"

    override suspend fun execute(arguments: JsonObject): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                removeServer(arguments)
            } catch (e: Exception) {
                ToolResult(false, "Не удалось удалить сервер: ${e.message}")
            }
        }

    private suspend fun removeServer(arguments: JsonObject): ToolResult {
        val resolution = targeting.resolve(arguments)
        val server = when (resolution) {
            is ServerResolution.Found -> resolution.server
            else -> return ToolResult(false, targeting.question(resolution))
        }
        val removed = repository.remove(server.id)
        if (removed <= 0) {
            return ToolResult(
                false,
                "Сервер #${server.id} «${server.name}» не удалился (строк удалено: $removed)."
            )
        }
        secretStore.forget(server.id)
        knownHostsStore.forget(server.host, server.port)

        // Post-verify: the row must be gone, and the secret must be gone with it.
        if (repository.byId(server.id) != null) {
            return ToolResult(
                false,
                "Пост-проверка не прошла: сервер #${server.id} всё ещё читается из базы."
            )
        }
        val secretGone = !secretStore.hasPassword(server.id)
        return ToolResult(
            true,
            "Удалил сервер #${server.id} «${server.name}» (${server.endpoint}) вместе с сохранённым " +
                "паролем/парольной фразой и записью в known_hosts. " +
                if (secretGone) "Проверено повторным чтением." else
                "Секрет удалить не удалось — проверьте вручную."
        )
    }
}
