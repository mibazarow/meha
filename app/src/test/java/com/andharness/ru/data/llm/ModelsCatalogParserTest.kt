package com.andharness.ru.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsCatalogParserTest {

    @Test
    fun parsesOpenAiDataIdsAndContextLength() {
        val body = """
            {"object":"list","data":[
              {"id":"gpt-4o","object":"model","context_length":128000},
              {"id":"gpt-4o-mini","object":"model"}
            ]}
        """.trimIndent()

        val parsed = ModelsCatalogParser.parseModels(body)

        assertFalse(parsed.malformed)
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), parsed.models)
        assertEquals(128000L, parsed.contextById["gpt-4o"])
        assertNull(parsed.contextById["gpt-4o-mini"])
    }

    @Test
    fun parsesMaxModelLen() {
        val body = """{"data":[{"id":"local-model","max_model_len":32768}]}"""

        val parsed = ModelsCatalogParser.parseModels(body)

        assertEquals(listOf("local-model"), parsed.models)
        assertEquals(32768L, parsed.contextById["local-model"])
    }

    @Test
    fun parsesMetaNCtxAndPrefersNCtxOverTrain() {
        // Real llama.cpp /v1/models shape: meta carries n_ctx (serving window) and n_ctx_train.
        val body = """
            {"models":[{"name":"x.gguf","model":"x.gguf"}],"object":"list","data":[
              {"id":"x.gguf","object":"model","meta":{"n_vocab":262144,"n_ctx":155136,"n_ctx_train":262144}}
            ]}
        """.trimIndent()

        val parsed = ModelsCatalogParser.parseModels(body)

        assertEquals(listOf("x.gguf"), parsed.models)
        assertEquals(155136L, parsed.contextById["x.gguf"])
    }

    @Test
    fun parsesTopProviderContextLength() {
        val body = """{"data":[{"id":"anthropic/claude-3.5","top_provider":{"context_length":200000}}]}"""

        val parsed = ModelsCatalogParser.parseModels(body)

        assertEquals(200000L, parsed.contextById["anthropic/claude-3.5"])
    }

    @Test
    fun parsesNestedLimitContext() {
        // OpenAI-compatible gateways (e.g. neuraldeep) nest the limit:
        // {"limit":{"context":1048576,"output":384000}}
        val body =
            """{"data":[{"id":"deepseek-v4.1-flash","limit":{"context":1048576,"output":384000}}]}"""

        val parsed = ModelsCatalogParser.parseModels(body)

        assertEquals(listOf("deepseek-v4.1-flash"), parsed.models)
        assertEquals(1_048_576L, parsed.contextById["deepseek-v4.1-flash"])
    }

    @Test
    fun parsesPlainArray() {
        val body = """[{"id":"a","context_length":4096},{"id":"b"}]"""

        val parsed = ModelsCatalogParser.parseModels(body)

        assertFalse(parsed.malformed)
        assertEquals(listOf("a", "b"), parsed.models)
        assertEquals(4096L, parsed.contextById["a"])
    }

    @Test
    fun parsesModelsKeyWithNameAndDedupes() {
        val body = """{"models":[{"name":"llama3:8b"},{"model":"llama3:8b"},{"name":"qwen2.5"}]}"""

        val parsed = ModelsCatalogParser.parseModels(body)

        assertEquals(listOf("llama3:8b", "qwen2.5"), parsed.models)
    }

    @Test
    fun validButEmptyListIsNotEmptyFailure() {
        val parsed = ModelsCatalogParser.parseModels("""{"models":[]}""")

        assertFalse(parsed.malformed)
        assertTrue(parsed.models.isEmpty())
    }

    @Test
    fun malformedBodyIsAnHonestFailure() {
        val parsed = ModelsCatalogParser.parseModels("this is not json")

        assertTrue(parsed.malformed)
        assertTrue(parsed.models.isEmpty())
    }

    @Test
    fun jsonScalarIsMalformed() {
        val parsed = ModelsCatalogParser.parseModels("\"oops\"")

        assertTrue(parsed.malformed)
    }

    @Test
    fun parsePropsFromDefaultGenerationSettings() {
        val body = """
            {"default_generation_settings":{"params":{"temperature":0.7},"n_ctx":155136},
             "total_slots":1,"model_alias":"gemma.gguf"}
        """.trimIndent()

        assertEquals(155136L, ModelsCatalogParser.parseProps(body))
    }

    @Test
    fun parsePropsFromTopLevelNCtx() {
        assertEquals(4096L, ModelsCatalogParser.parseProps("""{"n_ctx":4096}"""))
    }

    @Test
    fun parsePropsMalformedReturnsNull() {
        assertNull(ModelsCatalogParser.parseProps("<html>not found</html>"))
        assertNull(ModelsCatalogParser.parseProps("""{"total_slots":1}"""))
    }
}
