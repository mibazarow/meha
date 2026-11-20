package com.andharness.ru.data.tools.ssh

import com.andharness.ru.data.ssh.ServerResolution
import com.andharness.ru.data.ssh.SshClient
import com.andharness.ru.data.ssh.SshFailure
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `repo_log`: runs `git log --oneline` with a bounded entry count on the SSH repository. */
internal class RepoLogToolHandler(
    private val client: SshClient,
    private val targeting: SshServerTargeting,
    private val failureText: SshFailureText,
    private val args: SshToolArgs,
    private val shell: SshRepoShell,
) : ToolHandler {

    override val name = "repo_log"

    override suspend fun execute(arguments: JsonObject): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                repoLog(arguments)
            } catch (e: SshFailure) {
                ToolResult(false, failureText.describe(e))
            } catch (e: Exception) {
                ToolResult(false, "Ошибка repo_log: ${e.message}")
            }
        }

    private suspend fun repoLog(arguments: JsonObject): ToolResult {
        val resolution = targeting.resolve(arguments)
        val server = when (resolution) {
            is ServerResolution.Found -> resolution.server
            else -> return ToolResult(false, targeting.question(resolution))
        }
        val dir = args.string(arguments, "dir")
            ?: return ToolResult(false, "Укажите dir — путь к каталогу репозитория на сервере.")
        if (dir.contains('\u0000')) return ToolResult(false, "Путь dir содержит недопустимый символ NUL.")
        val limitArg = args.string(arguments, "limit")
        val requested = if (limitArg == null) {
            SshToolLimits.DEFAULT_LOG_LIMIT
        } else {
            limitArg.toIntOrNull() ?: return ToolResult(
                false,
                "limit «$limitArg» — не число. Укажите целое от 1 до ${SshToolLimits.MAX_LOG_LIMIT}."
            )
        }
        val limit = requested.coerceIn(1, SshToolLimits.MAX_LOG_LIMIT)
        val timeoutSec = args.int(arguments, "timeout_sec", SshToolLimits.DEFAULT_EXEC_TIMEOUT_SEC)
            .coerceIn(1, SshToolLimits.MAX_EXEC_TIMEOUT_SEC)
        val trustHost = args.boolean(arguments, "trust_host") ?: false

        val qDir = shell.path(dir)
        val command = "git -C $qDir log --oneline -n $limit"
        val outcome = client.exec(server, command, timeoutSec, trustHost)
        val note = if (requested != limit) {
            "Запрошен limit=$requested, применён $limit (допустимо 1–${SshToolLimits.MAX_LOG_LIMIT})."
        } else {
            null
        }
        return shell.result(server, command, outcome, "git log каталога $qDir", note)
    }
}
