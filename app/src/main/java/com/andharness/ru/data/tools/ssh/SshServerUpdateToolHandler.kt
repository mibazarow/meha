package com.andharness.ru.data.tools.ssh

import com.andharness.ru.data.ssh.ServerResolution
import com.andharness.ru.data.ssh.SshSecretStore
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.ssh.SshAuthType
import com.andharness.ru.domain.ssh.SshServerRepository
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `ssh_server_update`: updates the provided fields of a saved server, keeping omitted ones. */
internal class SshServerUpdateToolHandler(
    private val repository: SshServerRepository,
    private val secretStore: SshSecretStore,
    private val targeting: SshServerTargeting,
    private val args: SshToolArgs,
) : ToolHandler {

    override val name = "ssh_server_update"

    override suspend fun execute(arguments: JsonObject): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                updateServer(arguments)
            } catch (e: Exception) {
                ToolResult(false, "Не удалось изменить сервер: ${e.message}")
            }
        }

    private suspend fun updateServer(arguments: JsonObject): ToolResult {
        val resolution = targeting.resolveEditable(arguments)
        val server = when (resolution) {
            is ServerResolution.Found -> resolution.server
            else -> return ToolResult(false, targeting.question(resolution))
        }
        val host = args.string(arguments, "host")
        val portArg = args.string(arguments, "port")
        val user = args.string(arguments, "user")
        val secret = args.string(arguments, "secret")
        if (host == null && portArg == null && user == null && secret == null) {
            return ToolResult(
                false,
                "Нечего обновлять: укажите host, port, user и/или secret. Ничего не изменено."
            )
        }
        val port = if (portArg == null) {
            server.port
        } else {
            portArg.toIntOrNull() ?: return ToolResult(
                false,
                "Порт «$portArg» — не число. Ничего не изменено."
            )
        }
        if (port !in 1..SshToolLimits.MAX_PORT) {
            return ToolResult(
                false,
                "Порт $port вне диапазона 1–${SshToolLimits.MAX_PORT}. Ничего не изменено."
            )
        }
        val newHost = host ?: server.host
        val newUser = user ?: server.user

        val updated = repository.update(server.id, newHost, port, newUser)
        if (updated <= 0) {
            return ToolResult(
                false,
                "Сервер #${server.id} «${server.name}» не изменился (строк обновлено: $updated). " +
                    "Проверьте, что host и user не пустые."
            )
        }

        val secretNote = if (secret == null) {
            "Секрет не менялся."
        } else {
            when (server.authType) {
                SshAuthType.PASSWORD -> {
                    secretStore.setPassword(server.id, secret)
                    "Пароль обновлён в EncryptedSharedPreferences (не в базе)."
                }

                SshAuthType.KEY -> {
                    secretStore.setPassphrase(server.id, secret)
                    "Парольная фраза ключа обновлена в EncryptedSharedPreferences; сам ключ " +
                        "остаётся в файле по key_ref."
                }
            }
        }

        // Post-verify: the row must read back with the new non-secret fields.
        val stored = repository.byId(server.id)
            ?: return ToolResult(
                false,
                "Пост-проверка не прошла: сервер #${server.id} не читается из базы обратно."
            )
        if (stored.host != newHost || stored.port != port || stored.user != newUser) {
            return ToolResult(
                false,
                "Пост-проверка не прошла: изменения не сохранились " +
                    "(в базе ${stored.endpoint}, ожидалось $newUser@$newHost:$port)."
            )
        }
        val hostChanged = stored.host != server.host || stored.port != server.port
        val knownHostsNote = if (hostChanged) {
            " Адрес изменился: ключ нового адреса ещё не известен — при первом подключении " +
                "потребуется trust_host=true."
        } else {
            ""
        }
        return ToolResult(
            true,
            "Обновил сервер #${stored.id} «${stored.name}» — ${stored.endpoint}. $secretNote" +
                "$knownHostsNote Проверено повторным чтением."
        )
    }
}
