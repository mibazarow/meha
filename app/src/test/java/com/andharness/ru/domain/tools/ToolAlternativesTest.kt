package com.andharness.ru.domain.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the alternatives registry: exact tool lookup, capability-family lookup, honest
 * reason text, bounded output, and the hard safety rule that destructive/irreversible tools are
 * never offered as a recovery path.
 */
class ToolAlternativesTest {

    private val sampledTools = listOf(
        "wallpaper_set", "web_search", "web_fetch", "web_readable", "sms_send", "compose_message",
        "files_read", "wifi_set", "bluetooth_set", "image_search",
        "files_zip", "files_zip_list", "files_unzip", "files_7z_extract", "files_7z_create",
        "files_rar_extract", "files_tar_extract", "files_gzip", "files_gunzip",
        "doc_read", "doc_create", "docx_append", "pdf_ocr", "pdf_merge",
        "media_audio_list", "media_metadata", "photos_list", "photos_delete",
        "image_convert", "image_resize", "image_thumbnail",
    )

    @Test
    fun wallpaperSetByUrlSuggestsDownloadThenLocalPath() {
        val alts = ToolAlternatives.suggestions("wallpaper_set")
        assertEquals("download_file", alts.first().tool)
        // The reason must spell out the two-step recovery: download, then retry with a local path.
        assertTrue(alts.first().reason.contains("локально"))
        assertTrue(alts.first().reason.contains("wallpaper_set"))
        assertFalse(alts.any { it.tool == "wallpaper_set" })
    }

    @Test
    fun webSearchSuggestsImageSearchAndOpenAndHttp() {
        val tools = ToolAlternatives.suggestions("web_search").map { it.tool }
        assertTrue(tools.contains("image_search"))
        assertTrue(tools.contains("web_open"))
        assertTrue(tools.contains("http_request"))
        // web_open reason must mention reading the page via web_readable.
        val open = ToolAlternatives.suggestions("web_search").first { it.tool == "web_open" }
        assertTrue(open.reason.contains("web_readable"))
    }

    @Test
    fun smsSendSuggestsComposeMessage() {
        assertTrue(ToolAlternatives.suggestions("sms_send").any { it.tool == "compose_message" })
    }

    @Test
    fun composeMessageNeverSuggestsSmsSend() {
        // sms_send is irreversible; the symmetric pair must be dropped by the safety filter.
        assertFalse(ToolAlternatives.suggestions("compose_message").any { it.tool == "sms_send" })
        assertTrue(ToolAlternatives.IRREVERSIBLE.contains("sms_send"))
    }

    @Test
    fun filesReadSuggestsDownloadOpenList() {
        val tools = ToolAlternatives.suggestions("files_read").map { it.tool }
        assertTrue(tools.contains("download_file"))
        assertTrue(tools.contains("files_open"))
        assertTrue(tools.contains("files_list"))
    }

    @Test
    fun platformBlockedTogglesSuggestSettingsPanels() {
        assertEquals("open_settings", ToolAlternatives.suggestions("wifi_set").first().tool)
        assertEquals("open_settings", ToolAlternatives.suggestions("bluetooth_set").first().tool)
        assertTrue(ToolAlternatives.suggestions("wifi_set").first().reason.contains("Wi-Fi"))
        assertTrue(ToolAlternatives.suggestions("bluetooth_set").first().reason.contains("Bluetooth"))
    }

    @Test
    fun capabilityFamiliesAreLookedUpByPrefix() {
        assertTrue(ToolAlternatives.suggestions("files_7z_create").isNotEmpty())
        assertTrue(ToolAlternatives.suggestions("files_rar_extract").isNotEmpty())
        assertTrue(ToolAlternatives.suggestions("doc_read").any { it.tool == "files_open" })
        assertTrue(ToolAlternatives.suggestions("pdf_merge").isNotEmpty())
        assertTrue(ToolAlternatives.suggestions("media_audio_list").isNotEmpty())
        assertTrue(ToolAlternatives.suggestions("photos_list").any { it.tool == "media_metadata" })
        assertTrue(ToolAlternatives.suggestions("image_resize").any { it.tool == "image_convert" })
    }

    @Test
    fun aToolNeverSuggestsItself() {
        sampledTools.forEach { name ->
            assertFalse(name, ToolAlternatives.suggestions(name).any { it.tool == name })
        }
    }

    @Test
    fun unknownOrBlankToolHasNoSuggestion() {
        assertTrue(ToolAlternatives.suggestions("device_info").isEmpty())
        assertEquals("", ToolAlternatives.format("device_info"))
        assertTrue(ToolAlternatives.suggestions("").isEmpty())
        assertEquals("", ToolAlternatives.format("   "))
    }

    @Test
    fun irreversibleToolsWithNoSafePathGetNoAlternatives() {
        // These destructive tools have no curated safe alternative, so the registry must stay silent
        // rather than invent one. (`sms_send` is deliberately absent: it has the safe
        // compose_message path, which is allowed — see the safety note in ToolAlternatives.)
        listOf(
            "files_delete", "files_trash_empty", "app_uninstall",
            "call_start", "mms_send", "sms_send_on_sim",
        ).forEach { name ->
            assertTrue(name, ToolAlternatives.suggestions(name).isEmpty())
        }
    }

    @Test
    fun destructiveToolsAreNeverSuggestedAsRecovery() {
        val names = sampledTools + ToolAlternatives.IRREVERSIBLE
        names.forEach { name ->
            ToolAlternatives.suggestions(name).forEach { alt ->
                assertFalse(
                    "destructive tool ${alt.tool} suggested for $name",
                    alt.tool in ToolAlternatives.IRREVERSIBLE
                )
            }
        }
    }

    @Test
    fun irreversibleSetCoversTheExpectedDangerousTools() {
        listOf(
            "files_delete", "files_trash", "files_trash_empty", "app_uninstall",
            "sms_send", "sms_send_on_sim", "mms_send", "call_start",
            "media_delete", "photos_delete", "contact_delete", "call_log_delete",
        ).forEach { assertTrue("missing $it", it in ToolAlternatives.IRREVERSIBLE) }
    }

    @Test
    fun outputIsBounded() {
        sampledTools.forEach { name ->
            val alts = ToolAlternatives.suggestions(name)
            assertTrue("too many suggestions for $name", alts.size <= ToolAlternatives.MAX_SUGGESTIONS)
            assertTrue("hint too long for $name", ToolAlternatives.format(name).length <= ToolAlternatives.MAX_HINT_CHARS)
        }
    }

    @Test
    fun hintTextIsHonestAndActionable() {
        val text = ToolAlternatives.format("files_read")
        assertTrue(text.startsWith("Попробуй также:"))
        ToolAlternatives.suggestions("files_read").forEach { alt ->
            assertTrue(text.contains(alt.tool))
            assertTrue(text.contains(alt.reason))
        }
        assertEquals("", ToolAlternatives.format("device_info"))
    }
}
