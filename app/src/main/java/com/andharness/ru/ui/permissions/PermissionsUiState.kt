package com.andharness.ru.ui.permissions


data class PermissionsUiState(
    val items: List<PermissionItem> = emptyList(),
    val specialAccess: List<SpecialAccessItem> = emptyList()
)
