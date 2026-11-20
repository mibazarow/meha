package com.andharness.ru.service

import android.app.admin.DeviceAdminReceiver

/**
 * Device admin receiver enabling `device_lock` (screen lock) via DevicePolicyManager.
 * Reference: https://developer.android.com/guide/topics/admin/device-admin
 */
class AndharnessDeviceAdminReceiver : DeviceAdminReceiver()
