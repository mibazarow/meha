package com.andharness.ru.data.ssh

import com.andharness.ru.data.db.dao.SshServerDao
import com.andharness.ru.data.db.entity.SshServerEntity
import com.andharness.ru.domain.ssh.SshAuthType
import com.andharness.ru.domain.ssh.SshServer
import com.andharness.ru.domain.ssh.SshServerRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SshServerRepositoryImpl @Inject constructor(
    private val dao: SshServerDao
) : SshServerRepository {

    override suspend fun all(): List<SshServer> = dao.all().map { it.toDomain() }

    override suspend fun byId(id: Long): SshServer? = dao.byId(id)?.toDomain()

    override suspend fun byName(name: String): SshServer? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        return dao.byName(trimmed)?.toDomain()
    }

    override suspend fun insert(server: SshServer): Long? {
        val name = server.name.trim()
        if (name.isEmpty()) return null
        val host = server.host.trim()
        if (host.isEmpty()) return null
        val user = server.user.trim()
        if (user.isEmpty()) return null
        val rowId = dao.insert(
            SshServerEntity(
                name = name,
                host = host,
                port = server.port,
                user = user,
                authType = SshAuthType.nameOf(server.authType),
                keyRef = server.keyRef?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = server.createdAt
            )
        )
        return if (rowId == -1L) null else rowId
    }

    override suspend fun remove(id: Long): Int = dao.delete(id)

    override suspend fun rename(id: Long, name: String): Int {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return 0
        return dao.rename(id, trimmed)
    }

    override suspend fun update(id: Long, host: String, port: Int, user: String): Int {
        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty()) return 0
        val trimmedUser = user.trim()
        if (trimmedUser.isEmpty()) return 0
        if (port < MIN_PORT || port > MAX_PORT) return 0
        return dao.update(id, trimmedHost, port, trimmedUser)
    }

    /**
     * The `authType` column is only ever written by [insert] with a valid enum name, so the
     * `PASSWORD` fallback below is unreachable for rows this repository created; it exists so a
     * hand-edited/corrupt row still maps to a usable model instead of crashing a listing.
     */
    private fun SshServerEntity.toDomain() = SshServer(
        id = id,
        name = name,
        host = host,
        port = port,
        user = user,
        authType = SshAuthType.from(authType) ?: SshAuthType.PASSWORD,
        keyRef = keyRef,
        createdAt = createdAt
    )

    private companion object {
        const val MIN_PORT = 1
        const val MAX_PORT = 65535
    }
}
