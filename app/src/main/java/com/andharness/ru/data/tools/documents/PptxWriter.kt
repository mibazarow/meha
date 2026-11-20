package com.andharness.ru.data.tools.documents

/**
 * Minimal-but-valid PresentationML (PPTX) package generation plus a small presentation editor:
 * build a deck from a list of slides and append one slide to an existing `ppt/presentation.xml`.
 *
 * The package mirrors the shape the project's own reader expects (`ppt/slides/slideN.xml` with
 * `<a:t>` runs inside `<a:p>` paragraphs), while also carrying the parts a suite needs to open it
 * (a slide master, one blank layout and a theme). Everything here is plain `java.util.zip` + string
 * XML — no Android APIs — so the module stays unit-testable on the JVM.
 *
 * References:
 * - PresentationML (slides, shapes, `sldIdLst`) —
 *   https://learn.microsoft.com/en-us/office/open-xml/presentation/presentationml
 * - DrawingML (text bodies, `a:t`) —
 *   https://learn.microsoft.com/en-us/office/open-xml/drawingml/drawingml
 * - OOXML package structure — https://learn.microsoft.com/en-us/office/open-xml/word/package
 * - `java.util.zip.ZipOutputStream` —
 *   https://developer.android.com/reference/java/util/zip/ZipOutputStream
 */
internal object PptxWriter {

    private const val P_NS = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val PKG_RELS_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val CT_NS = "http://schemas.openxmlformats.org/package/2006/content-types"

    private const val SLIDE_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
    private const val PRESENTATION_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
    private const val SLIDE_MASTER_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"
    private const val SLIDE_LAYOUT_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"
    private const val THEME_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.theme+xml"

    /** 16:9 slide canvas in EMU (English Metric Units). */
    private const val SLIDE_WIDTH = 12192000
    private const val SLIDE_HEIGHT = 6858000

    /** One slide: an optional [title] plus bullet [lines] (each line becomes a paragraph). */
    data class Slide(val title: String?, val lines: List<String>)

    /** A minimal PresentationML package with one `ppt/slides/slideN.xml` per [slide]. */
    fun pptx(slides: List<Slide>): ByteArray {
        val entries = mutableListOf<Pair<String, String>>()
        val overrides = StringBuilder()
        val presentationRels = StringBuilder()

        entries += "[Content_Types].xml" to CONTENT_TYPES_PLACEHOLDER
        entries += "_rels/.rels" to (
            DocumentXml.HEADER +
                "<Relationships xmlns=\"$PKG_RELS_NS\">" +
                "<Relationship Id=\"rId1\" Type=\"$R_NS/officeDocument\" Target=\"ppt/presentation.xml\"/>" +
                "</Relationships>"
            )
        entries += "ppt/slideMasters/slideMaster1.xml" to slideMaster()
        entries += "ppt/slideMasters/_rels/slideMaster1.xml.rels" to (
            DocumentXml.HEADER +
                "<Relationships xmlns=\"$PKG_RELS_NS\">" +
                "<Relationship Id=\"rId1\" Type=\"$R_NS/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"$R_NS/theme\" Target=\"../theme/theme1.xml\"/>" +
                "</Relationships>"
            )
        entries += "ppt/slideLayouts/slideLayout1.xml" to slideLayout()
        entries += "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to (
            DocumentXml.HEADER +
                "<Relationships xmlns=\"$PKG_RELS_NS\">" +
                "<Relationship Id=\"rId1\" Type=\"$R_NS/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/>" +
                "</Relationships>"
            )
        entries += "ppt/theme/theme1.xml" to theme()

        if (slides.isEmpty()) {
            // A deck must still hold the master/layout/theme; keep a valid, slide-less package.
            entries += "ppt/presentation.xml" to presentation(emptyList())
            entries += "ppt/_rels/presentation.xml.rels" to (
                DocumentXml.HEADER +
                    "<Relationships xmlns=\"$PKG_RELS_NS\">" +
                    "<Relationship Id=\"rId1\" Type=\"$R_NS/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>" +
                    "</Relationships>"
                )
        } else {
            slides.forEachIndexed { index, slide ->
                val number = index + 1
                entries += "ppt/slides/slide$number.xml" to slideXml(slide)
                entries += "ppt/slides/_rels/slide$number.xml.rels" to (
                    DocumentXml.HEADER +
                        "<Relationships xmlns=\"$PKG_RELS_NS\">" +
                        "<Relationship Id=\"rId1\" Type=\"$R_NS/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
                        "</Relationships>"
                    )
                overrides.append(
                    "<Override PartName=\"/ppt/slides/slide$number.xml\" ContentType=\"$SLIDE_CONTENT_TYPE\"/>"
                )
                val relId = "rId${index + 2}"
                presentationRels.append(
                    "<Relationship Id=\"$relId\" Type=\"$R_NS/slide\" Target=\"slides/slide$number.xml\"/>"
                )
            }
            entries += "ppt/presentation.xml" to presentation(
                slides.indices.map { it }
            )
            entries += "ppt/_rels/presentation.xml.rels" to (
                DocumentXml.HEADER +
                    "<Relationships xmlns=\"$PKG_RELS_NS\">" +
                    "<Relationship Id=\"rId1\" Type=\"$R_NS/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>" +
                    presentationRels +
                    "</Relationships>"
                )
        }

        val finalEntries = entries.map { (name, xml) ->
            when (name) {
                "[Content_Types].xml" -> name to contentTypes(overrides.toString())
                else -> name to xml
            }
        }
        return ZipPackageWriter.zip(*finalEntries.toTypedArray())
    }

    /** `ppt/presentation.xml` referencing the given slide [indices] (0-based) in order. */
    private fun presentation(indices: List<Int>): String {
        val sldIds = indices.joinToString("") { index ->
            "<p:sldId id=\"${SLIDE_ID_BASE + index}\" r:id=\"rId${index + 2}\"/>"
        }
        return DocumentXml.HEADER +
            "<p:presentation xmlns:a=\"$A_NS\" xmlns:r=\"$R_NS\" xmlns:p=\"$P_NS\" saveSubsetFonts=\"1\">" +
            "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>" +
            "<p:sldIdLst>$sldIds</p:sldIdLst>" +
            "<p:sldSz cx=\"$SLIDE_WIDTH\" cy=\"$SLIDE_HEIGHT\"/>" +
            "<p:notesSz cx=\"6858000\" cy=\"9144000\"/>" +
            "</p:presentation>"
    }

    private fun contentTypes(overrides: String): String =
        DocumentXml.HEADER +
            "<Types xmlns=\"$CT_NS\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"$PRESENTATION_CONTENT_TYPE\"/>" +
            "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"$SLIDE_MASTER_CONTENT_TYPE\"/>" +
            "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"$SLIDE_LAYOUT_CONTENT_TYPE\"/>" +
            "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"$THEME_CONTENT_TYPE\"/>" +
            overrides +
            "</Types>"

    /** `ppt/slides/slideN.xml`: a title text box plus one body text box with the bullet paragraphs. */
    fun slideXml(slide: Slide): String {
        val shapes = StringBuilder()
        var shapeId = 2
        val title = slide.title?.takeIf { it.isNotBlank() }
        if (title != null) {
            shapes.append(textBox(shapeId++, "Title 1", TITLE_X, TITLE_Y, TITLE_CX, TITLE_CY, title, bold = true))
        }
        if (slide.lines.isNotEmpty()) {
            val body = slide.lines.joinToString("\n")
            shapes.append(textBox(shapeId, "Body 1", BODY_X, BODY_Y, BODY_CX, BODY_CY, body, bullet = true))
        }
        return DocumentXml.HEADER +
            "<p:sld xmlns:a=\"$A_NS\" xmlns:r=\"$R_NS\" xmlns:p=\"$P_NS\">" +
            "<p:cSld><p:spTree>" +
            "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
            "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>" +
            "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>" +
            shapes +
            "</p:spTree></p:cSld>" +
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>" +
            "</p:sld>"
    }

    private fun textBox(
        id: Int,
        name: String,
        x: Int,
        y: Int,
        cx: Int,
        cy: Int,
        text: String,
        bold: Boolean = false,
        bullet: Boolean = false,
    ): String {
        val paragraphs = text.split('\n').joinToString("") { line ->
            val content = if (bullet && line.isNotEmpty()) "• $line" else line
            paragraph(content, bold)
        }
        return "<p:sp>" +
            "<p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>" +
            "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>" +
            "<a:noFill/><a:ln><a:noFill/></a:ln></p:spPr>" +
            "<p:txBody><a:bodyPr wrap=\"square\"><a:normAutofit/></a:bodyPr><a:lstStyle/>" +
            paragraphs +
            "</p:txBody></p:sp>"
    }

    private fun paragraph(text: String, bold: Boolean): String {
        val boldAttr = if (bold) " b=\"1\"" else ""
        val safe = DocumentXml.xmlEscape(DocumentXml.xmlSafe(text))
        return "<a:p><a:r><a:rPr lang=\"ru-RU\"$boldAttr dirty=\"0\"/><a:t>$safe</a:t></a:r>" +
            "<a:endParaRPr lang=\"ru-RU\"/></a:p>"
    }

    private fun slideMaster(): String =
        DocumentXml.HEADER +
            "<p:sldMaster xmlns:a=\"$A_NS\" xmlns:r=\"$R_NS\" xmlns:p=\"$P_NS\">" +
            "<p:cSld><p:spTree>" +
            "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
            "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>" +
            "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>" +
            "</p:spTree></p:cSld>" +
            "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" " +
            "accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" " +
            "accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
            "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>" +
            "<p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles>" +
            "</p:sldMaster>"

    private fun slideLayout(): String =
        DocumentXml.HEADER +
            "<p:sldLayout xmlns:a=\"$A_NS\" xmlns:r=\"$R_NS\" xmlns:p=\"$P_NS\" type=\"blank\" preserve=\"1\">" +
            "<p:cSld name=\"Blank\"><p:spTree>" +
            "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
            "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>" +
            "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>" +
            "</p:spTree></p:cSld>" +
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>" +
            "</p:sldLayout>"

    private fun theme(): String =
        DocumentXml.HEADER +
            "<a:theme xmlns:a=\"$A_NS\" name=\"Office Theme\"><a:themeElements>" +
            "<a:clrScheme name=\"Office\">" +
            "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1>" +
            "<a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>" +
            "<a:dk2><a:srgbClr val=\"44546A\"/></a:dk2><a:lt2><a:srgbClr val=\"E7E6E6\"/></a:lt2>" +
            "<a:accent1><a:srgbClr val=\"4472C4\"/></a:accent1><a:accent2><a:srgbClr val=\"ED7D31\"/></a:accent2>" +
            "<a:accent3><a:srgbClr val=\"A5A5A5\"/></a:accent3><a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4>" +
            "<a:accent5><a:srgbClr val=\"5B9BD5\"/></a:accent5><a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6>" +
            "<a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink>" +
            "</a:clrScheme>" +
            "<a:fontScheme name=\"Office\">" +
            "<a:majorFont><a:latin typeface=\"Calibri Light\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>" +
            "<a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont>" +
            "</a:fontScheme>" +
            "<a:fmtScheme name=\"Office\">" +
            "<a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
            "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
            "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:fillStyleLst>" +
            "<a:lnStyleLst><a:ln><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>" +
            "<a:ln><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln>" +
            "<a:ln><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln></a:lnStyleLst>" +
            "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle>" +
            "<a:effectStyle><a:effectLst/></a:effectStyle>" +
            "<a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>" +
            "<a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
            "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
            "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst>" +
            "</a:fmtScheme></a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"

    /**
     * Appends a slide to an existing `ppt/presentation.xml`, entering a new `<p:sldId>` before
     * `</p:sldIdLst>` (creating it when absent). [slideId] is the numeric shape id (>= 256) and
     * [relId] the relationship id added to `presentation.xml.rels`.
     */
    fun presentationWithSlide(
        presentationXml: String,
        slideId: Int,
        relId: String,
    ): String {
        val entry = "<p:sldId id=\"$slideId\" r:id=\"$relId\"/>"
        val close = presentationXml.indexOf("</p:sldIdLst>")
        if (close >= 0) return presentationXml.substring(0, close) + entry + presentationXml.substring(close)
        val selfClosing = Regex("(?s)<p:sldIdLst\\s*/>").find(presentationXml)
        if (selfClosing != null) {
            return presentationXml.replaceRange(
                selfClosing.range,
                "<p:sldIdLst>$entry</p:sldIdLst>"
            )
        }
        val bodyClose = presentationXml.indexOf("</p:presentation>")
        require(bodyClose >= 0) { "ppt/presentation.xml не содержит </p:presentation>." }
        return presentationXml.substring(0, bodyClose) +
            "<p:sldIdLst>$entry</p:sldIdLst>" +
            presentationXml.substring(bodyClose)
    }

    /** Appends a slide `<Relationship>` to `ppt/_rels/presentation.xml.rels`. */
    fun relsWithSlide(relsXml: String, relId: String, target: String): String {
        val close = relsXml.indexOf("</Relationships>")
        require(close >= 0) { "ppt/_rels/presentation.xml.rels не содержит </Relationships>." }
        val relationship = "<Relationship Id=\"$relId\" Type=\"$R_NS/slide\" Target=\"$target\"/>"
        return relsXml.substring(0, close) + relationship + relsXml.substring(close)
    }

    /** Adds the `<Override>` for slide [slideNumber] to `[Content_Types].xml`. */
    fun contentTypesWithSlide(contentTypesXml: String, slideNumber: Int): String {
        if (contentTypesXml.contains("/ppt/slides/slide$slideNumber.xml")) return contentTypesXml
        val close = contentTypesXml.indexOf("</Types>")
        require(close >= 0) { "[Content_Types].xml не содержит </Types>." }
        val override = "<Override PartName=\"/ppt/slides/slide$slideNumber.xml\" " +
            "ContentType=\"$SLIDE_CONTENT_TYPE\"/>"
        return contentTypesXml.substring(0, close) + override + contentTypesXml.substring(close)
    }

    private const val SLIDE_ID_BASE = 256
    private const val TITLE_X = 457200
    private const val TITLE_Y = 457200
    private const val TITLE_CX = 11176000
    private const val TITLE_CY = 1000000
    private const val BODY_X = 457200
    private const val BODY_Y = 1600000
    private const val BODY_CX = 11176000
    private const val BODY_CY = 4800000
    private const val CONTENT_TYPES_PLACEHOLDER = "CONTENT_TYPES"
}
