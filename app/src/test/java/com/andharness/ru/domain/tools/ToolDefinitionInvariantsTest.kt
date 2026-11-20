package com.andharness.ru.domain.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks invariants that are painful to break and were fixed empirically:
 * 1. JSON-schema parameter types must be limited to string/boolean/object —
 *    the local llama.cpp server rejects number/integer/array (HTTP 400).
 * 2. The shared-storage file/download tools must request MANAGE_EXTERNAL_STORAGE
 *    (the "All files access" special permission) via needsPermission.
 */
private const val MANAGE = "android.permission.MANAGE_EXTERNAL_STORAGE"

class ToolDefinitionInvariantsTest {

    private val tools = DefaultTools.all()

    private fun collectTypes(element: JsonElement): Set<String> = when (element) {
        is JsonObject -> element.entries.flatMap { (key, value) ->
            if (key == "type" && value is JsonPrimitive) {
                listOf(value.content) + collectTypes(value)
            } else {
                collectTypes(value)
            }
        }.toSet()
        is JsonArray -> element.flatMap { collectTypes(it) }.toSet()
        JsonNull -> emptySet()
        is JsonPrimitive -> emptySet()
    }

    @Test
    fun onlyAllowedSchemaTypes() {
        val allowed = setOf("string", "boolean", "object")
        for (tool in tools) {
            val used = collectTypes(tool.parameters)
            val forbidden = used - allowed
            assertTrue(
                "Tool '${tool.name}' uses forbidden schema type(s) $forbidden " +
                    "(llama.cpp rejects number/integer/array)",
                forbidden.isEmpty()
            )
        }
    }

    @Test
    fun sharedStorageToolsRequestAllFilesPermission() {
        val expected = setOf(
            "files_list", "files_read", "files_write", "files_create_dir",
            "files_delete", "files_move", "files_rename", "files_copy", "files_stat", "files_open",
            "files_dir_size",
            "files_append", "files_search", "files_grep", "files_hash", "files_duplicates", "files_zip", "files_unzip",
            "files_zip_list", "files_gzip", "files_gunzip", "share_file", "share_files", "print_file",
            "files_split", "files_join", "files_mime", "files_compare", "files_hide", "files_chmod",
            "files_tar_create", "files_tar_extract",
            "files_7z_create", "files_7z_extract", "files_rar_extract",
            "files_trash", "files_trash_list", "files_trash_restore", "files_trash_empty",
            "download_file", "photos_delete", "ocr_image",
            "exif_read", "exif_write", "image_thumbnail", "media_scan", "media_delete",
            "media_trash", "media_request_write",
            "media_downloads_list", "media_metadata", "media_frame_extract",
            "media_favorite_set", "audio_play",
            "saf_create_document", "saf_open_document",
            "doc_info", "doc_read", "doc_create", "doc_edit", "pdf_merge", "pdf_split",
            "pdf_ocr", "pdf_rotate", "pdf_watermark", "pdf_extract_images", "pdf_render_pages",
            "pdf_compress", "pdf_fill_form", "pdf_pages",
            "xlsx_set_cell", "xlsx_set_cells", "rag_index", "web_screenshot", "web_download",
            "pdf_create", "pdf_from_markdown", "pdf_from_template", "pdf_from_template_file",
            "docx_replace", "docx_append",
            "pptx_create", "pptx_edit", "odp_create", "xlsx_style",
            "office_convert", "office_merge", "office_split", "doc_tables",
            "contact_photo_set", "camera_burst", "camera_video_record",
            "screen_capture_region",
            "audio_trim", "audio_concat", "audio_convert", "audio_volume", "audio_speed",
            "audio_extract_from_video", "audio_tag_write",
            "video_trim", "video_concat", "video_resize", "video_bitrate", "video_compress",
            "video_rotate", "video_extract_audio", "video_subtitles", "video_convert"
        )
        val actual = tools.filter { it.needsPermission == MANAGE }.map { it.name }.toSet()
        assertEquals("the exact set of tools requiring MANAGE_EXTERNAL_STORAGE", expected, actual)
    }

    @Test
    fun folderWatchToolsAreRegisteredAndPermissionFree() {
        val names = tools.map { it.name }.toSet()
        val expected = setOf(
            "folder_watch_start", "folder_watch_stop", "folder_watch_list",
            "folder_watch_events", "folder_watch_clear"
        )
        assertEquals("folder_watch_* tools must be registered", expected, names.intersect(expected))

        val watchTools = tools.filter { it.name in expected }
        for (tool in watchTools) {
            assertEquals(
                "folder_watch tools must not request a permission: ${tool.name}",
                null,
                tool.needsPermission
            )
            assertTrue(
                "folder_watch tools must not be flagged dangerous: ${tool.name}",
                !tool.dangerous
            )
        }
    }

    @Test
    fun noToolDescriptionPromisesAppLevelConfirmation() {
        // BUG-033: the app never asks for confirmation before dangerous actions, so a tool
        // description must not claim "требует подтверждения". An honest negation
        // ("Не требует подтверждения") is allowed; genuine system dialogs are described
        // otherwise ("с подтверждением", "диалог подтверждения", "ждёт подтверждения").
        val staleClaim = Regex("(?<!Не )требует подтверждения")
        for (tool in tools) {
            assertTrue(
                "Tool '${tool.name}' promises an app-level confirmation: ${tool.description}",
                staleClaim.find(tool.description) == null
            )
        }
    }

    @Test
    fun destructiveAndExternalToolsAreFlaggedDangerous() {
        val dangerous = tools.filter { it.dangerous }.map { it.name }.toSet()
        val mustBeDangerous = setOf(
            "files_delete", "contact_create", "contact_update", "contact_delete",
            "contact_star_set",
            "contact_batch_upsert", "contact_merge", "contact_split",
            "contact_photo_set", "contact_photo_clear",
            "contact_import_vcf", "contact_group_delete",
            "sms_send", "sms_send_on_sim", "sms_delete", "mms_send", "camera_capture", "audio_record",
            "camera_burst", "camera_video_record",
            "call_start", "calendar_create", "calendar_update", "calendar_delete",
            "calendar_attendees_set", "calendar_reminders_set", "calendar_recurring_create",
            "app_launch", "app_uninstall", "photos_delete", "call_log_delete", "doc_create", "doc_edit",
            "pdf_merge", "pdf_split", "xlsx_set_cell", "media_frame_extract",
            "settings_system_set", "screen_record_start", "screen_capture_region",
            "web_download", "audio_capture_app",
            "chat_delete", "chat_clear_messages"
        )
        assertTrue(
            "dangerous flag missing on: ${mustBeDangerous - dangerous}",
            dangerous.containsAll(mustBeDangerous)
        )
    }
}
