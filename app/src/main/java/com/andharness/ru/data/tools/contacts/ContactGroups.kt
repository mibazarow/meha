package com.andharness.ru.data.tools.contacts

/**
 * Android-free decision logic for the contact-group write tools (`contact_group_create`,
 * `contact_group_assign`).
 *
 * A group is one `ContactsContract.Groups` row; its owning account is stored in the
 * `SyncColumns.ACCOUNT_TYPE` / `SyncColumns.ACCOUNT_NAME` columns (both null = a purely local
 * group). A membership is a `ContactsContract.Data` row whose MIMETYPE is
 * `GroupMembership.CONTENT_ITEM_TYPE`; the target group is referenced by
 * `GroupMembership.GROUP_ROW_ID` (an alias of `Data.DATA1`, type INTEGER). Exactly one of
 * `GROUP_ROW_ID` / `GROUP_SOURCE_ID` must be set when inserting such a row.
 *
 * References:
 * - `ContactsContract.Groups` (CONTENT_URI, TITLE, ACCOUNT_TYPE/ACCOUNT_NAME, GROUP_VISIBLE):
 *   https://developer.android.com/reference/android/provider/ContactsContract.Groups
 * - `ContactsContract.GroupsColumns` (column semantics):
 *   https://developer.android.com/reference/android/provider/ContactsContract.GroupsColumns
 * - `ContactsContract.CommonDataKinds.GroupMembership` (CONTENT_ITEM_TYPE,
 *   GROUP_ROW_ID = data1, GROUP_SOURCE_ID):
 *   https://developer.android.com/reference/android/provider/ContactsContract.CommonDataKinds.GroupMembership
 * - `ContactsContract.Data` (RAW_CONTACT_ID / CONTACT_ID / generic DATA1..DATA15):
 *   https://developer.android.com/reference/android/provider/ContactsContract.Data
 *
 * Covered by the JVM test `ContactGroupsTest`.
 */
object ContactGroups {

    /** A group's owning account; both fields are null for a purely local group. */
    data class Account(val type: String?, val name: String?)

    /** A group row: its id, title and owning account. */
    data class GroupRef(
        val id: Long,
        val title: String?,
        val accountType: String? = null,
        val accountName: String? = null,
    )

    /** A raw contact plus the account it belongs to. */
    data class RawContactRef(
        val rawContactId: Long,
        val accountType: String?,
        val accountName: String?,
    )

    sealed interface AccountResolution {
        data class Resolved(val account: Account) : AccountResolution

        data class Invalid(val message: String) : AccountResolution
    }

    sealed interface GroupResolution {
        data class Resolved(val groupId: Long) : GroupResolution

        data class Invalid(val message: String) : GroupResolution
    }

    /** What `contact_group_assign` should do for the requested (contact, group, remove) triple. */
    sealed interface MembershipDecision {
        data object Insert : MembershipDecision
        data object AlreadyMember : MembershipDecision
        data object Delete : MembershipDecision
        data object NotMember : MembershipDecision
    }

    /**
     * Picks the account a new group is created in. Both explicitly requested values win when both
     * are given; specifying only one of them is rejected (the pair identifies the account, so a
     * half-specified account is ambiguous). When neither is given the [fallback] account is used
     * (nulls = create a local group).
     */
    fun resolveGroupAccount(
        requestedType: String?,
        requestedName: String?,
        fallbackType: String?,
        fallbackName: String?,
    ): AccountResolution {
        val type = requestedType?.trim().orEmpty()
        val name = requestedName?.trim().orEmpty()
        if (type.isEmpty() && name.isEmpty()) {
            return AccountResolution.Resolved(Account(fallbackType, fallbackName))
        }
        if (type.isEmpty() || name.isEmpty()) {
            return AccountResolution.Invalid(
                "Укажите и account_type, и account_name одновременно (либо не указывайте ни одного).",
            )
        }
        return AccountResolution.Resolved(Account(type, name))
    }

    /**
     * Resolves which group the membership applies to: an explicit [groupId] (validated against the
     * known [groups]) or a [title] matched case-insensitively. An ambiguous title is rejected and
     * the caller must pass `group_id`; a missing title is a user-facing error that points at
     * `contact_group_create` (this tool never silently invents a group).
     */
    fun resolveGroupId(groupId: String?, title: String?, groups: List<GroupRef>): GroupResolution {
        val idText = groupId?.trim().orEmpty()
        if (idText.isNotEmpty()) {
            val parsed = idText.toLongOrNull()
                ?: return GroupResolution.Invalid("group_id должен быть числом.")
            return if (groups.any { it.id == parsed }) {
                GroupResolution.Resolved(parsed)
            } else {
                GroupResolution.Invalid("Группа $parsed не найдена.")
            }
        }
        val wanted = title?.trim().orEmpty()
        if (wanted.isEmpty()) {
            return GroupResolution.Invalid("Укажите group_id или group_title.")
        }
        val matches = groups.filter { it.title?.trim().equals(wanted, ignoreCase = true) }
        return when {
            matches.isEmpty() -> GroupResolution.Invalid(
                "Группа «$wanted» не найдена. Создайте её через contact_group_create.",
            )
            matches.size > 1 -> GroupResolution.Invalid(
                "Найдено несколько групп «$wanted»; укажите group_id.",
            )
            else -> GroupResolution.Resolved(matches.first().id)
        }
    }

    /** Whether to insert/delete a membership row, given the groups the contact already belongs to. */
    fun decideMembership(
        existingGroupIds: List<Long>,
        groupId: Long,
        remove: Boolean,
    ): MembershipDecision {
        val member = existingGroupIds.contains(groupId)
        return when {
            remove && member -> MembershipDecision.Delete
            remove -> MembershipDecision.NotMember
            member -> MembershipDecision.AlreadyMember
            else -> MembershipDecision.Insert
        }
    }

    /**
     * Chooses the raw contact to attach a membership row to. A group belongs to one account, and a
     * membership row is meaningful on a raw contact of that same account, so a matching raw contact
     * is preferred; a local (null-account) group next prefers a local raw contact; otherwise the
     * first raw contact is used as a best effort. `null` only when there are no raw contacts.
     */
    fun pickRawContactForGroup(
        rawContacts: List<RawContactRef>,
        accountType: String?,
        accountName: String?,
    ): Long? {
        if (rawContacts.isEmpty()) return null
        if (accountType != null || accountName != null) {
            rawContacts.firstOrNull {
                it.accountType == accountType && it.accountName == accountName
            }?.let { return it.rawContactId }
        }
        rawContacts.firstOrNull { it.accountType == null && it.accountName == null }
            ?.let { return it.rawContactId }
        return rawContacts.first().rawContactId
    }
}
