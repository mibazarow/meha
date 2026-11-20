package com.andharness.ru.domain.permission

data class DevicePermission(
    val name: String,
    val androidPermission: String?,
    val displayName: String,
    val skill: String
)
