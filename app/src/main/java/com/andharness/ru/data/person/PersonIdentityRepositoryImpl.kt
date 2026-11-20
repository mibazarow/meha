package com.andharness.ru.data.person

import com.andharness.ru.data.db.dao.PersonIdentityDao
import com.andharness.ru.data.db.entity.PersonIdentityEntity
import com.andharness.ru.domain.person.IdentityNormalizer
import com.andharness.ru.domain.person.IdentityType
import com.andharness.ru.domain.person.PersonIdentity
import com.andharness.ru.domain.person.PersonIdentityRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PersonIdentityRepositoryImpl @Inject constructor(
    private val dao: PersonIdentityDao
) : PersonIdentityRepository {

    override suspend fun insert(
        personId: Long,
        type: IdentityType,
        value: String,
        appPackage: String,
        label: String?
    ): Long? {
        val normalized = IdentityNormalizer.normalize(type, value, appPackage) ?: return null
        val pkg = appPackage.trim()
        val newId = dao.insert(
            PersonIdentityEntity(
                personId = personId,
                type = type.name,
                value = normalized,
                appPackage = pkg,
                label = label?.trim()?.takeIf { it.isNotEmpty() }
            )
        )
        // On a unique-index conflict `insert` returns -1; the identity is already indexed, so return
        // its existing id rather than pretending the write failed.
        if (newId != -1L) return newId
        return dao.byValue(type.name, normalized, pkg)?.id
    }

    override suspend fun byValue(
        type: IdentityType,
        value: String,
        appPackage: String
    ): PersonIdentity? {
        val normalized = IdentityNormalizer.normalize(type, value, appPackage) ?: return null
        return dao.byValue(type.name, normalized, appPackage.trim())?.toDomain()
    }

    override suspend fun personIdFor(
        type: IdentityType,
        value: String,
        appPackage: String
    ): Long? = byValue(type, value, appPackage)?.personId

    override suspend fun forPerson(personId: Long): List<PersonIdentity> =
        dao.forPerson(personId).map { it.toDomain() }

    override suspend fun link(identityId: Long, personId: Long): Boolean =
        dao.link(identityId, personId) > 0

    override suspend fun delete(identityId: Long): Int = dao.delete(identityId)

    override suspend fun deleteForPerson(personId: Long): Int = dao.deleteForPerson(personId)

    private fun PersonIdentityEntity.toDomain() = PersonIdentity(
        id = id,
        personId = personId,
        type = IdentityType.from(type),
        value = value,
        appPackage = appPackage,
        label = label
    )
}
