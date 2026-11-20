package com.andharness.ru.domain.permission


/**
 * One UI row of «Все разрешения»: the Android permissions of a single user-facing capability
 * merged together (e.g. `READ_CONTACTS` + `WRITE_CONTACTS` → one «Контакты» row).
 */
data class PermissionCapability(
    val displayName: String,
    val description: String,
    val androidPermissions: List<String>,
)
