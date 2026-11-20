package com.andharness.ru.domain.permission

interface PermissionManager {
    fun isGranted(permission: String?): Boolean
    fun all(): List<DevicePermission>
}
