package com.andharness.ru.domain.tools

import com.andharness.ru.data.agent.SystemPrompts
import com.andharness.ru.domain.serialization.AppJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Exports the tool catalog exactly as the agent sees it, for the fine-tune dataset generator
 * (`create-local-llm/scripts/gen_dataset.py` reads `create-local-llm/build/tools.json`).
 *
 * The catalog is serialized in the same OpenAI tool shape the cloud path sends to the LLM: an
 * array of `{name, description, parameters}`. It is built from the single source of truth
 * (`DefaultTools.all()` -> `Tool.toLlmTool()`), so the export cannot drift from the runtime
 * catalog. No DI / Android runtime is needed — this is a plain JVM unit test.
 *
 * The output file is gitignored (see `.gitignore`: everything under `create-local-llm/build/`).
 * Run with:
 *
 *     ./gradlew test --tests "*ToolsExportTest*"
 *
 * The test is idempotent: it overwrites the file with the same deterministic content each run.
 */
class ToolsExportTest {

    @Test
    fun exportsToolCatalogAsJson() {
        val source = DefaultTools.all()
        val tools = source.map { it.toLlmTool() }

        val rows: List<JsonObject> = tools.map { tool ->
            buildJsonObject {
                put("name", JsonPrimitive(tool.name))
                put("description", JsonPrimitive(tool.description))
                put("parameters", tool.parameters)
            }
        }

        val out = File(repoRoot(), "create-local-llm/build/tools.json")
        out.parentFile?.mkdirs()
        out.writeText(AppJson.Pretty.encodeToString(ListSerializer(JsonObject.serializer()), rows))

        println("TOOLS_EXPORT count=${tools.size} bytes=${out.length()} path=${out.absolutePath}")

        // Second export: the metadata flags that `toLlmTool()` omits (read from `Tool` directly).
        val metaRows: List<JsonObject> = source.map { tool ->
            buildJsonObject {
                put("name", JsonPrimitive(tool.name))
                put("description", JsonPrimitive(tool.description))
                put("parameters", tool.parameters)
                put("dangerous", JsonPrimitive(tool.dangerous))
                put(
                    "needsPermission",
                    tool.needsPermission?.let { JsonPrimitive(it) } ?: JsonNull
                )
            }
        }

        val metaOut = File(repoRoot(), "create-local-llm/build/tools_meta.json")
        metaOut.parentFile?.mkdirs()
        metaOut.writeText(
            AppJson.Pretty.encodeToString(ListSerializer(JsonObject.serializer()), metaRows)
        )

        val dangerous = source.count { it.dangerous }
        val permitted = source.count { it.needsPermission != null }
        println(
            "TOOLS_META count=${source.size} dangerous=$dangerous permitted=$permitted " +
                "bytes=${metaOut.length()} path=${metaOut.absolutePath}"
        )

        // Third export: the exact system prompt the cloud model runs with, so the local fine-tune
        // trains and infers on byte-identical instructions. Read from the same source of truth
        // (SystemPrompts.BASE_SYSTEM_PROMPT) the runtime uses.
        val prompt = SystemPrompts.BASE_SYSTEM_PROMPT
        val promptOut = File(repoRoot(), "create-local-llm/build/system_prompt.txt")
        promptOut.parentFile?.mkdirs()
        promptOut.writeText(prompt)
        println(
            "SYSTEM_PROMPT chars=${prompt.length} bytes=${promptOut.length()} " +
                "path=${promptOut.absolutePath}"
        )

        assertTrue("tool catalog must not be empty", tools.isNotEmpty())
        assertTrue("system prompt must not be blank", prompt.isNotBlank())
        assertEquals("unexpected tool count in the catalog", EXPECTED_TOOL_COUNT, tools.size)
        assertTrue(
            "tool names must be unique in the catalog",
            tools.map { it.name }.toSet().size == tools.size
        )
    }

    /**
     * Locates the repository root from the Gradle test working directory (for the `app` module
     * that is `<root>/app`), so the export lands at `<root>/create-local-llm/build/tools.json`.
     */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("settings.gradle.kts not found above ${File("").absolutePath}")
    }

    private companion object {
        const val EXPECTED_TOOL_COUNT = 546
    }
}
