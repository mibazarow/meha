package com.andharness.ru.data.tools.selftest

/**
 * Curated cases for the deterministic `selftest_run` tool: non-interactive tools with safe sample
 * arguments. Interactive/destructive tools are excluded and listed in [SKIPPED].
 */
object SelfTestCases {

    data class Case(val name: String, val args: String)

    val CASES: List<Case> = listOf(
        Case("device_info", "{}"),
        Case("battery_detail", "{}"),
        Case("network_info", "{}"),
        Case("wifi_info", "{}"),
        Case("bluetooth_devices", "{}"),
        Case("brightness_get", "{}"),
        Case("volume_get", "{\"type\":\"music\"}"),
        Case("sensor_list", "{}"),
        Case("sensor_read", "{\"type\":\"accelerometer\"}"),
        Case("settings_get", "{}"),
        Case("files_list", "{\"path\":\"Documents\"}"),
        Case("files_stat", "{\"path\":\"ANDharness_selftest/probe.txt\"}"),
        Case("files_append", "{\"path\":\"ANDharness_selftest/probe.txt\",\"content\":\"more\"}"),
        Case("files_read", "{\"path\":\"ANDharness_selftest/probe.txt\"}"),
        Case("files_search", "{\"query\":\"probe\"}"),
        Case("files_hash", "{\"path\":\"ANDharness_selftest/probe.txt\"}"),
        Case("files_copy", "{\"source\":\"ANDharness_selftest/probe.txt\",\"destination\":\"ANDharness_selftest/probe2.txt\"}"),
        Case("files_move", "{\"source\":\"ANDharness_selftest/probe2.txt\",\"destination\":\"ANDharness_selftest/probe3.txt\"}"),
        Case("files_zip", "{\"sources\":\"ANDharness_selftest/probe.txt\",\"destination\":\"ANDharness_selftest/probe.zip\"}"),
        Case("files_unzip", "{\"source\":\"ANDharness_selftest/probe.zip\",\"destination\":\"ANDharness_selftest/unz\"}"),
        Case("files_delete", "{\"path\":\"ANDharness_selftest/probe3.txt\"}"),
        Case("photos_list", "{\"limit\":\"3\"}"),
        Case("media_audio_list", "{\"limit\":\"3\"}"),
        Case("media_video_list", "{\"limit\":\"3\"}"),
        Case("contact_search", "{\"query\":\"a\"}"),
        Case("call_log", "{\"limit\":\"3\"}"),
        Case("sms_list", "{\"limit\":\"2\"}"),
        Case("clipboard_get", "{}"),
        Case("clipboard_set", "{\"text\":\"andharness selftest\"}"),
        Case("location_get", "{}"),
        Case("calendar_events", "{\"from\":\"1000000000000\",\"to\":\"9999999999999\"}"),
        Case("app_list", "{}"),
        Case("http_request", "{\"method\":\"GET\",\"url\":\"https://example.com\"}"),
        Case("dns_lookup", "{\"host\":\"example.com\"}"),
        Case("ping", "{\"host\":\"1.1.1.1\",\"count\":\"1\"}"),
        Case("wifi_scan", "{}"),
        Case("app_usage", "{\"days\":\"1\"}"),
        Case("notification_list", "{}"),
        Case("note_create", "{\"text\":\"selftest note\"}"),
        Case("note_list", "{}"),
        Case("memory_save", "{\"text\":\"selftest memory\"}"),
        Case("memory_search", "{\"query\":\"selftest\"}"),
        Case("memory_list", "{\"limit\":\"5\"}"),
        Case("todo_add", "{\"text\":\"selftest todo\"}"),
        Case("todo_list", "{}"),
        Case("schedule_list", "{}"),
    )

    val SEED: List<Case> = listOf(
        Case("files_create_dir", "{\"path\":\"ANDharness_selftest\"}"),
        Case("files_write", "{\"path\":\"ANDharness_selftest/probe.txt\",\"content\":\"selftest\"}"),
    )

    val SKIPPED: List<String> = listOf(
        "camera_capture", "audio_record", "speech_to_text", "text_to_speech",
        "call_start", "dial", "sms_send", "ussd_request",
        "app_launch", "app_uninstall", "app_install_apk", "app_deep_link",
        "open_url", "app_details", "open_settings", "share_text", "share_file", "files_open",
        "ui_tree", "ui_tap", "ui_long_press", "ui_swipe", "ui_scroll", "ui_type", "key_event", "screen_capture",
        "ui_macro_start", "ui_macro_stop", "ui_macro_list", "ui_macro_run", "ui_macro_delete",
        "skill_record_start", "skill_record_stop", "skill_export", "skill_import",
        "notification_open", "notification_dismiss", "notification_reply",
        "web_open", "web_click", "web_type", "web_scroll", "web_back", "web_extract", "web_fetch",
        "download_file", "photos_delete", "contact_delete", "call_log_delete", "calendar_create",
        "calendar_update", "calendar_delete", "contact_create", "contact_update",
        "alarm_set", "timer_set", "vibrate", "ringer_set", "rotation_set", "screen_timeout_set",
        "font_scale_set", "flashlight_set", "wifi_set", "bluetooth_set", "device_lock", "device_admin_enable",
        "schedule_create", "schedule_delete", "schedule_toggle", "reminder_set", "ocr_image", "selftest_run",
        "autofill_profile_set", "autofill_profile_get", "memory_delete", "wallpaper_set",
        "events_recent"
    )
}
