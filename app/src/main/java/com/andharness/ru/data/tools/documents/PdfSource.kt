package com.andharness.ru.data.tools.documents

import java.io.File

/** A verified PDF input: the resolved file plus the page count PdfBox reported when opening it. */
internal sealed interface PdfSource {
    data class Ok(val file: File, val pages: Int) : PdfSource

    data class Failure(val message: String) : PdfSource
}
