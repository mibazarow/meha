package com.andharness.ru.domain.offline

/**
 * Pure (Android-free) decision for the offline action queue: given a FAILED tool call, may it be
 * queued and retried automatically when the network returns?
 *
 * The rule is deliberately CONSERVATIVE and is a two-part AND:
 *
 * 1. **The tool must be genuinely network-bound AND safe to retry** — see [ELIGIBLE_TOOLS].
 *    Calls that never touched the network (local config, storage, UI, memory, …) are rejected, and
 *    so is every irreversible local action (SMS, calls, deletes, files, contacts, calendar): those
 *    must NEVER be silently re-run, because a second execution could message/call/delete twice.
 * 2. **The error must be clearly network-related** — no connectivity, DNS failure or a timeout on a
 *    network call ([NETWORK_ERROR_MARKERS]). A logical failure such as `HTTP 404`, a validation
 *    error or a missing argument is NOT queued: retrying it would never help.
 *
 * This is why the decision takes BOTH the tool name and the error text: either signal alone is too
 * weak (`web_fetch` can fail with "invalid URL"; any tool can fail with "timeout").
 *
 * @param toolName canonical tool name (e.g. `http_request`).
 * @param error the failure output/message returned by the tool.
 * @return true only when the call is both retry-safe-and-network-bound and failed for a network reason.
 */
object OfflineQueuePolicy {

    /**
     * Exact tool names that are network-bound and safe to retry. Kept as an explicit allowlist (not
     * a prefix match) so a future tool can never be queued accidentally.
     *
     * - `http_request`, `web_fetch`, `web_search`, `download_file` — read/fetch over HTTP.
     * - `ssh_exec`, `ssh_sftp_list`, `ssh_sftp_get`, `ssh_sftp_put` — reach a remote host over the
     *   network. NOTE: the local `ssh_server_*` config tools are deliberately EXCLUDED: they only
     *   touch the on-device server store and never need the network.
     * - `repo_status`, `repo_diff`, `repo_log`, `repo_run`, `repo_apply_patch` — operate on a remote
     *   repository over SSH.
     *
     * `download_file` writes a file, but only AFTER a successful network fetch: when the failure is
     * a network error nothing was written, so re-running it is safe. Non-idempotent semantic risk
     * (`repo_run`/`repo_apply_patch`, `ssh_exec`/`ssh_sftp_put`) is bounded by the queue's
     * MAX_ATTEMPTS and only triggered by clear network errors, i.e. when the command very likely
     * never reached the remote.
     */
    val ELIGIBLE_TOOLS: Set<String> = setOf(
        "http_request",
        "web_fetch",
        "web_search",
        "download_file",
        "ssh_exec",
        "ssh_sftp_list",
        "ssh_sftp_get",
        "ssh_sftp_put",
        "repo_status",
        "repo_diff",
        "repo_log",
        "repo_run",
        "repo_apply_patch",
    )

    /**
     * Lower-cased substrings that mark a failure as network-related (no connectivity / DNS /
     * timeout / connection refused). Chosen to match the messages OkHttp/JVM produce and the Russian
     * hint [com.andharness.ru.domain.tools.ToolErrorHints] appends, while NOT matching logical HTTP
     * status errors (e.g. "HTTP 404" contains none of these).
     */
    private val NETWORK_ERROR_MARKERS: List<String> = listOf(
        "unknownhost",
        "unable to resolve host",
        "no address associated with host",
        "sockettimeout",
        "timed out",
        "timeout",
        "connectexception",
        "failed to connect",
        "connection refused",
        "network is unreachable",
        "no route to host",
        "нет подключения",
        "нет интернета",
        "не удалось подключиться",
        "проверьте подключение",
    )

    /** True when [toolName] is network-bound and safe to retry (see [ELIGIBLE_TOOLS]). */
    fun isEligibleTool(toolName: String): Boolean = toolName in ELIGIBLE_TOOLS

    /** True when [error] looks like a clear network failure. */
    fun isNetworkError(error: String): Boolean {
        if (error.isBlank()) return false
        val e = error.lowercase()
        return NETWORK_ERROR_MARKERS.any { e.contains(it) }
    }

    /** The queue decision; see the class doc for the exact rule. */
    fun shouldQueue(toolName: String, error: String): Boolean =
        isEligibleTool(toolName) && isNetworkError(error)
}
