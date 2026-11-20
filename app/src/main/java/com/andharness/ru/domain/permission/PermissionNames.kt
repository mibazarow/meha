package com.andharness.ru.domain.permission

/**
 * Android permission names as framework-free constants so the domain layer stays free of Android
 * framework imports (no `android.Manifest`). Every value is byte-identical to the corresponding
 * `android.Manifest.permission` constant (verified against android-35 `android.jar`); do not
 * "correct" a spelling here — the value must keep matching the platform constant exactly.
 * https://developer.android.com/reference/android/Manifest.permission
 */
object PermissionNames {
    const val READ_CONTACTS = "android.permission.READ_CONTACTS"
    const val WRITE_CONTACTS = "android.permission.WRITE_CONTACTS"
    const val CAMERA = "android.permission.CAMERA"
    const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    const val READ_SMS = "android.permission.READ_SMS"
    const val SEND_SMS = "android.permission.SEND_SMS"
    const val RECEIVE_SMS = "android.permission.RECEIVE_SMS"
    const val READ_CALL_LOG = "android.permission.READ_CALL_LOG"
    const val CALL_PHONE = "android.permission.CALL_PHONE"
    const val READ_PHONE_NUMBERS = "android.permission.READ_PHONE_NUMBERS"
    const val ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val READ_CALENDAR = "android.permission.READ_CALENDAR"
    const val WRITE_CALENDAR = "android.permission.WRITE_CALENDAR"
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val ACTIVITY_RECOGNITION = "android.permission.ACTIVITY_RECOGNITION"
    const val BODY_SENSORS = "android.permission.BODY_SENSORS"
    const val READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"
    const val READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    const val MANAGE_EXTERNAL_STORAGE = "android.permission.MANAGE_EXTERNAL_STORAGE"
    const val WRITE_SETTINGS = "android.permission.WRITE_SETTINGS"
}
