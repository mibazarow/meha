package com.andharness.ru.data.tools

import com.andharness.ru.data.db.dao.SshServerDao
import com.andharness.ru.data.db.entity.SshServerEntity
import com.andharness.ru.data.ssh.SshServerRepositoryImpl
import com.andharness.ru.domain.ssh.SshAuthType
import com.andharness.ru.domain.ssh.SshServer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the store half of BUG-003 (rename / edit a saved SSH server) with an in-memory DAO. The
 * unique-name clash itself is enforced by the SQLite unique index on `ssh_servers.name`, so the
 * handler pre-checks `byName`; here we pin that a rename/update actually persists and that blank or
 * out-of-range input is refused by the repository instead of writing a broken row.
 */
class SshServerRepositoryEditTest {

    /** Minimal in-memory [SshServerDao]; mimics the SQL of the real Room DAO. */
    private class InMemorySshServerDao : SshServerDao {
        private val rows = LinkedHashMap<Long, SshServerEntity>()
        private var nextId = 1L

        override suspend fun insert(entity: SshServerEntity): Long {
            if (rows.values.any { it.name.equals(entity.name, ignoreCase = true) }) return -1L
            val id = if (entity.id != 0L) entity.id else nextId++
            rows[id] = entity.copy(id = id)
            return id
        }

        override suspend fun all(): List<SshServerEntity> =
            rows.values.sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            )

        override suspend fun byId(id: Long): SshServerEntity? = rows[id]

        override suspend fun byName(name: String): SshServerEntity? =
            rows.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

        override suspend fun delete(id: Long): Int = if (rows.remove(id) != null) 1 else 0

        override suspend fun rename(id: Long, name: String): Int {
            val row = rows[id] ?: return 0
            rows[id] = row.copy(name = name)
            return 1
        }

        override suspend fun update(id: Long, host: String, port: Int, user: String): Int {
            val row = rows[id] ?: return 0
            rows[id] = row.copy(host = host, port = port, user = user)
            return 1
        }
    }

    private suspend fun seed(repository: SshServerRepositoryImpl, name: String): Long {
        val id = repository.insert(
            SshServer(
                id = 0,
                name = name,
                host = "$name.example.com",
                port = 22,
                user = "root",
                authType = SshAuthType.PASSWORD,
                keyRef = null,
                createdAt = 0L
            )
        )
        assertNotNull("seed insert for «$name» must succeed", id)
        return id!!
    }

    @Test
    fun renamePersistsTheNewName() = runTest {
        val repository = SshServerRepositoryImpl(InMemorySshServerDao())
        val id = seed(repository, "vps-prod")

        assertEquals(1, repository.rename(id, "vps-main"))

        assertEquals("vps-main", repository.byId(id)?.name)
        assertNull("old name must no longer resolve", repository.byName("vps-prod"))
        assertEquals(id, repository.byName("vps-main")?.id)
    }

    @Test
    fun renameTrimsAndRefusesBlank() = runTest {
        val repository = SshServerRepositoryImpl(InMemorySshServerDao())
        val id = seed(repository, "vps-prod")

        assertEquals(1, repository.rename(id, "  vps-main  "))
        assertEquals("vps-main", repository.byId(id)?.name)
        assertEquals(0, repository.rename(id, "   "))
        assertEquals("vps-main", repository.byId(id)?.name)
    }

    @Test
    fun renameOfMissingIdChangesNothing() = runTest {
        val repository = SshServerRepositoryImpl(InMemorySshServerDao())
        seed(repository, "vps-prod")

        assertEquals(0, repository.rename(999L, "ghost"))
        assertNull(repository.byId(999L))
    }

    @Test
    fun updateMergedFieldsPersist() = runTest {
        val repository = SshServerRepositoryImpl(InMemorySshServerDao())
        val id = seed(repository, "vps-prod")

        val current = repository.byId(id)!!
        assertEquals(1, repository.update(id, "10.0.0.5", 2222, "deploy"))

        val stored = repository.byId(id)!!
        assertEquals("10.0.0.5", stored.host)
        assertEquals(2222, stored.port)
        assertEquals("deploy", stored.user)
        assertEquals("name and auth are untouched by update", current.name, stored.name)
        assertEquals(current.authType, stored.authType)
    }

    @Test
    fun updateRefusesBlankFieldsAndOutOfRangePort() = runTest {
        val repository = SshServerRepositoryImpl(InMemorySshServerDao())
        val id = seed(repository, "vps-prod")

        assertEquals(0, repository.update(id, "   ", 2222, "deploy"))
        assertEquals(0, repository.update(id, "10.0.0.5", 2222, "  "))
        assertEquals(0, repository.update(id, "10.0.0.5", 0, "deploy"))
        assertEquals(0, repository.update(id, "10.0.0.5", 70000, "deploy"))

        val stored = repository.byId(id)!!
        assertEquals("vps-prod.example.com", stored.host)
        assertEquals(22, stored.port)
        assertEquals("root", stored.user)
    }

    @Test
    fun insertStillRefusesADuplicateName() = runTest {
        val repository = SshServerRepositoryImpl(InMemorySshServerDao())
        seed(repository, "vps-prod")

        assertNull(
            repository.insert(
                SshServer(
                    id = 0,
                    name = "VPS-PROD",
                    host = "other.example.com",
                    port = 22,
                    user = "root",
                    authType = SshAuthType.PASSWORD,
                    keyRef = null,
                    createdAt = 0L
                )
            )
        )
    }
}
