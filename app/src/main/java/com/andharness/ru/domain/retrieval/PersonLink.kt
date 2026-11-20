package com.andharness.ru.domain.retrieval


/** One detected mention: a person and the strength of the link. */
data class PersonLink(val personId: Long, val weight: Float)
