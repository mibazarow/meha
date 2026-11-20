package com.andharness.ru.data.tools.ssh

import com.andharness.ru.data.ssh.DestructiveCommandPolicy
import com.andharness.ru.data.ssh.ServerResolution
import com.andharness.ru.data.ssh.SshClient
import com.andharness.ru.data.ssh.SshFailure
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** `ssh_exec`: runs one command on a resolved server and reports stdout/stderr/exit code honouring. */
internal class SshExecToolHandler(
    private val client: SshClient,
    private val targeting: SshServerTargeting,
    private val failureText: SshFailureText,
    private val args: SshToolArgs,
) : ToolHandler {

    override val name = "ssh_exec"

    override suspend fun execute(arguments: JsonObject): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                execCommand(arguments)
            } catch (e: SshFailure) {
                ToolResult(false, failureText.describe(e))
            } catch (e: Exception) {
                ToolResult(false, "Ошибка выполнения по SSH: ${e.message}")
            }
        }

    private suspend fun execCommand(arguments: JsonObject): ToolResult {
        val resolution = targeting.resolve(arguments)
        val server = when (resolution) {
            is ServerResolution.Found -> resolution.server
            else -> return ToolResult(false, targeting.question(resolution))
        }
        val command = args.string(arguments, "command")
            ?: return ToolResult(false, "Укажите command — команду для выполнения на сервере.")
        if (command.contains('\u0000')) {
            return ToolResult(false, "Команда содержит недопустимый символ NUL.")
        }
        val timeoutSec = args.int(arguments, "timeout_sec", SshToolLimits.DEFAULT_EXEC_TIMEOUT_SEC)
            .coerceIn(1, SshToolLimits.MAX_EXEC_TIMEOUT_SEC)
        val trustHost = args.boolean(arguments, "trust_host") ?: false

        val verdict = DestructiveCommandPolicy.inspect(command)

        val outcome = client.exec(server, command, timeoutSec, trustHost)
        val header = buildString {
            append("Сервер «${server.name}» (${server.endpoint}), команда: ")
            appendLine(command)
            if (outcome.timedOut) {
                appendLine("ТАЙМАУТ: команда не завершилась за ${timeoutSec}с и была прервана.")
            } else {
                appendLine("Код возврата: ${outcome.exitStatus}" + if (outcome.exitStatus != 0) " (не 0 — команда завершилась с ошибкой)" else "")
            }
            if (verdict.destructive) {
                appendLine("Внимание: команда отмечена как деструктивная (${verdict.reasonText}).")
            }
            appendLine("--- stdout ---")
            appendLine(outcome.stdout.ifBlank { "(пусто)" })
            appendLine("--- stderr ---")
            append(outcome.stderr.ifBlank { "(пусто)" })
            if (outcome.stdoutTruncated || outcome.stderrTruncated) {
                appendLine()
                append("Вывод обрезан (лимит ${SshToolLimits.MAX_OUTPUT_CHARS} символов на поток).")
            }
        }.trimEnd()

        return ToolResult(!outcome.timedOut, header)
    }
}
