package com.andharness.ru.domain.saf

/**
 * A user-granted Storage Access Framework folder the agent may access in restricted mode.
 *
 * @param uri persisted tree URI returned by `ACTION_OPEN_DOCUMENT_TREE`.
 * @param treeDocumentId tree document id extracted from [uri], used to build child document URIs.
 * @param displayName human-readable folder name shown in the UI.
 * @param lastCheckedAt last time the grant was re-validated (0 = never since it was added).
 * @param lastOk whether the last re-validation still had an accessible grant.
 */
data class SafGrant(
    val id: Long,
    val uri: String,
    val treeDocumentId: String,
    val displayName: String,
    val createdAt: Long,
    val lastCheckedAt: Long,
    val lastOk: Boolean
)
