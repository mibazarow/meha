package com.andharness.ru.domain.person

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [PersonResolver] (update.md §4.5): no Android runtime, no network, fixed clock.
 *
 * The tests pin the documented, deterministic behaviour: exact identity wins, fuzzy name matching
 * tolerates typos/diminutives/transliteration, channel context and recency/frequency are used as
 * tie-breakers, and an unclear answer is reported as ambiguous instead of being guessed.
 */
class PersonResolverTest {

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val HOUR = 60L * 60L * 1000L
        const val DAY = 24L * HOUR
    }

    private fun person(id: Long, name: String) = Person(
        id = id,
        displayName = name,
        notes = "",
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun identity(
        id: Long,
        personId: Long,
        type: IdentityType,
        value: String,
        appPackage: String = IdentityNormalizer.NO_PACKAGE
    ) = PersonIdentity(
        id = id,
        personId = personId,
        type = type,
        value = value,
        appPackage = appPackage,
        label = null
    )

    private fun candidate(
        person: Person,
        identities: List<PersonIdentity> = emptyList(),
        aliases: List<String> = emptyList(),
        lastInteractionAt: Long? = null,
        lastInteractionChannel: InteractionChannel? = null,
        interactionCount: Int = 0
    ) = PersonResolver.PersonCandidate(
        person = person,
        identities = identities,
        aliases = aliases,
        lastInteractionAt = lastInteractionAt,
        lastInteractionChannel = lastInteractionChannel,
        interactionCount = interactionCount
    )

    // ---- canonicalization / transliteration ----

    @Test
    fun canonicalNameTransliteratesAndFoldsPunctuation() {
        assertEquals("serega", PersonResolver.canonicalName("  Серёга  "))
        assertEquals("serega", PersonResolver.canonicalName("@Serega"))
        assertEquals("sergey petrov", PersonResolver.canonicalName("Сергей  Петров!"))
        assertEquals("", PersonResolver.canonicalName("   "))
        assertEquals("", PersonResolver.canonicalName(null))
    }

    @Test
    fun preferredIdentityTypeFollowsTheChannel() {
        assertEquals(IdentityType.PHONE, PersonResolver.preferredIdentityType(InteractionChannel.SMS))
        assertEquals(IdentityType.PHONE, PersonResolver.preferredIdentityType(InteractionChannel.CALL))
        assertEquals(
            IdentityType.MESSENGER,
            PersonResolver.preferredIdentityType(InteractionChannel.MESSENGER)
        )
        assertEquals(IdentityType.EMAIL, PersonResolver.preferredIdentityType(InteractionChannel.EMAIL))
        assertNull(PersonResolver.preferredIdentityType(InteractionChannel.CHAT))
        assertNull(PersonResolver.preferredIdentityType(null))
    }

    /** A name-only match must never outrank an exact identity match, whatever bonuses apply. */
    @Test
    fun nameOnlyScoreCannotReachTheIdentityFloor() {
        assertTrue(
            PersonResolver.NAME_WEIGHT + PersonResolver.MAX_BONUS <
                PersonResolver.IDENTITY_FLOOR
        )
    }

    // ---- exact identity ----

    @Test
    fun exactIdentityBeatsNameSimilarity() {
        val byName = candidate(person(1, "Serega"), aliases = listOf("Serega"))
        val byIdentity = candidate(
            person(2, "Sergey Petrov"),
            identities = listOf(
                identity(10, 2, IdentityType.MESSENGER, "@serega", "org.telegram.messenger")
            )
        )

        val result = PersonResolver.resolve("@serega", listOf(byName, byIdentity), now = NOW)

        assertEquals(PersonResolver.ResolveOutcome.RESOLVED, result.outcome)
        assertEquals(2L, result.best?.person?.id)
        assertTrue(result.best!!.identityExact)
        assertTrue(result.best!!.score >= PersonResolver.IDENTITY_FLOOR)
        // The name-only candidate is still reported, just ranked below.
        assertEquals(1L, result.matches[1].person.id)
        assertFalse(result.matches[1].identityExact)
    }

    @Test
    fun phoneQueryMatchesTheNormalizedIdentity() {
        val owner = candidate(
            person(1, "Иван Петров"),
            identities = listOf(identity(1, 1, IdentityType.PHONE, "+79991234567"))
        )

        val result = PersonResolver.resolve("8 999 123 45 67", listOf(owner), now = NOW)

        assertEquals(PersonResolver.ResolveOutcome.RESOLVED, result.outcome)
        assertTrue(result.best!!.identityExact)
        assertEquals("PHONE +79991234567", result.best!!.identityMatched)
        assertEquals(IdentityType.PHONE, PersonResolver.identityExactness(owner.identities, "8 999 123 45 67")?.type)
    }

    // ---- fuzzy name / alias matching ----

    @Test
    fun typoInNameStillResolves() {
        val sergey = candidate(person(7, "Сергей"))

        val result = PersonResolver.resolve("Сергеи", listOf(sergey), now = NOW)

        assertEquals(PersonResolver.ResolveOutcome.RESOLVED, result.outcome)
        assertEquals(7L, result.best?.person?.id)
        assertTrue(result.best!!.nameSimilarity >= 0.8)
    }

    @Test
    fun diminutiveMatchesViaConsonantSkeleton() {
        val sergey = candidate(person(7, "Сергей"))

        val result = PersonResolver.resolve("Серёга", listOf(sergey), now = NOW)

        assertEquals(PersonResolver.ResolveOutcome.RESOLVED, result.outcome)
        assertEquals(7L, result.best?.person?.id)
        assertTrue(result.best!!.nameSimilarity >= 0.85)
    }

    @Test
    fun transliterationMatches() {
        val serega = candidate(person(3, "Серёга"))

        val result = PersonResolver.resolve("Serega", listOf(serega), now = NOW)

        assertEquals(PersonResolver.ResolveOutcome.RESOLVED, result.outcome)
        assertEquals(1.0, result.best!!.nameSimilarity, 0.0001)
    }

    @Test
    fun aliasMatchResolves() {
        val sergey = candidate(person(5, "Сергей Петров"), aliases = listOf("Серёга", "Serega"))

        val result = PersonResolver.resolve("серёга", listOf(sergey), now = NOW)

        assertEquals(PersonResolver.ResolveOutcome.RESOLVED, result.outcome)
        assertEquals(5L, result.best?.person?.id)
    }

    // ---- channel context ----

    @Test
    fun channelContextPrefersTheMatchingIdentityType() {
        val withPhone = candidate(
            person(1, "Сергей"),
            identities = listOf(identity(1, 1, IdentityType.PHONE, "+79990000000"))
        )
        val withMessenger = candidate(
            person(2, "Сергей"),
            identities = listOf(identity(2, 2, IdentityType.MESSENGER, "serega_tg", "org.telegram.messenger"))
        )

        val sms = PersonResolver.resolve("Сергей", listOf(withPhone, withMessenger), InteractionChannel.SMS, NOW)
        assertEquals(1L, sms.best?.person?.id)
        assertEquals(PersonResolver.ResolveOutcome.RESOLVED, sms.outcome)

        val messenger =
            PersonResolver.resolve("Сергей", listOf(withPhone, withMessenger), InteractionChannel.MESSENGER, NOW)
        assertEquals(2L, messenger.best?.person?.id)
        assertEquals(PersonResolver.ResolveOutcome.RESOLVED, messenger.outcome)
    }

    // ---- recency / frequency ----

    @Test
    fun recencyBreaksTiesAndKeepsTheOutcomeHonest() {
        val recent = candidate(
            person(1, "Сергей"),
            lastInteractionAt = NOW - HOUR,
            lastInteractionChannel = InteractionChannel.SMS,
            interactionCount = 1
        )
        val stale = candidate(
            person(2, "Сергей"),
            lastInteractionAt = NOW - 60 * DAY,
            interactionCount = 1
        )

        val result = PersonResolver.resolve("Сергей", listOf(stale, recent), now = NOW)

        assertEquals(1L, result.best?.person?.id)
        assertTrue(result.best!!.score > result.matches[1].score)
        // Recency alone is not a clear enough margin to answer without asking — that is honest.
        assertEquals(PersonResolver.ResolveOutcome.AMBIGUOUS, result.outcome)
    }

    @Test
    fun frequencyIncreasesTheScore() {
        val frequent = candidate(person(1, "Сергей"), interactionCount = 50)
        val rare = candidate(person(2, "Сергей"), interactionCount = 0)

        val result = PersonResolver.resolve("Сергей", listOf(rare, frequent), now = NOW)

        assertEquals(1L, result.best?.person?.id)
        assertTrue(result.best!!.score > result.matches[1].score)
    }

    // ---- ambiguity / not found ----

    @Test
    fun twoEquallyGoodCandidatesAreAmbiguous() {
        val first = candidate(person(1, "Сергей"))
        val second = candidate(person(2, "Сергей"))

        val result = PersonResolver.resolve("Сергей", listOf(first, second), now = NOW)

        assertEquals(PersonResolver.ResolveOutcome.AMBIGUOUS, result.outcome)
        assertFalse(result.isUnambiguous)
        assertEquals(2, result.matches.size)
    }

    @Test
    fun sameFirstNameForDifferentPeopleIsAmbiguous() {
        val petrov = candidate(person(1, "Сергей Петров"))
        val ivanov = candidate(person(2, "Сергей Иванов"))

        val result = PersonResolver.resolve("Сергей", listOf(petrov, ivanov), now = NOW)

        assertEquals(PersonResolver.ResolveOutcome.AMBIGUOUS, result.outcome)
    }

    @Test
    fun singleStrongCandidateIsUnambiguous() {
        val sergey = candidate(person(9, "Сергей"))

        val result = PersonResolver.resolve("Сергей", listOf(sergey), now = NOW)

        assertEquals(PersonResolver.ResolveOutcome.RESOLVED, result.outcome)
        assertTrue(result.isUnambiguous)
        assertEquals(9L, result.best?.person?.id)
    }

    @Test
    fun unrelatedQueryIsNotFound() {
        val ivan = candidate(person(1, "Иван"))

        val result = PersonResolver.resolve("Сергей", listOf(ivan), now = NOW)

        assertEquals(PersonResolver.ResolveOutcome.NOT_FOUND, result.outcome)
        assertTrue(result.matches.isEmpty())
        assertFalse(result.isUnambiguous)
    }

    @Test
    fun blankQueryOrEmptyPoolIsNotFound() {
        assertEquals(
            PersonResolver.ResolveOutcome.NOT_FOUND,
            PersonResolver.resolve("   ", listOf(candidate(person(1, "Сергей"))), now = NOW).outcome
        )
        assertEquals(
            PersonResolver.ResolveOutcome.NOT_FOUND,
            PersonResolver.resolve("Сергей", emptyList(), now = NOW).outcome
        )
    }
}
