package com.andharness.ru.domain.person

/**
 * Persistence for [PersonIdentity] rows (table `person_identities`, update.md §4.3).
 *
 * Values are always run through [IdentityNormalizer] before they are stored or looked up, so callers
 * pass raw handles and the repository keeps the stored form canonical.
 */
interface PersonIdentityRepository {

    /**
     * Adds the handle [value] (normalized) to [personId].
     *
     * @param appPackage the owning app package, or [IdentityNormalizer.NO_PACKAGE].
     * @param label optional display hint.
     * @return the identity id — the existing one when the same
     *   (`type`, `value`, `appPackage`) is already indexed — or `null` when [value] is not a valid
     *   handle of [type].
     */
    suspend fun insert(
        personId: Long,
        type: IdentityType,
        value: String,
        appPackage: String = IdentityNormalizer.NO_PACKAGE,
        label: String? = null
    ): Long?

    /** Finds an identity by a raw handle, normalizing it first. */
    suspend fun byValue(
        type: IdentityType,
        value: String,
        appPackage: String = IdentityNormalizer.NO_PACKAGE
    ): PersonIdentity?

    /** The person id owning the (normalized) handle, or `null` when it is not indexed. */
    suspend fun personIdFor(
        type: IdentityType,
        value: String,
        appPackage: String = IdentityNormalizer.NO_PACKAGE
    ): Long?

    suspend fun forPerson(personId: Long): List<PersonIdentity>

    /** Re-points an existing identity at another person; `false` when the identity does not exist. */
    suspend fun link(identityId: Long, personId: Long): Boolean

    /** Deletes an identity; returns the number of removed rows. */
    suspend fun delete(identityId: Long): Int

    suspend fun deleteForPerson(personId: Long): Int
}
