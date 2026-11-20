package com.andharness.ru.data.tools.documents

/** A resolved XLSX worksheet (its XML entry and text) or the honest reason it cannot be resolved. */
internal sealed interface SheetTarget {
    data class Ok(
        val names: List<String>,
        val index: Int,
        val entry: String,
        val xml: String,
    ) : SheetTarget

    data class Failure(val message: String) : SheetTarget
}
