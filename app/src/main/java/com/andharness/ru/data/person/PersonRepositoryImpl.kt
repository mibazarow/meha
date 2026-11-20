package com.andharness.ru.data.person

import androidx.room.withTransaction
import com.andharness.ru.data.db.AppDatabase
import com.andharness.ru.data.db.dao.InteractionDao
import com.andharness.ru.data.db.dao.PersonAliasDao
import com.andharness.ru.data.db.dao.PersonDao
import com.andharness.ru.data.db.dao.PersonIdentityDao
import com.andharness.ru.data.db.entity.PersonEntity
import com.andharness.ru.domain.person.Person
import com.andharness.ru.domain.person.PersonRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PersonRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val personDao: PersonDao,
    private val identityDao: PersonIdentityDao,
    private val interactionDao: InteractionDao,
    private val aliasDao: PersonAliasDao
) : PersonRepository {

    override suspend fun insert(displayName: String, notes: String): Long {
        val now = System.currentTimeMillis()
        return personDao.insert(
            PersonEntity(
                displayName = displayName.trim(),
                notes = notes,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    override suspend fun get(id: Long): Person? = personDao.byId(id)?.toDomain()

    override suspend fun searchByName(query: String, limit: Int): List<Person> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return personDao.searchByName(trimmed, limit).map { it.toDomain() }
    }

    override suspend fun byAlias(alias: String, limit: Int): List<Person> {
        val trimmed = alias.trim()
        if (trimmed.isEmpty()) return emptyList()
        val ids = aliasDao.byAlias(trimmed).map { it.personId }.distinct().take(limit)
        if (ids.isEmpty()) return emptyList()
        return personDao.byIds(ids).map { it.toDomain() }
    }

    override suspend fun recent(limit: Int): List<Person> =
        personDao.recent(limit).map { it.toDomain() }

    override suspend fun update(person: Person) {
        personDao.updateFields(
            id = person.id,
            displayName = person.displayName.trim(),
            notes = person.notes,
            updatedAt = System.currentTimeMillis()
        )
    }

    override suspend fun delete(id: Long): Int = personDao.delete(id)

    override suspend fun merge(fromId: Long, intoId: Long): Boolean {
        // A person cannot be merged into itself, and both sides must exist.
        if (fromId == intoId) return false
        val from = personDao.byId(fromId) ?: return false
        val into = personDao.byId(intoId) ?: return false

        return db.withTransaction {
            // `UPDATE OR IGNORE` keeps unique indices from aborting the merge; rows it skips (the
            // target already has that identity/alias) are then dropped with the source person.
            identityDao.reassign(from.id, into.id)
            identityDao.deleteForPerson(from.id)
            interactionDao.reassign(from.id, into.id)
            aliasDao.reassign(from.id, into.id)
            aliasDao.deleteForPerson(from.id)
            personDao.delete(from.id)
            personDao.updateFields(
                id = into.id,
                displayName = into.displayName,
                notes = into.notes,
                updatedAt = System.currentTimeMillis()
            )
            true
        }
    }

    override suspend fun count(): Int = personDao.count()

    private fun PersonEntity.toDomain() = Person(
        id = id,
        displayName = displayName,
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
