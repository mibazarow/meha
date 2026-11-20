package com.andharness.ru.domain.tools

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Characterization test for the tool wiring layer (W0 of the refactoring plan).
 *
 * The production `Set<ToolHandler>` is assembled by Hilt (`ToolBindingsModule.provideToolHandlers`)
 * and cannot be instantiated in a JVM unit test without the Android/Hilt runtime. Instead this test
 * pins the equivalent source-level invariant: every name in [DefaultTools.all] is wired somewhere
 * in the handler layer (`data/tools/`), either as a concrete handler's `override val name = "…"`,
 * as an argument to a shared handler factory (`pdfFromLines`, `readHandler`, `WebEngine` names), or
 * as one of the two registry built-ins (`selftest_run`, `tool_search`, handled in `ToolRegistryImpl`).
 *
 * The generator/reference lists are deliberately excluded (`selftest/SelfTestCases.kt`), so the
 * assertion only credits name-like string literals that live with the handlers — i.e. an unwired
 * catalog entry (a tool with a definition but no handler) fails here.
 *
 * This guards the handler moves planned for W2: names may move between files, but none may vanish.
 */
class ToolHandlerRegistryCoverageTest {

    /** A standalone tool-name-like Kotlin string literal, e.g. `"files_list"`. */
    private val toolNameLiteral = Regex("\"([a-z][a-z0-9_]{2,})\"")

    @Test
    fun everyCatalogToolNameIsRegisteredInTheHandlerLayer() {
        val registered = registeredHandlerNames()
        val missing = DefaultTools.all().map { it.name }.filterNot { it in registered }
        assertTrue(
            "catalog tools that are not wired to any handler under data/tools/: $missing",
            missing.isEmpty()
        )
    }

    private fun registeredHandlerNames(): Set<String> {
        val root = handlerSourceRoot()
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.contains("/selftest/") }
            .flatMap { toolNameLiteral.findAll(it.readText()).map { match -> match.groupValues[1] } }
            .toSet()
    }

    private fun handlerSourceRoot(): File {
        val relative = "data/tools"
        val candidates = listOf(
            File("src/main/java/com/andharness/ru/$relative"),
            File("app/src/main/java/com/andharness/ru/$relative"),
        )
        candidates.firstOrNull { it.isDirectory }?.let { return it }

        // Tests normally run with `app/` (or the repo root) as the working directory; walk up a few
        // levels so the test is not sensitive to which one it is.
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(6) {
            val candidate = dir?.let { File(it, "app/src/main/java/com/andharness/ru/$relative") }
            if (candidate != null && candidate.isDirectory) return candidate
            dir = dir?.parentFile
        }
        error("handler source root not found (cwd=${System.getProperty("user.dir")})")
    }
}
