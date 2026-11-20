package com.andharness.ru.data.tools.ssh

import com.andharness.ru.data.ssh.ServerResolution
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.ssh.SshServerRepository
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `ssh_server_rename`: renames a saved server, addressing it by `server` or `server_id`. */
internal class SshServerRenameToolHandler(
    private val repository: SshServerRepository,
    private val targeting: SshServerTargeting,
    private val args: SshToolArgs,
) : ToolHandler {

    override val name = "ssh_server_rename"

    override suspend fun execute(arguments: JsonObject): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                renameServer(arguments)
            } catch (e: Exception) {
                ToolResult(false, "Не удалось переименовать сервер: ${e.message}")
            }
        }

    private suspend fun renameServer(arguments: JsonObject): ToolResult {
        val resolution = targeting.resolveEditable(arguments)
        val server = when (resolution) {
            is ServerResolution.Found -> resolution.server
            else -> return ToolResult(false, targeting.question(resolution))
        }
        val newName = args.string(arguments, "name")
            ?: return ToolResult(
                false,
                "Укажите name — новое понятное имя сервера, например «vps-prod». Ничего не изменено."
            )
        if (newName.equals(server.name, ignoreCase = true)) {
            return ToolResult(
                true,
                "Сервер #${server.id} уже называется «${server.name}» — переименование не требуется. " +
                    "Ничего не изменено."
            )
        }
        repository.byName(newName)?.let { clash ->
            if (clash.id != server.id) {
                return ToolResult(
                    false,
                    "Имя «$newName» уже занято сервером #${clash.id} (${clash.endpoint}). " +
                        "Имена уникальны. Ничего не изменено."
                )
            }
        }

        val updated = repository.rename(server.id, newName)
        if (updated <= 0) {
            return ToolResult(
                false,
                "Сервер #${server.id} «${server.name}» не переименовался (строк изменено: $updated)."
            )
        }

        // Post-verify: the row must read back with the new name.
        val stored = repository.byId(server.id)
            ?: return ToolResult(
                false,
                "Пост-проверка не прошла: сервер #${server.id} не читается из базы обратно."
            )
        if (!stored.name.equals(newName, ignoreCase = true)) {
            return ToolResult(
                false,
                "Пост-проверка не прошла: имя осталось «${stored.name}», ожидалось «$newName»."
            )
        }
        return ToolResult(
            true,
            "Переименовал сервер #${stored.id}: «${server.name}» → «${stored.name}» " +
                "(${stored.endpoint}). Проверено повторным чтением. Адрес, пользователь, " +
                "пароль/ключ и запись known_hosts не затронуты."
        )
    }
}
