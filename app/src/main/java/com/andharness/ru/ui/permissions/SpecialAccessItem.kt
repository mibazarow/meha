package com.andharness.ru.ui.permissions

import com.andharness.ru.domain.permission.PermissionManager
import com.andharness.ru.domain.permission.SpecialAccess

/**
 * One «Особые доступы» row: a [SpecialAccess] token, its RU title and the granted state read from
 * the single source of truth ([PermissionManager.isGranted]).
 */
data class SpecialAccessItem(
    val token: String,
    val displayName: String,
    val granted: Boolean
)
