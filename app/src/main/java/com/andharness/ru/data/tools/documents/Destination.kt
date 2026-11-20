package com.andharness.ru.data.tools.documents

import java.io.File

/** A resolved, clobber-checked output path or the honest reason it cannot be written. */
internal sealed interface Destination {
    data class Ok(val file: File) : Destination

    data class Failure(val message: String) : Destination
}
