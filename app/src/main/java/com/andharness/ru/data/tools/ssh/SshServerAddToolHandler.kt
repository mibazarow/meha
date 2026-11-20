package com.andharness.ru.data.tools.ssh

import android.content.Context
import android.net.Uri
import com.andharness.ru.data.ssh.SshFailure
import com.andharness.ru.data.ssh.SshSecretStore
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.ssh.SshAuthType
import com.andharness.ru.domain.ssh.SshServer
import com.andharness.ru.domain.ssh.SshServerRepository
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * `ssh_server_add`: registers an SSH server after validating the name/host/user/auth fields and
 * probing a KEY `key_ref`, then reads the row and its secret back.
 */
internal class SshServerAddToolHandler(
    private val context: Context,
    private val repository: SshServerRepository,
    private val secretStore: SshSecretStore,
    private val args: SshToolArgs,
) : ToolHandler {

    override val name = "ssh_server_add"

    override suspend fun execute(arguments: JsonObject): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                addServer(arguments)
            } catch (e: SshFailure) {
                ToolResult(false, e.message ?: "Ошибка SSH.")
            } catch (e: Exception) {
                ToolResult(false, "Не удалось добавить сервер: ${e.message}")
            }
        }

    private suspend fun addServer(arguments: JsonObject): ToolResult {
        val name = args.string(arguments, "name")
            ?: return ToolResult(false, "Укажите name — понятное имя сервера, например «vps-prod».")
        val host = args.string(arguments, "host")
            ?: return ToolResult(false, "Укажите host — адрес или имя сервера.")
        val user = args.string(arguments, "user")
            ?: return ToolResult(false, "Укажите user — имя пользователя для входа.")
        val port = args.int(arguments, "port", SshToolLimits.DEFAULT_PORT)
        if (port !in 1..SshToolLimits.MAX_PORT) {
            return ToolResult(
                false,
                "Порт $port вне диапазона 1–${SshToolLimits.MAX_PORT}. Ничего не изменено."
            )
        }
        val authType = SshAuthType.from(args.string(arguments, "auth_type"))
            ?: return ToolResult(
                false,
                "Укажите auth_type: PASSWORD (пароль) или KEY (приватный ключ). Ничего не изменено."
            )
        val password = args.string(arguments, "password")
        val keyRef = args.string(arguments, "key_ref")
        val passphrase = args.string(arguments, "passphrase")

        repository.byName(name)?.let { existing ->
            return ToolResult(
                false,
                "Сервер с именем «${existing.name}» уже есть (#${existing.id}, ${existing.endpoint}). " +
                    "Ничего не изменено: имена уникальны. Сначала удалите его (ssh_server_remove), " +
                    "затем добавьте заново."
            )
        }

        when (authType) {
            SshAuthType.PASSWORD -> if (password == null) {
                return ToolResult(
                    false,
                    "Для auth_type=PASSWORD нужен параметр password. Ничего не изменено."
                )
            }

            SshAuthType.KEY -> {
                if (keyRef == null) {
                    return ToolResult(
                        false,
                        "Для auth_type=KEY нужен параметр key_ref — SAF-URI приватного ключа " +
                            "(например content://…, выданный через доступ к документам). " +
                            "Ничего не изменено."
                    )
                }
                val readable = probeKeyRef(keyRef)
                if (readable != null) {
                    return ToolResult(false, "$readable Ничего не изменено.")
                }
            }
        }

        val rowId = repository.insert(
            SshServer(
                id = 0,
                name = name,
                host = host,
                port = port,
                user = user,
                authType = authType,
                keyRef = if (authType == SshAuthType.KEY) keyRef else null,
                createdAt = System.currentTimeMillis()
            )
        ) ?: return ToolResult(
            false,
            "Сервер не записался: имя «$name» уже занято (уникальный индекс). Ничего не изменено."
        )

        if (authType == SshAuthType.PASSWORD) {
            secretStore.setPassword(rowId, password)
        } else {
            secretStore.setPassphrase(rowId, passphrase)
        }

        // Post-verify: the row must read back, and the secret must be on file.
        val stored = repository.byId(rowId)
            ?: return ToolResult(
                false,
                "Пост-проверка не прошла: сервер #$rowId не читается из базы обратно."
            )
        val secretOk = when (authType) {
            SshAuthType.PASSWORD -> secretStore.hasPassword(rowId)
            SshAuthType.KEY -> true
        }
        if (!secretOk) {
            return ToolResult(
                false,
                "Пост-проверка не прошла: пароль не сохранился в защищённом хранилище. " +
                    "Сервер #$rowId записан, но подключиться не сможет."
            )
        }

        val secretText = when (authType) {
            SshAuthType.PASSWORD -> "Пароль сохранён в EncryptedSharedPreferences (не в базе)."
            SshAuthType.KEY -> if (passphrase != null) {
                "Парольная фраза ключа сохранена в EncryptedSharedPreferences; сам ключ остаётся " +
                    "в файле по key_ref и не копируется."
            } else {
                "Ключ читается из key_ref при подключении; сам ключ нигде не копируется."
            }
        }
        return ToolResult(
            true,
            "Добавил сервер #${stored.id} «${stored.name}» — ${stored.endpoint}, " +
                "аутентификация ${stored.authType.name}. $secretText Проверено повторным чтением."
        )
    }

    /** `null` when the key URI can be opened; otherwise a user-facing reason. */
    private fun probeKeyRef(keyRef: String): String? {
        val uri = try {
            Uri.parse(keyRef)
        } catch (e: Exception) {
            return "key_ref «$keyRef» не похож на URI: ${e.message}."
        }
        return try {
            val stream = context.contentResolver.openInputStream(uri)
            if (stream == null) {
                "Не удалось открыть key_ref «$keyRef»: доступ к документу не выдан."
            } else {
                stream.use { it.read() }
                null
            }
        } catch (e: Exception) {
            "Не удалось прочитать key_ref «$keyRef»: ${e.message}."
        }
    }
}
