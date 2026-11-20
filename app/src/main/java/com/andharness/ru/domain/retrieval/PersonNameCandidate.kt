package com.andharness.ru.domain.retrieval


/** One person with every name they can be mentioned by (display name + aliases). */
data class PersonNameCandidate(val personId: Long, val names: List<String>)
