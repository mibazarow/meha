package com.andharness.ru.data.tools.ssh

import com.andharness.ru.data.ssh.ServerResolution
import com.andharness.ru.data.ssh.SshClient
import com.andharness.ru.data.ssh.SshFailure
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `repo_status`: runs `git status` on the resolved SSH repository. */
internal class RepoStatusToolHandler(
    private val client: SshClient,
    private val targeting: SshServerTargeting,
    private val failureText: SshFailureText,
    private val args: SshToolArgs,
    private val shell: SshRepoShell,
) : ToolHandler {

    override val name = "repo_status"

    override suspend fun execute(arguments: JsonObject): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                repoStatus(arguments)
            } catch (e: SshFailure) {
                ToolResult(false, failureText.describe(e))
            } catch (e: Exception) {
                ToolResult(false, "Ошибка repo_status: ${e.message}")
            }
        }

    private suspend fun repoStatus(arguments: JsonObject): ToolResult {
        val resolution = targeting.resolve(arguments)
        val server = when (resolution) {
            is ServerResolution.Found -> resolution.server
            else -> return ToolResult(false, targeting.question(resolution))
        }
        val dir = args.string(arguments, "dir")
            ?: return ToolResult(false, "Укажите dir — путь к каталогу репозитория на сервере.")
        if (dir.contains('\u0000')) return ToolResult(false, "Путь dir содержит недопустимый символ NUL.")
        val short = args.boolean(arguments, "short") ?: true
        val timeoutSec = args.int(arguments, "timeout_sec", SshToolLimits.DEFAULT_EXEC_TIMEOUT_SEC)
            .coerceIn(1, SshToolLimits.MAX_EXEC_TIMEOUT_SEC)
        val trustHost = args.boolean(arguments, "trust_host") ?: false

        val qDir = shell.path(dir)
        val command = if (short) {
            "git -C $qDir status --short --branch"
        } else {
            "git -C $qDir status"
        }
        val outcome = client.exec(server, command, timeoutSec, trustHost)
        return shell.result(server, command, outcome, "git status каталога $qDir")
    }
}
