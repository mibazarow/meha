package com.andharness.ru.data.tools.ssh

import com.andharness.ru.data.ssh.ServerResolution
import com.andharness.ru.data.ssh.SftpEntry
import com.andharness.ru.data.ssh.SshClient
import com.andharness.ru.data.ssh.SshFailure
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/** `ssh_sftp_list`: lists a remote directory over SFTP with an honest, bounded listing. */
internal class SshSftpListToolHandler(
    private val client: SshClient,
    private val targeting: SshServerTargeting,
    private val failureText: SshFailureText,
    private val args: SshToolArgs,
) : ToolHandler {

    override val name = "ssh_sftp_list"

    override suspend fun execute(arguments: JsonObject): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                sftpList(arguments)
            } catch (e: SshFailure) {
                ToolResult(false, failureText.describe(e))
            } catch (e: Exception) {
                ToolResult(false, "Ошибка SFTP-листинга: ${e.message}")
            }
        }

    private suspend fun sftpList(arguments: JsonObject): ToolResult {
        val resolution = targeting.resolve(arguments)
        val server = when (resolution) {
            is ServerResolution.Found -> resolution.server
            else -> return ToolResult(false, targeting.question(resolution))
        }
        val path = args.string(arguments, "path") ?: "."
        val limit = args.int(arguments, "limit", SshToolLimits.DEFAULT_LIST_LIMIT)
            .coerceIn(1, SshToolLimits.MAX_LIST_LIMIT)
        val timeoutSec = args.int(arguments, "timeout_sec", SshToolLimits.DEFAULT_SFTP_TIMEOUT_SEC)
            .coerceIn(1, SshToolLimits.MAX_EXEC_TIMEOUT_SEC)
        val trustHost = args.boolean(arguments, "trust_host") ?: false

        val outcome = client.list(server, path, limit, timeoutSec, trustHost)
        return ToolResult(
            true,
            buildString {
                append("SFTP-листинг «${server.name}»: ${outcome.path} (${outcome.entries.size} записей")
                if (outcome.truncated) append(", показаны не все — лимит $limit")
                appendLine("):")
                if (outcome.entries.isEmpty()) {
                    appendLine("  (пусто)")
                } else {
                    outcome.entries.forEach { appendLine(entryLine(it)) }
                }
                append("Честно: timestamps не показываются, листинг ограничен $limit записями на вызов.")
            }
        )
    }

    private fun entryLine(entry: SftpEntry): String = buildString {
        append("  ")
        append(if (entry.directory) "d " else "- ")
        append(entry.permissions)
        append("  ")
        append(String.format(Locale.US, "%12d", entry.sizeBytes))
        append("  ")
        append(entry.name)
    }
}
