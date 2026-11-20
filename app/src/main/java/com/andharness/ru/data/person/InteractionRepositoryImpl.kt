package com.andharness.ru.data.person

import com.andharness.ru.data.db.dao.InteractionDao
import com.andharness.ru.data.db.entity.InteractionEntity
import com.andharness.ru.domain.person.Interaction
import com.andharness.ru.domain.person.InteractionChannel
import com.andharness.ru.domain.person.InteractionDirection
import com.andharness.ru.domain.person.InteractionRepository
import com.andharness.ru.domain.person.InteractionSearchTerms
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InteractionRepositoryImpl @Inject constructor(
    private val dao: InteractionDao
) : InteractionRepository {

    override suspend fun insert(interaction: Interaction): Long = dao.insert(
        InteractionEntity(
            personId = interaction.personId,
            channel = interaction.channel.name,
            direction = interaction.direction.name,
            at = interaction.at,
            title = interaction.title,
            summary = interaction.summary,
            externalRef = interaction.externalRef,
            sourceApp = interaction.sourceApp
        )
    )

    override suspend fun recentFor(personId: Long, limit: Int): List<Interaction> =
        dao.recentFor(personId, limit).map { it.toDomain() }

    override suspend fun recentOverall(limit: Int): List<Interaction> =
        dao.recentOverall(limit).map { it.toDomain() }

    override suspend fun search(query: String, limit: Int): List<Interaction> {
        val terms = InteractionSearchTerms.expand(query)
        if (terms.isEmpty()) return emptyList()
        return searchByTerms(terms, limit) { term -> dao.search(term, limit) }
    }

    override suspend fun searchForPerson(personId: Long, query: String, limit: Int): List<Interaction> {
        val terms = InteractionSearchTerms.expand(query)
        if (terms.isEmpty()) return emptyList()
        return searchByTerms(terms, limit) { term -> dao.searchForPerson(personId, term, limit) }
    }

    /**
     * Runs [lookup] once per expanded term, merges the rows by id and returns the newest [limit].
     * A single term is the common case (plain text) and skips the merge entirely.
     */
    private suspend fun searchByTerms(
        terms: List<String>,
        limit: Int,
        lookup: suspend (String) -> List<InteractionEntity>,
    ): List<Interaction> {
        if (terms.size == 1) return lookup(terms.first()).map { it.toDomain() }
        val merged = LinkedHashMap<Long, InteractionEntity>()
        terms.forEach { term -> lookup(term).forEach { merged[it.id] = it } }
        return merged.values.sortedByDescending { it.at }.take(limit).map { it.toDomain() }
    }

    override suspend fun existsByExternalRef(personId: Long, externalRef: String): Boolean {
        val ref = externalRef.trim()
        if (ref.isEmpty()) return false
        return dao.countByExternalRef(personId, ref) > 0
    }

    override suspend fun countFor(personId: Long): Int = dao.countForPerson(personId)

    override suspend fun deleteFor(personId: Long): Int = dao.deleteForPerson(personId)

    private fun InteractionEntity.toDomain() = Interaction(
        id = id,
        personId = personId,
        channel = InteractionChannel.from(channel) ?: InteractionChannel.CHAT,
        direction = InteractionDirection.from(direction),
        at = at,
        title = title,
        summary = summary,
        externalRef = externalRef,
        sourceApp = sourceApp
    )
}
