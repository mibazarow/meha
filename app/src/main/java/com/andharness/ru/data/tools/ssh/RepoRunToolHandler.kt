package com.andharness.ru.data.tools.ssh

import com.andharness.ru.data.ssh.ServerResolution
import com.andharness.ru.data.ssh.SshClient
import com.andharness.ru.data.ssh.SshFailure
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `repo_run`: runs an arbitrary command inside the resolved SSH repository directory. */
internal class RepoRunToolHandler(
    private val client: SshClient,
    private val targeting: SshServerTargeting,
    private val failureText: SshFailureText,
    private val args: SshToolArgs,
    private val shell: SshRepoShell,
) : ToolHandler {

    override val name = "repo_run"

    override suspend fun execute(arguments: JsonObject): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                repoRun(arguments)
            } catch (e: SshFailure) {
                ToolResult(false, failureText.describe(e))
            } catch (e: Exception) {
                ToolResult(false, "Ошибка repo_run: ${e.message}")
            }
        }

    private suspend fun repoRun(arguments: JsonObject): ToolResult {
        val resolution = targeting.resolve(arguments)
        val server = when (resolution) {
            is ServerResolution.Found -> resolution.server
            else -> return ToolResult(false, targeting.question(resolution))
        }
        val dir = args.string(arguments, "dir")
            ?: return ToolResult(false, "Укажите dir — путь к каталогу репозитория на сервере.")
        if (dir.contains('\u0000')) return ToolResult(false, "Путь dir содержит недопустимый символ NUL.")
        val command = args.string(arguments, "command")
            ?: return ToolResult(false, "Укажите command — команду для выполнения в каталоге репозитория.")
        if (command.contains('\u0000')) {
            return ToolResult(false, "Команда содержит недопустимый символ NUL.")
        }
        val timeoutArg = args.string(arguments, "timeoutMs")
        val timeoutMs = if (timeoutArg == null) {
            SshToolLimits.DEFAULT_RUN_TIMEOUT_MS
        } else {
            timeoutArg.toLongOrNull() ?: return ToolResult(
                false,
                "timeoutMs «$timeoutArg» — не число миллисекунд."
            )
        }.coerceIn(SshToolLimits.MIN_RUN_TIMEOUT_MS, SshToolLimits.MAX_RUN_TIMEOUT_MS)
        // SshClient.exec takes seconds and clamps to 1..MAX_EXEC_TIMEOUT_SEC; round up so the
        // requested millisecond budget is at least honoured.
        val timeoutSec = ((timeoutMs + 999L) / 1000L).toInt()
            .coerceIn(1, SshToolLimits.MAX_EXEC_TIMEOUT_SEC)
        val trustHost = args.boolean(arguments, "trust_host") ?: false

        val qDir = shell.path(dir)
        val fullCommand = "cd $qDir && $command"
        val outcome = client.exec(server, fullCommand, timeoutSec, trustHost)
        return shell.result(
            server,
            fullCommand,
            outcome,
            "выполнение в каталоге $qDir",
            "timeoutMs=$timeoutMs (SSH-таймаут: ${timeoutSec}с)."
        )
    }
}
