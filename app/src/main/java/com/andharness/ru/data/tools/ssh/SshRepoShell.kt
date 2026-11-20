package com.andharness.ru.data.tools.ssh

import com.andharness.ru.data.ssh.SshExecOutcome
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.ssh.SshServer

/**
 * Shell-quoting and honest outcome formatting shared by the coding-bridge tools (`repo_*`), which
 * run git on the user's own SSH host through the same client used by `ssh_exec`.
 */
internal class SshRepoShell {

    /**
     * Quotes a remote path but keeps a leading `~` unquoted so the remote shell still expands the
     * home directory (`~/repo` → `/home/user/repo`), a common way to name a repository.
     */
    fun path(raw: String): String = when {
        raw == "~" -> "~"
        raw.startsWith("~/") -> "~/" + quote(raw.removePrefix("~/"))
        else -> quote(raw)
    }

    /**
     * POSIX-quotes one shell word. Everything is wrapped in single quotes and embedded single quotes
     * are escaped as `'\''`, so `dir`/`path` values can never break out of the command.
     */
    private fun quote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /** Formats one git/SSH outcome honestly. */
    fun text(
        server: SshServer,
        command: String,
        outcome: SshExecOutcome,
        action: String,
        note: String? = null
    ): String = buildString {
        appendLine("Сервер «${server.name}» (${server.endpoint}), $action")
        appendLine("Команда: $command")
        if (outcome.timedOut) {
            appendLine("ТАЙМАУТ: команда не завершилась вовремя и была прервана.")
        } else {
            appendLine(
                "Код возврата: ${outcome.exitStatus}" +
                    if (outcome.exitStatus != 0) " (не 0 — команда вернула ошибку)" else ""
            )
        }
        if (note != null) appendLine(note)
        appendLine("--- stdout ---")
        appendLine(outcome.stdout.ifBlank { "(пусто)" })
        appendLine("--- stderr ---")
        append(outcome.stderr.ifBlank { "(пусто)" })
        if (outcome.stdoutTruncated || outcome.stderrTruncated) {
            appendLine()
            append("Вывод обрезан (лимит ${SshToolLimits.MAX_OUTPUT_CHARS} символов на поток).")
        }
    }.trimEnd()

    /** Success only when the command actually ran and exited 0; a non-zero git result is a failure. */
    fun result(
        server: SshServer,
        command: String,
        outcome: SshExecOutcome,
        action: String,
        note: String? = null
    ): ToolResult = ToolResult(
        success = !outcome.timedOut && outcome.exitStatus == 0,
        output = text(server, command, outcome, action, note)
    )
}
