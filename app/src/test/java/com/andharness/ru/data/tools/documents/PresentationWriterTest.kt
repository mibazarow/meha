package com.andharness.ru.data.tools.documents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * JVM tests for [PptxWriter], the ODP generation in [OdfWriter] and [SlideSpecParser]: each produced
 * container is re-opened (ZIP structure, XML well-formedness, text round-trip through the very
 * readers `doc_read` uses) so a regression in the byte layout fails here, not on the device.
 *
 * References:
 * - PresentationML — https://learn.microsoft.com/en-us/office/open-xml/presentation/presentationml
 * - `java.util.zip.ZipFile` — https://developer.android.com/reference/java/util/zip/ZipFile
 */
class PresentationWriterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun probe(bytes: ByteArray, name: String = "probe.zip"): File =
        File.createTempFile("probe", name, temp.root).apply { writeBytes(bytes) }

    private fun file(name: String, bytes: ByteArray): File =
        temp.newFile(name).apply { writeBytes(bytes) }

    private fun entryNames(bytes: ByteArray): List<String> =
        ZipFile(probe(bytes)).use { zip -> zip.entries().asSequence().map { it.name }.toList() }

    private fun entryText(bytes: ByteArray, entry: String): String =
        ZipFile(probe(bytes)).use { zip ->
            val zipEntry = zip.getEntry(entry)
            assertNotNull("ZIP must contain $entry", zipEntry)
            zip.getInputStream(zipEntry).use { it.readBytes().toString(Charsets.UTF_8) }
        }

    private fun assertWellFormed(xml: String) {
        val parser = newPullParser()
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) event = parser.next()
    }

    private fun newPullParser(): XmlPullParser {
        val fromFactory = try {
            XmlPullParserFactory.newInstance().newPullParser()
        } catch (t: Throwable) {
            null
        }
        if (fromFactory != null) return fromFactory
        @Suppress("UNCHECKED_CAST")
        return Class.forName("org.kxml2.io.KXmlParser").getDeclaredConstructor().newInstance() as XmlPullParser
    }

    @Test
    fun pptxContainsRequiredPartsAndRoundTripsSlideText() {
        val slides = listOf(
            PptxWriter.Slide("Заголовок", listOf("Пункт 1", "Пункт 2")),
            PptxWriter.Slide("Второй", listOf("a & <b>")),
        )
        val bytes = PptxWriter.pptx(slides)

        val names = entryNames(bytes)
        assertTrue(
            "PPTX parts missing: $names",
            names.containsAll(
                listOf(
                    "[Content_Types].xml",
                    "_rels/.rels",
                    "ppt/presentation.xml",
                    "ppt/_rels/presentation.xml.rels",
                    "ppt/slideMasters/slideMaster1.xml",
                    "ppt/slideLayouts/slideLayout1.xml",
                    "ppt/theme/theme1.xml",
                    "ppt/slides/slide1.xml",
                    "ppt/slides/slide2.xml",
                )
            )
        )
        for (entry in names) assertWellFormed(entryText(bytes, entry))

        val slide1 = entryText(bytes, "ppt/slides/slide1.xml")
        val text1 = DocumentSupport.extractRunText(slide1, "t", "p")
        assertTrue("title must round-trip", text1.contains("Заголовок"))
        assertTrue("bullets must round-trip", text1.contains("• Пункт 1") && text1.contains("• Пункт 2"))
        assertTrue("& must be escaped", entryText(bytes, "ppt/slides/slide2.xml").contains("&amp;"))

        val presentation = entryText(bytes, "ppt/presentation.xml")
        assertTrue(presentation.contains("<p:sldId id=\"256\" r:id=\"rId2\"/>"))
        assertTrue(presentation.contains("<p:sldId id=\"257\" r:id=\"rId3\"/>"))
        assertWellFormed(presentation)
    }

    @Test
    fun pptxIsDetectedAsPptx() {
        val file = file("a.pptx", PptxWriter.pptx(listOf(PptxWriter.Slide("t", listOf("x")))))
        assertEquals(DocumentFormat.PPTX, DocumentFormatDetector.detect(file))
        assertEquals(1, DocumentSupport.sortedSlideEntries(DocumentSupport.zipEntryNames(file)).size)
    }

    @Test
    fun appendSlideEditsPresentationRelsAndContentTypes() {
        val bytes = PptxWriter.pptx(listOf(PptxWriter.Slide("t", emptyList())))
        val presentation = entryText(bytes, "ppt/presentation.xml")
        val rels = entryText(bytes, "ppt/_rels/presentation.xml.rels")
        val contentTypes = entryText(bytes, "[Content_Types].xml")

        val newPresentation = PptxWriter.presentationWithSlide(presentation, 257, "rId3")
        assertTrue(newPresentation.contains("<p:sldId id=\"257\" r:id=\"rId3\"/>"))
        assertTrue(
            "the new sldId must stay inside sldIdLst",
            newPresentation.indexOf("rId3") < newPresentation.indexOf("</p:sldIdLst>")
        )

        val newRels = PptxWriter.relsWithSlide(rels, "rId3", "slides/slide2.xml")
        assertTrue(newRels.contains("Target=\"slides/slide2.xml\""))
        assertWellFormed(newRels)

        val newTypes = PptxWriter.contentTypesWithSlide(contentTypes, 2)
        assertTrue(newTypes.contains("/ppt/slides/slide2.xml"))
        assertWellFormed(newTypes)
        assertEquals("adding the same slide twice must be idempotent", newTypes, PptxWriter.contentTypesWithSlide(newTypes, 2))
    }

    @Test
    fun presentationWithSlideCreatesMissingSldIdLst() {
        val minimal = DocumentXml.HEADER + "<p:presentation xmlns:p=\"p\"></p:presentation>"
        val updated = PptxWriter.presentationWithSlide(minimal, 256, "rId2")
        assertTrue(updated.contains("<p:sldIdLst><p:sldId id=\"256\" r:id=\"rId2\"/></p:sldIdLst>"))
        assertWellFormed(updated)
    }

    @Test
    fun odpStoresMimetypeFirstUncompressedAndRoundTripsSlides() {
        val slides = listOf(
            PptxWriter.Slide("Слайд раз", listOf("тезис")),
            PptxWriter.Slide("Слайд два", emptyList()),
        )
        val bytes = OdfWriter.odp(slides)

        val names = entryNames(bytes)
        assertEquals("mimetype must be the FIRST entry", "mimetype", names.first())
        for (entry in names) assertWellFormed(entryText(bytes, entry))

        ZipFile(probe(bytes)).use { zip ->
            val mimetype = zip.getEntry("mimetype")
            assertEquals(ZipEntry.STORED, mimetype.method)
            assertEquals(
                OdfWriter.ODF_MIMETYPE_PRESENTATION,
                zip.getInputStream(mimetype).use { it.readBytes().toString(Charsets.UTF_8) }
            )
        }

        val content = entryText(bytes, "content.xml")
        assertEquals(2, DocumentSupport.countXmlElements(content, "page"))
        val pages = DocumentSupport.odpSlides(content)
        assertEquals(listOf("Слайд раз", "• тезис"), pages[0])
        assertEquals(listOf("Слайд два"), pages[1])

        val file = file("a.odp", bytes)
        assertEquals(DocumentFormat.ODP, DocumentFormatDetector.detect(file))
    }

    @Test
    fun appendOdpPageAddsAPage() {
        val bytes = OdfWriter.odp(listOf(PptxWriter.Slide("A", emptyList())))
        val content = entryText(bytes, "content.xml")
        val updated = OdfWriter.appendOdpPage(content, PptxWriter.Slide("B", listOf("x")))
        assertWellFormed(updated)
        assertEquals(2, DocumentSupport.countXmlElements(updated, "page"))
        assertEquals("B", DocumentSupport.odpSlides(updated)[1].first())
    }

    // --- SlideSpecParser ----------------------------------------------------------------------

    @Test
    fun slideSpecParsesStringsAndObjects() {
        val slides = SlideSpecParser.parse(
            "[\"Заголовок\\nпункт1\\nпункт2\", {\"title\": \"Другой\", \"lines\": [\"a\", \"b\"]}]"
        )
        assertEquals(PptxWriter.Slide("Заголовок", listOf("пункт1", "пункт2")), slides[0])
        assertEquals(PptxWriter.Slide("Другой", listOf("a", "b")), slides[1])
    }

    @Test
    fun slideSpecParsesTextObject() {
        val slides = SlideSpecParser.parse("[{\"title\": \"T\", \"text\": \"x\\ny\"}]")
        assertEquals(PptxWriter.Slide("T", listOf("x", "y")), slides.single())
    }

    @Test
    fun slideSpecRejectsMalformedAndEmpty() {
        val malformed = runCatching { SlideSpecParser.parse("not json") }.exceptionOrNull()
        assertTrue(malformed is IllegalArgumentException)
        val empty = runCatching { SlideSpecParser.parse("[]") }.exceptionOrNull()
        assertTrue(empty is IllegalArgumentException)
    }
}
