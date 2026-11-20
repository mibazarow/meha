package com.andharness.ru.data.person

import com.andharness.ru.data.db.dao.PersonAliasDao
import com.andharness.ru.data.db.entity.PersonAliasEntity
import com.andharness.ru.domain.person.PersonAlias
import com.andharness.ru.domain.person.PersonAliasRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PersonAliasRepositoryImpl @Inject constructor(
    private val dao: PersonAliasDao
) : PersonAliasRepository {

    override suspend fun insert(personId: Long, alias: String): Long? {
        val trimmed = alias.trim()
        if (trimmed.isEmpty()) return null
        val newId = dao.insert(PersonAliasEntity(personId = personId, alias = trimmed))
        if (newId != -1L) return newId
        // The pair (personId, alias) already exists — return the row that is already stored.
        return dao.forPerson(personId).firstOrNull { it.alias.equals(trimmed, ignoreCase = true) }?.id
    }

    override suspend fun forPerson(personId: Long): List<PersonAlias> =
        dao.forPerson(personId).map { it.toDomain() }

    override suspend fun byAlias(alias: String): List<PersonAlias> {
        val trimmed = alias.trim()
        if (trimmed.isEmpty()) return emptyList()
        return dao.byAlias(trimmed).map { it.toDomain() }
    }

    override suspend fun delete(personId: Long, alias: String): Int {
        val trimmed = alias.trim()
        if (trimmed.isEmpty()) return 0
        return dao.delete(personId, trimmed)
    }

    override suspend fun deleteFor(personId: Long): Int = dao.deleteForPerson(personId)

    private fun PersonAliasEntity.toDomain() = PersonAlias(
        id = id,
        personId = personId,
        alias = alias
    )
}
