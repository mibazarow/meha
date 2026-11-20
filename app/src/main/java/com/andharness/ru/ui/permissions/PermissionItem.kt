package com.andharness.ru.ui.permissions


/**
 * One row of the runtime-permissions block. A capability may map to several Android permissions
 * (`READ_/WRITE_…`): they are merged into one row whose «Запросить» requests every entry of
 * [androidPermissions].
 */
data class PermissionItem(
    val androidPermissions: List<String>,
    val displayName: String,
    val description: String,
    val granted: Boolean
)
