package com.andharness.ru.domain.print


/** Honest result of a `print_file` request: the reported success flag and its message. */
data class PrintFileResult(val success: Boolean, val message: String)
