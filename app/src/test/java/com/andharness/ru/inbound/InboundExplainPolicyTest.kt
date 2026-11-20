package com.andharness.ru.inbound

import com.andharness.ru.domain.chat.AttachmentKind
import com.andharness.ru.domain.chat.ChatAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the pure «Что это?» seed-text policy. No Android APIs are involved, so the mapping
 * from a shared payload (text / image / document) to the composer message is tested exactly, which
 * is what makes the share and process-text entry points verifiable without a device.
 */
class InboundExplainPolicyTest {

    private fun attachment(
        name: String,
        mime: String,
        kind: AttachmentKind
    ): ChatAttachment = ChatAttachment(path = "/data/files/$name", name = name, mime = mime, kind = kind)

    private fun image() = attachment("photo.jpg", "image/jpeg", AttachmentKind.IMAGE)
    private fun document() = attachment("report.pdf", "application/pdf", AttachmentKind.DOCUMENT)

    @Test
    fun `shared text is quoted under the explain question`() {
        assertEquals(
            "Объясни, что это значит:\n\n> Купите подписку сегодня",
            InboundExplainPolicy.buildSeedText("Купите подписку сегодня", emptyList())
        )
    }

    @Test
    fun `multiline text keeps the quote block intact, including blank lines`() {
        assertEquals(
            "> первая строка\n>\n> вторая строка",
            InboundExplainPolicy.quote("  первая строка\n\nвторая строка \n")
        )
    }

    @Test
    fun `image only seeds the image question`() {
        assertEquals(
            InboundExplainPolicy.IMAGE_QUESTION,
            InboundExplainPolicy.buildSeedText("", listOf(image()))
        )
    }

    @Test
    fun `image and text seeds the combined question with the quoted text`() {
        assertEquals(
            InboundExplainPolicy.IMAGE_WITH_TEXT_QUESTION + "\n\n> что это за ошибка?",
            InboundExplainPolicy.buildSeedText("что это за ошибка?", listOf(image()))
        )
    }

    @Test
    fun `non-image attachment gets the attachment question`() {
        assertEquals(
            InboundExplainPolicy.ATTACHMENT_QUESTION,
            InboundExplainPolicy.buildSeedText("   ", listOf(document()))
        )
    }

    @Test
    fun `nothing usable yields an empty seed so the handler can skip auto-run`() {
        assertEquals("", InboundExplainPolicy.buildSeedText("", emptyList()))
        assertEquals("", InboundExplainPolicy.buildSeedText("   \n  ", emptyList()))
    }

    @Test
    fun `quote returns null for blank input`() {
        assertNull(InboundExplainPolicy.quote(""))
        assertNull(InboundExplainPolicy.quote("   \n\t"))
    }

    @Test
    fun `title names an image explain as an image`() {
        assertEquals("Что это? — изображение", InboundExplainPolicy.title(listOf(image())))
        assertEquals("Что это?", InboundExplainPolicy.title(emptyList()))
        assertEquals("Что это?", InboundExplainPolicy.title(listOf(document())))
    }
}
