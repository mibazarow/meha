package com.andharness.ru.data.messages

import com.andharness.ru.data.db.entity.StoredMessageEntity
import com.andharness.ru.domain.messages.StoredMessage

/**
 * Entity ⇄ domain mapping for the stored-messages tables. Kept in the data layer (not in the
 * Android-free domain) so the domain model stays free of Room dependencies.
 *
 * `sourceType` / `direction` are stored as their string names and copied verbatim; a stored value the
 * domain does not know (e.g. written by a newer version) is preserved as-is rather than dropped.
 */
fun StoredMessageEntity.toDomain(): StoredMessage = StoredMessage(
    id = id,
    sourceType = sourceType,
    packageName = packageName,
    conversationKey = conversationKey,
    personId = personId,
    direction = direction,
    sender = sender,
    isGroup = isGroup,
    at = at,
    text = text,
    externalRef = externalRef,
    createdAt = createdAt
)

fun StoredMessage.toEntity(): StoredMessageEntity = StoredMessageEntity(
    id = id,
    sourceType = sourceType,
    packageName = packageName,
    conversationKey = conversationKey,
    personId = personId,
    direction = direction,
    sender = sender,
    isGroup = isGroup,
    at = at,
    text = text,
    externalRef = externalRef,
    createdAt = createdAt
)
