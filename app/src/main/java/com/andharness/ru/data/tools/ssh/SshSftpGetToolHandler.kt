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
import java.io.File

/** `ssh_sftp_get`: downloads a remote file into the safe tool root over SFTP. */
internal class SshSftpGetToolHandler(
    private val context: Context,
    private val client: SshClient,
    private val targeting: SshServerTargeting,
    private val failureText: SshFailureText,
    private val args: SshToolArgs,
) : ToolHandler {

    override val name = "ssh_sftp_get"

    override suspend fun execute(arguments: JsonObject): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                sftpGet(arguments)
            } catch (e: SshFailure) {
                ToolResult(false, failureText.describe(e))
            } catch (e: Exception) {
                ToolResult(false, "Ошибка загрузки по SFTP: ${e.message}")
            }
        }

    private suspend fun sftpGet(arguments: JsonObject): ToolResult {
        val resolution = targeting.resolve(arguments)
        val server = when (resolution) {
            is ServerResolution.Found -> resolution.server
            else -> return ToolResult(false, targeting.question(resolution))
        }
        val remote = args.string(arguments, "remote")
            ?: return ToolResult(false, "Укажите remote — путь к файлу на сервере.")
        val rawLocal = args.string(arguments, "local")
            ?: return ToolResult(
                false,
                "Укажите local — путь для сохранения относительно корня файловых инструментов, " +
                    "например Downloads/report.txt."
            )
        val timeoutSec = args.int(arguments, "timeout_sec", SshToolLimits.DEFAULT_SFTP_TIMEOUT_SEC)
            .coerceIn(1, SshToolLimits.MAX_EXEC_TIMEOUT_SEC)
        val trustHost = args.boolean(arguments, "trust_host") ?: false

        val base = try {
            resolveSafePath(context.effectiveToolRoot(), rawLocal)
        } catch (e: IllegalArgumentException) {
            return ToolResult(false, e.message ?: "Недопустимый путь local.")
        }
        val destination = if (base.isDirectory || rawLocal.endsWith("/")) {
            File(base, remote.substringAfterLast('/'))
        } else {
            base
        }

        val outcome = client.get(server, remote, destination, timeoutSec, trustHost)
        return ToolResult(
            true,
            "Скачал ${outcome.remote} с «${server.name}» в ${outcome.localPath} " +
                "(${outcome.sizeBytes} байт). Файл записан атомарно (через временный «.part» и " +
                "переименование), поэтому неполный файл не остаётся под конечным именем."
        )
    }
}
