package com.andharness.ru.data.tools.ssh

import android.content.Context
import com.andharness.ru.data.ssh.ServerResolution
import com.andharness.ru.data.ssh.SshClient
import com.andharness.ru.data.ssh.SshFailure
import com.andharness.ru.data.tools.effectiveToolRoot
import com.andharness.ru.data.tools.resolveSafePath
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `ssh_sftp_put`: uploads a local file from the safe tool root to a remote path over SFTP. */
internal class SshSftpPutToolHandler(
    private val context: Context,
    private val client: SshClient,
    private val targeting: SshServerTargeting,
    private val failureText: SshFailureText,
    private val args: SshToolArgs,
) : ToolHandler {

    override val name = "ssh_sftp_put"

    override suspend fun execute(arguments: JsonObject): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                sftpPut(arguments)
            } catch (e: SshFailure) {
                ToolResult(false, failureText.describe(e))
            } catch (e: Exception) {
                ToolResult(false, "Ошибка выгрузки по SFTP: ${e.message}")
            }
        }

    private suspend fun sftpPut(arguments: JsonObject): ToolResult {
        val resolution = targeting.resolve(arguments)
        val server = when (resolution) {
            is ServerResolution.Found -> resolution.server
            else -> return ToolResult(false, targeting.question(resolution))
        }
        val local = args.string(arguments, "local")
            ?: return ToolResult(
                false,
                "Укажите local — путь к файлу относительно корня файловых инструментов."
            )
        val remote = args.string(arguments, "remote")
            ?: return ToolResult(false, "Укажите remote — путь назначения на сервере.")
        val overwrite = args.boolean(arguments, "overwrite") ?: false
        val timeoutSec = args.int(arguments, "timeout_sec", SshToolLimits.DEFAULT_SFTP_TIMEOUT_SEC)
            .coerceIn(1, SshToolLimits.MAX_EXEC_TIMEOUT_SEC)
        val trustHost = args.boolean(arguments, "trust_host") ?: false

        val source = try {
            resolveSafePath(context.effectiveToolRoot(), local)
        } catch (e: IllegalArgumentException) {
            return ToolResult(false, e.message ?: "Недопустимый путь local.")
        }
        if (!source.isFile) {
            return ToolResult(
                false,
                "Локальный файл не найден: ${source.absolutePath} (относительно корня " +
                    "${context.effectiveToolRoot().absolutePath})."
            )
        }

        val outcome = client.put(server, source, remote, overwrite, timeoutSec, trustHost)
        return ToolResult(
            true,
            "Выгрузил ${outcome.localPath} на «${server.name}» в ${outcome.remote} " +
                "(${outcome.sizeBytes} байт). Перезапись: ${if (overwrite) "разрешена" else "запрещена"}. " +
                "Проверить результат можно через ssh_sftp_list."
        )
    }
}
