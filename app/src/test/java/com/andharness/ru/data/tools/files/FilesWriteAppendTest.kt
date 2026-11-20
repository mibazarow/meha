package com.andharness.ru.data.tools.files

import com.andharness.ru.domain.agent.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for [FilesWriteToolHandler] / [FilesAppendToolHandler]: the BUG-050 single-call size
 * guard (under/over/boundary + the honest message), and the write-in-parts semantics (concatenation,
 * UTF-8, no truncation). The handlers take a [pathResolver] lambda so they run against a temp dir
 * without an Android Context.
 */
class FilesWriteAppendTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val path by lazy { File(temp.root, "MehaTest/out.txt").absolutePath }

    private fun resolver(): (JsonObject, String) -> String = { _, _ -> path }

    private fun args(vararg kv: Pair<String, Any>): JsonObject = buildJsonObject {
        kv.forEach { (k, v) -> put(k, v.toString()) }
    }

    private fun write(vararg kv: Pair<String, Any>): ToolResult =
        runBlocking { FilesWriteToolHandler(resolver(), FileBackups()).execute(args(*kv)) }

    private fun append(content: String): ToolResult =
        runBlocking { FilesAppendToolHandler(resolver()).execute(args("path" to path, "content" to content)) }

    private fun read(): String = File(path).readText(Charsets.UTF_8)

    // ---- BUG-050 size guard: decision (under / over / boundary) ----

    @Test
    fun writeUnderThresholdSucceeds() {
        val r = write("path" to path, "content" to "x".repeat(FileToolIo.MAX_SINGLE_WRITE_BYTES - 1))
        assertTrue("under-threshold write must succeed: ${r.output}", r.success)
        assertEquals((FileToolIo.MAX_SINGLE_WRITE_BYTES - 1).toLong(), File(path).length())
    }

    @Test
    fun writeExactlyAtThresholdSucceeds() {
        val r = write("path" to path, "content" to "x".repeat(FileToolIo.MAX_SINGLE_WRITE_BYTES))
        assertTrue("exactly-at-threshold write must succeed: ${r.output}", r.success)
        assertEquals(FileToolIo.MAX_SINGLE_WRITE_BYTES.toLong(), File(path).length())
    }

    @Test
    fun writeOverThresholdReturnsHonestError() {
        val r = write("path" to path, "content" to "x".repeat(FileToolIo.MAX_SINGLE_WRITE_BYTES + 1))
        assertFalse(r.success)
        assertEquals(FileToolIo.TOO_LARGE_ONE_CALL_MESSAGE, r.output)
        assertFalse("an over-size write must NOT create the file", File(path).exists())
    }

    @Test
    fun appendOverThresholdReturnsHonestError() {
        write("path" to path, "content" to "seed")
        val before = File(path).length()
        val r = append("y".repeat(FileToolIo.MAX_SINGLE_WRITE_BYTES + 1))
        assertFalse(r.success)
        assertEquals(FileToolIo.TOO_LARGE_ONE_CALL_MESSAGE, r.output)
        assertEquals("an over-size append must not grow the file", before, File(path).length())
    }

    @Test
    fun thresholdMessageStatesTheLimit() {
        assertTrue(FileToolIo.TOO_LARGE_ONE_CALL_MESSAGE.contains("${FileToolIo.MAX_SINGLE_WRITE_BYTES / 1024} КБ"))
        assertTrue(FileToolIo.TOO_LARGE_ONE_CALL_MESSAGE.contains("files_write"))
        assertTrue(FileToolIo.TOO_LARGE_ONE_CALL_MESSAGE.contains("files_append"))
    }

    // ---- write-in-parts semantics: concatenation, UTF-8, no truncation ----

    @Test
    fun writeThenAppendConcatenatesUtf8WithoutTruncation() {
        val part1 = "PART1-😀-привет-мир\n"          // emoji + Cyrillic (multi-byte UTF-8)
        val part2 = "PART2-line\n"
        val part3 = "PART3-🚀-конец\n"

        val w = write("path" to path, "content" to part1)
        assertTrue("first chunk: ${w.output}", w.success)
        val a1 = append(part2)
        assertTrue("append 1: ${a1.output}", a1.success)
        val a2 = append(part3)
        assertTrue("append 2: ${a2.output}", a2.success)

        val expected = part1 + part2 + part3
        assertEquals(expected, read())
        // No truncation: the on-disk byte length equals the full expected payload.
        assertEquals(
            expected.toByteArray(Charsets.UTF_8).size,
            File(path).length().toInt()
        )
    }

    @Test
    fun manySmallAppendsReachLargeSize() {
        val chunk = "abCD-😀-测试\n"          // 13 bytes in UTF-8
        val count = 10_000                    // ~130 KB assembled from many appends
        write("path" to path, "content" to "")
        repeat(count) { append(chunk) }
        val expected = chunk.repeat(count)
        assertEquals(expected, read())
        assertEquals(expected.toByteArray(Charsets.UTF_8).size, File(path).length().toInt())
    }
}
