package com.andharness.ru.domain.person


/** One alternative name of a [Person] (update.md §4.3/§4.5), e.g. "Серёга" or "Serega". */
data class PersonAlias(
    val id: Long,
    val personId: Long,
    val alias: String
)
