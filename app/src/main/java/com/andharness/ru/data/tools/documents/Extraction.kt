package com.andharness.ru.data.tools.documents


/** Outcome of a format-specific extraction: either the text or an honest error message. */
internal sealed interface Extraction {
    data class Success(val text: String) : Extraction

    data class Failure(val message: String) : Extraction
}
