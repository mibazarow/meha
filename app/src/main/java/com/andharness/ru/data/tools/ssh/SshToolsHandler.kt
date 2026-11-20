package com.andharness.ru.data.tools.ssh

import android.content.Context
import com.andharness.ru.data.ssh.KnownHostsStore
import com.andharness.ru.data.ssh.SshClient
import com.andharness.ru.data.ssh.SshSecretStore
import com.andharness.ru.domain.ssh.SshServerRepository
import com.andharness.ru.domain.tools.ToolHandler

/**
 * The SSH surface of the agent (update.md §5.3): the server registry, remote command execution and
 * SFTP, on top of [SshClient]. This class is a thin coordinator that wires one [ToolHandler] per
 * tool, in their original registration order.
 *
 * The honesty rules of update.md §5.4 are implemented by the per-tool handlers, not in the UI:
 * - `ssh_exec` runs a **destructive-command heuristic** ([SshExecToolHandler] via
 *   `DestructiveCommandPolicy`) purely for labelling: a flagged command is executed immediately
 *   (there is no app-side confirmation any more) and the result simply carries a "flagged
 *   destructive" warning. The legacy `confirm_dangerous` parameter is accepted for schema
 *   compatibility but ignored.
 * - A server that is unnamed or ambiguous yields a **numbered list and a question** — never a guess
 *   ([SshServerTargeting]).
 * - An unknown host key is reported **with its fingerprint** and the connection is refused;
 *   `trust_host=true` is the only way to remember it, and a *changed* key is always refused.
 * - Nothing is interactive: no PTY, no password prompt — `sudo` asking for a password fails.
 * - SFTP local paths are resolved through `resolveSafePath` against `effectiveToolRoot`; the SSH
 *   tools deliberately do **not** request `MANAGE_EXTERNAL_STORAGE`, so without "All files access"
 *   they read and write the app-private root only.
 * - Secrets never appear in a result: passwords/passphrases live in [SshSecretStore] and private
 *   keys are read from their SAF URI at connect time.
 *
 * The `repo_*` tools form the coding bridge: Android cannot run git locally, so they are thin,
 * honest wrappers that run git on the user's own configured SSH host through the same [SshClient].
 * Every argument that lands in the shell is POSIX-quoted ([SshRepoShell]); output and time are
 * bounded by [SshClient] (24k characters per stream, hard timeout cap), and the real exit code is
 * reported. References: git CLI (https://git-scm.com/docs/git), git-status
 * (https://git-scm.com/docs/git-status), git-diff (https://git-scm.com/docs/git-diff), git-log
 * (https://git-scm.com/docs/git-log), git-apply (https://git-scm.com/docs/git-apply).
 */
class SshToolsHandler(
    private val context: Context,
    private val repository: SshServerRepository,
    private val secretStore: SshSecretStore,
    private val knownHostsStore: KnownHostsStore,
    private val client: SshClient
) {

    fun handlers(): List<ToolHandler> {
        val args = SshToolArgs()
        val targeting = SshServerTargeting(repository, args)
        val failureText = SshFailureText()
        val shell = SshRepoShell()
        return listOf(
            SshServerAddToolHandler(context, repository, secretStore, args),
            SshServerListToolHandler(repository, targeting),
            SshServerRemoveToolHandler(repository, secretStore, knownHostsStore, targeting),
            SshServerRenameToolHandler(repository, targeting, args),
            SshServerUpdateToolHandler(repository, secretStore, targeting, args),
            SshExecToolHandler(client, targeting, failureText, args),
            SshSftpListToolHandler(client, targeting, failureText, args),
            SshSftpGetToolHandler(context, client, targeting, failureText, args),
            SshSftpPutToolHandler(context, client, targeting, failureText, args),
            RepoStatusToolHandler(client, targeting, failureText, args, shell),
            RepoDiffToolHandler(client, targeting, failureText, args, shell),
            RepoLogToolHandler(client, targeting, failureText, args, shell),
            RepoApplyPatchToolHandler(context, client, targeting, failureText, args, shell),
            RepoRunToolHandler(client, targeting, failureText, args, shell),
        )
    }
}
