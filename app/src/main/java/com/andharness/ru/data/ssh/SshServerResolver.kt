package com.andharness.ru.data.ssh

import com.andharness.ru.domain.ssh.SshServer

object SshServerResolver {

    /**
     * @param servers the whole registry (already ordered by name).
     * @param name the `server` argument of the tool, or `null`.
     * @param serverId the `server_id` argument of the tool, or `null`.
     *
     * `server_id` wins when both are given (it is the unambiguous one). A name is matched exactly
     * first (case-insensitively); only when there is no exact hit is a case-insensitive "contains"
     * pass tried, and that pass is what can come back [ServerResolution.Ambiguous].
     */
    fun resolve(servers: List<SshServer>, name: String?, serverId: Long?): ServerResolution {
        if (serverId != null) {
            val byId = servers.firstOrNull { it.id == serverId } ?: return ServerResolution.NotFound
            return ServerResolution.Found(byId)
        }
        val query = name?.trim().orEmpty()
        if (query.isEmpty()) return ServerResolution.Unspecified

        servers.firstOrNull { it.name.equals(query, ignoreCase = true) }
            ?.let { return ServerResolution.Found(it) }

        val partial = servers.filter { it.name.contains(query, ignoreCase = true) }
        return when (partial.size) {
            0 -> ServerResolution.NotFound
            1 -> ServerResolution.Found(partial.first())
            else -> ServerResolution.Ambiguous(partial)
        }
    }
}
