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

/** `repo_apply_patch`: uploads a local patch and applies it with `git apply`, post-verified. */
internal class RepoApplyPatchToolHandler(
    private val context: Context,
    private val client: SshClient,
    private val targeting: SshServerTargeting,
    private val failureText: SshFailureText,
    private val args: SshToolArgs,
    private val shell: SshRepoShell,
) : ToolHandler {

    override val name = "repo_apply_patch"

    override suspend fun execute(arguments: JsonObject): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                repoApplyPatch(arguments)
            } catch (e: SshFailure) {
                ToolResult(false, failureText.describe(e))
            } catch (e: Exception) {
                ToolResult(false, "Ошибка repo_apply_patch: ${e.message}")
            }
        }

    private suspend fun repoApplyPatch(arguments: JsonObject): ToolResult {
        val resolution = targeting.resolve(arguments)
        val server = when (resolution) {
            is ServerResolution.Found -> resolution.server
            else -> return ToolResult(false, targeting.question(resolution))
        }
        val dir = args.string(arguments, "dir")
            ?: return ToolResult(false, "Укажите dir — путь к каталогу репозитория на сервере.")
        if (dir.contains('\u0000')) return ToolResult(false, "Путь dir содержит недопустимый символ NUL.")
        val rawPatch = args.string(arguments, "patchPath")
            ?: return ToolResult(
                false,
                "Укажите patchPath — путь к локальному файлу патча относительно корня файловых " +
                    "инструментов, например Downloads/fix.patch."
            )
        val checkOnly = args.boolean(arguments, "checkOnly") ?: false
        val timeoutSec = args.int(arguments, "timeout_sec", SshToolLimits.DEFAULT_EXEC_TIMEOUT_SEC)
            .coerceIn(1, SshToolLimits.MAX_EXEC_TIMEOUT_SEC)
        val trustHost = args.boolean(arguments, "trust_host") ?: false

        val source = try {
            resolveSafePath(context.effectiveToolRoot(), rawPatch)
        } catch (e: IllegalArgumentException) {
            return ToolResult(false, e.message ?: "Недопустимый путь patchPath.")
        }
        if (!source.isFile) {
            return ToolResult(
                false,
                "Локальный файл патча не найден: ${source.absolutePath} (относительно корня " +
                    "${context.effectiveToolRoot().absolutePath})."
            )
        }

        // A unique temp file on the host: uploaded via the existing SFTP helper and always removed
        // in the finally block. Never pretend the patch was applied without git saying so (code 0).
        val remoteTemp = "/tmp/andharness-${System.currentTimeMillis()}-" +
            "${System.nanoTime() % 1_000_000}.patch"
        val qDir = shell.path(dir)
        val qTemp = shell.path(remoteTemp)
        try {
            client.put(server, source, remoteTemp, overwrite = true, timeoutSec = timeoutSec, trustHost = trustHost)

            val checkCommand = "git -C $qDir apply --check $qTemp"
            val check = client.exec(server, checkCommand, timeoutSec, trustHost)
            if (check.timedOut || check.exitStatus != 0) {
                val why = if (check.timedOut) "проверка не завершилась вовремя" else "git apply --check вернул код ${check.exitStatus}"
                return ToolResult(
                    false,
                    "Патч НЕ применён: $why (патч не подходит к текущему состоянию репозитория).\n\n" +
                        shell.text(server, checkCommand, check, "проверка применимости патча")
                )
            }
            if (checkOnly) {
                return ToolResult(
                    true,
                    "Проверка пройдена: патч ПРИМЕНИМ, но НЕ применён (checkOnly=true).\n\n" +
                        shell.text(server, checkCommand, check, "проверка применимости патча")
                )
            }

            val applyCommand = "git -C $qDir apply $qTemp"
            val apply = client.exec(server, applyCommand, timeoutSec, trustHost)
            if (apply.timedOut || apply.exitStatus != 0) {
                val why = if (apply.timedOut) "git apply не завершился вовремя" else "git apply вернул код ${apply.exitStatus}"
                return ToolResult(
                    false,
                    "Патч НЕ применён: $why (проверка прошла, но применение не удалось).\n\n" +
                        shell.text(server, applyCommand, apply, "применение патча")
                )
            }
            return ToolResult(
                true,
                "Патч применён: git apply завершился с кодом 0.\n\n" +
                    shell.text(server, applyCommand, apply, "применение патча")
            )
        } finally {
            // Best-effort cleanup; a failure here must not mask the real result.
            runCatching {
                client.exec(server, "rm -f $qTemp", SshToolLimits.CLEANUP_TIMEOUT_SEC, trustHost)
            }
        }
    }
}
