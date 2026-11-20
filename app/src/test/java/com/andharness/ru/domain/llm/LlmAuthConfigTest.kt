package com.andharness.ru.domain.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure defaults/parsing tests for [LlmAuthConfig] and [LlmAuthStyle]. */
class LlmAuthConfigTest {

    @Test
    fun defaultConfigIsBearerWithNoCustomNames() {
        assertEquals(LlmAuthStyle.BEARER, LlmAuthConfig.DEFAULT.style)
        assertEquals("", LlmAuthConfig().headerName)
        assertEquals("", LlmAuthConfig().queryParam)
        assertEquals(LlmAuthConfig(), LlmAuthConfig.DEFAULT)
    }

    @Test
    fun unknownOrMissingStorageValueFallsBackToBearer() {
        assertEquals(LlmAuthStyle.BEARER, LlmAuthStyle.fromStorage(null))
        assertEquals(LlmAuthStyle.BEARER, LlmAuthStyle.fromStorage(""))
        assertEquals(LlmAuthStyle.BEARER, LlmAuthStyle.fromStorage("nonsense"))
    }

    @Test
    fun storageValuesParseCaseInsensitively() {
        assertEquals(LlmAuthStyle.BEARER, LlmAuthStyle.fromStorage("bearer"))
        assertEquals(LlmAuthStyle.HEADER, LlmAuthStyle.fromStorage(" header "))
        assertEquals(LlmAuthStyle.QUERY, LlmAuthStyle.fromStorage("Query"))
        assertEquals(LlmAuthStyle.NONE, LlmAuthStyle.fromStorage("NONE"))
    }
}
