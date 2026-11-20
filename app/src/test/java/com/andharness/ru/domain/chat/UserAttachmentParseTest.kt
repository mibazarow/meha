package com.andharness.ru.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round-trip and edge-case tests for [AttachmentPolicy.parseUserText] (the UI attachment parser). */
class UserAttachmentParseTest {

    private fun attachment(
        path: String,
        name: String,
        mime: String,
        kind: AttachmentKind
    ) = ChatAttachment(path = path, name = name, mime = mime, kind = kind)

    @Test
    fun `plain text without a block is returned unchanged`() {
        val parsed = AttachmentPolicy.parseUserText("просто сообщение")
        assertEquals("просто сообщение", parsed.text)
        assertTrue(parsed.attachments.isEmpty())
    }

    @Test
    fun `round trips the user text and the attachment list`() {
        val content = AttachmentPolicy.buildUserText(
            "вот фото",
            listOf(
                attachment("/data/att/1_photo.jpg", "photo.jpg", "image/jpeg", AttachmentKind.IMAGE),
                attachment("/data/att/2_report.pdf", "report.pdf", "application/pdf", AttachmentKind.DOCUMENT),
            ),
        )

        val parsed = AttachmentPolicy.parseUserText(content)

        assertEquals("вот фото", parsed.text)
        assertEquals(2, parsed.attachments.size)
        with(parsed.attachments[0]) {
            assertEquals("photo.jpg", name)
            assertEquals(AttachmentKind.IMAGE, kind)
            assertEquals("image/jpeg", mime)
            assertEquals("/data/att/1_photo.jpg", path)
        }
        assertEquals(AttachmentKind.DOCUMENT, parsed.attachments[1].kind)
        assertEquals("application/pdf", parsed.attachments[1].mime)
    }

    @Test
    fun `attachment-only message yields blank user text`() {
        val content = AttachmentPolicy.buildUserText(
            "",
            listOf(attachment("/p/a.png", "a.png", "image/png", AttachmentKind.IMAGE)),
        )

        val parsed = AttachmentPolicy.parseUserText(content)

        assertEquals("", parsed.text)
        assertEquals(1, parsed.attachments.size)
        assertEquals(AttachmentKind.IMAGE, parsed.attachments.single().kind)
    }

    @Test
    fun `a file name containing parentheses is preserved`() {
        val content = AttachmentPolicy.buildUserText(
            "hi",
            listOf(attachment("/p/x.jpg", "vacation (2024).jpg", "image/jpeg", AttachmentKind.IMAGE)),
        )

        val parsed = AttachmentPolicy.parseUserText(content)

        assertEquals(listOf("vacation (2024).jpg"), parsed.attachments.map { it.name })
    }

    @Test
    fun `blank mime falls back to the kind label`() {
        val content = AttachmentPolicy.buildUserText(
            "док",
            listOf(attachment("/p/notes.txt", "notes.txt", "", AttachmentKind.DOCUMENT)),
        )

        val parsed = AttachmentPolicy.parseUserText(content)

        assertEquals(AttachmentKind.DOCUMENT, parsed.attachments.single().kind)
    }

    @Test
    fun `other kind files parse as OTHER`() {
        val content = AttachmentPolicy.buildUserText(
            "архив",
            listOf(attachment("/p/archive.bin", "archive.bin", "application/octet-stream", AttachmentKind.OTHER)),
        )

        val parsed = AttachmentPolicy.parseUserText(content)

        assertEquals(AttachmentKind.OTHER, parsed.attachments.single().kind)
    }
}
