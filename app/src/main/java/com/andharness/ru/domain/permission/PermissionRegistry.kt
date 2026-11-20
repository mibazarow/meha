package com.andharness.ru.domain.permission

/**
 * Permission names come from [PermissionNames], framework-free constants byte-identical to the
 * `android.Manifest.permission` constants (each value is verified against android-35 `android.jar`)
 * so the domain layer stays free of Android framework imports.
 */
object PermissionRegistry {

    fun all(): List<DevicePermission> = listOf(
        DevicePermission("read_contacts", PermissionNames.READ_CONTACTS, "Контакты", "Чтение и запись контактов"),
        DevicePermission("write_contacts", PermissionNames.WRITE_CONTACTS, "Контакты", "Чтение и запись контактов"),
        DevicePermission("camera", PermissionNames.CAMERA, "Камера", "Съёмка фото и видео"),
        DevicePermission("record_audio", PermissionNames.RECORD_AUDIO, "Микрофон", "Запись звука"),
        DevicePermission("read_sms", PermissionNames.READ_SMS, "SMS", "Чтение, приём и отправка SMS"),
        DevicePermission("send_sms", PermissionNames.SEND_SMS, "SMS", "Чтение, приём и отправка SMS"),
        // RECEIVE_SMS is a separate runtime permission and is what lets the system deliver the
        // SMS_RECEIVED broadcast to SmsReceiver. Same «SMS» capability, so the row requests all
        // three SMS permissions at once instead of adding a duplicate row.
        // https://developer.android.com/training/permissions/requesting
        DevicePermission("receive_sms", PermissionNames.RECEIVE_SMS, "SMS", "Чтение, приём и отправка SMS"),
        DevicePermission("read_call_log", PermissionNames.READ_CALL_LOG, "Звонки", "Журнал вызовов и звонки"),
        DevicePermission("call_phone", PermissionNames.CALL_PHONE, "Звонки", "Журнал вызовов и звонки"),
        // READ_PHONE_NUMBERS lets sim_numbers read the SIM's own number (SubscriptionManager
        // .getPhoneNumber / TelephonyManager.getLine1Number); it is a separate runtime permission
        // from READ_PHONE_STATE even though both live in the PHONE group.
        // https://developer.android.com/reference/android/Manifest.permission#READ_PHONE_NUMBERS
        DevicePermission("read_phone_numbers", PermissionNames.READ_PHONE_NUMBERS, "Звонки", "Журнал вызовов и звонки"),
        DevicePermission("location_fine", PermissionNames.ACCESS_FINE_LOCATION, "Геолокация", "Точное местоположение"),
        DevicePermission("read_calendar", PermissionNames.READ_CALENDAR, "Календарь", "Чтение и запись событий"),
        DevicePermission("write_calendar", PermissionNames.WRITE_CALENDAR, "Календарь", "Чтение и запись событий"),
        DevicePermission("post_notifications", PermissionNames.POST_NOTIFICATIONS, "Уведомления", "Показ уведомлений"),
        DevicePermission("bluetooth_connect", PermissionNames.BLUETOOTH_CONNECT, "Bluetooth", "Подключение к устройствам"),
        DevicePermission("activity_recognition", PermissionNames.ACTIVITY_RECOGNITION, "Датчики активности", "Распознавание движения"),
        DevicePermission("body_sensors", PermissionNames.BODY_SENSORS, "Датчик пульса", "Пульс и другие датчики тела"),
        DevicePermission("read_media_audio", PermissionNames.READ_MEDIA_AUDIO, "Музыка и аудио", "Доступ к аудиофайлам"),
        DevicePermission("read_media_video", PermissionNames.READ_MEDIA_VIDEO, "Видео", "Доступ к видеофайлам"),
    )
}

/**
 * Merges the flat [DevicePermission] registry into one [PermissionCapability] per [displayName],
 * preserving registry order. Duplicate rows (same capability split into READ_/WRITE_ permissions)
 * collapse into a single entry that requests every underlying permission at once; blank
 * `androidPermission` values are dropped.
 */
fun List<DevicePermission>.mergeIntoCapabilities(): List<PermissionCapability> =
    groupBy { it.displayName }.map { (displayName, group) ->
        PermissionCapability(
            displayName = displayName,
            description = group.first().skill,
            androidPermissions = group.mapNotNull { it.androidPermission }.distinct(),
        )
    }
