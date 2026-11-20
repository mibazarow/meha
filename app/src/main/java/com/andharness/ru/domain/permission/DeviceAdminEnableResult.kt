package com.andharness.ru.domain.permission


/** Honest result of a `device_admin_enable` request: the reported success flag and its message. */
data class DeviceAdminEnableResult(val success: Boolean, val message: String)
