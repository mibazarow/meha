package com.andharness.ru.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for [resolveSafePath] path containment. Plain `java.io.File` only, no Android runtime.
 * Reference: https://developer.android.com/reference/java/io/File
 */
class SafePathTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun root(): File = File(temp.root, "Documents").apply { mkdirs() }

    private fun rejects(root: File, raw: String) {
        try {
            resolveSafePath(root, raw)
            throw AssertionError("Ожидался IllegalArgumentException для «$raw»")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }

    private fun rejects(roots: List<File>, raw: String) {
        try {
            resolveSafePath(roots, raw)
            throw AssertionError("Ожидался IllegalArgumentException для «$raw»")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }

    @Test
    fun rejectsTraversalAndAbsolutePaths() {
        val root = root()
        rejects(root, "../evil.txt")
        rejects(root, "../../etc/passwd")
        rejects(root, "/etc/passwd")
        rejects(root, "foo/../../bar")
        rejects(root, "Documents/../..")
        rejects(root, "a\u0000b")
        rejects(root, "C:/x")
        rejects(root, "")
        rejects(root, "   ")
    }

    @Test
    fun acceptsPathsInsideRoot() {
        val root = root()
        assertEquals(
            File(root, "Documents/notes.txt").canonicalFile,
            resolveSafePath(root, "Documents/notes.txt")
        )
        assertEquals(File(root, "Documents").canonicalFile, resolveSafePath(root, "Documents"))
    }

    @Test
    fun acceptsRootItself() {
        val root = root()
        assertEquals(root.canonicalFile, resolveSafePath(root, "."))
    }

    @Test
    fun siblingWithSharedPrefixIsNotInsideRoot() {
        val root = root()
        val sibling = File(temp.root, "Documents2").apply { mkdirs() }
        val siblingFile = File(sibling, "x").apply { writeText("x") }

        // The naive prefix check would wrongly pass ("…/Documents" is a prefix of "…/Documents2/x").
        assertTrue(siblingFile.canonicalPath.startsWith(root.canonicalPath))
        // The separator-aware check used by resolveSafePath correctly rejects it.
        assertFalse(siblingFile.canonicalPath.startsWith(root.canonicalPath + File.separator))

        rejects(root, "../Documents2/x")
    }

    @Test
    fun rejectsEscapeToSiblingDirectory() {
        val root = root()
        File(temp.root, "Other").mkdirs()
        rejects(root, "../Other/secret.txt")
    }

    @Test
    fun multiRootResolvesRelativePathUnderFirstMatchingRoot() {
        val first = File(temp.root, "First").apply { mkdirs() }
        val second = File(temp.root, "Second").apply { mkdirs() }
        val roots = listOf(first, second)

        assertEquals(
            File(first, "notes.txt").canonicalFile,
            resolveSafePath(roots, "notes.txt")
        )
        assertEquals(
            File(first, "sub/deep.txt").canonicalFile,
            resolveSafePath(roots, "sub/deep.txt")
        )
    }

    @Test
    fun multiRootAcceptsAbsolutePathInsideSecondRoot() {
        val first = File(temp.root, "First").apply { mkdirs() }
        val second = File(temp.root, "Second").apply { mkdirs() }
        val target = File(second, "photo.jpg").apply { writeText("x") }

        assertEquals(
            target.canonicalFile,
            resolveSafePath(listOf(first, second), target.absolutePath)
        )
        assertEquals(
            second.canonicalFile,
            resolveSafePath(listOf(first, second), second.absolutePath)
        )
    }

    @Test
    fun multiRootRejectsAbsolutePathOutsideEveryRoot() {
        val first = File(temp.root, "First").apply { mkdirs() }
        val second = File(temp.root, "Second").apply { mkdirs() }
        val outside = File(temp.root, "Outside").apply { mkdirs() }
        val target = File(outside, "secret.txt").apply { writeText("x") }

        rejects(listOf(first, second), target.absolutePath)
        rejects(listOf(first, second), "/etc/passwd")
    }

    @Test
    fun multiRootRejectsAbsolutePathContainingDotDot() {
        val first = File(temp.root, "First").apply { mkdirs() }
        val second = File(temp.root, "Second").apply { mkdirs() }

        rejects(listOf(first, second), "${second.canonicalPath}/sub/../evil.txt")
        rejects(listOf(first, second), "$first/../Second/evil.txt")
    }

    @Test
    fun multiRootRejectsPrefixConfusionAcrossRoots() {
        val first = File(temp.root, "Documents").apply { mkdirs() }
        val second = File(temp.root, "Documents2").apply { mkdirs() }
        val sibling = File(second, "x").apply { writeText("x") }

        // "…/Documents" is a naive prefix of "…/Documents2/x" but must not be treated as a root.
        assertTrue(sibling.canonicalPath.startsWith(first.canonicalPath))
        rejects(listOf(first), sibling.absolutePath)
        // A relative escape towards the sibling root is rejected by the `..` guard.
        rejects(listOf(first, second), "../Documents2/x")
    }

    /**
     * TOOLS_TASKS §4: the refusal must NAME the boundary, so the model can fix the path on the next
     * call instead of retrying against an unnamed "access error".
     */
    @Test
    fun refusalNamesTheAllowedRoots() {
        val first = File(temp.root, "Documents").apply { mkdirs() }
        val second = File(temp.root, "Music").apply { mkdirs() }

        val message = try {
            resolveSafePath(listOf(first, second), "/etc/passwd")
            throw AssertionError("Ожидался IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            expected.message.orEmpty()
        }

        assertTrue("the offending path must be quoted", message.contains("/etc/passwd"))
        assertTrue("the first allowed root must be named", message.contains(first.canonicalPath))
        assertTrue("every allowed root must be named", message.contains(second.canonicalPath))
    }

    @Test
    fun multiRootSingleRootBehavesLikeLegacyOverload() {
        val root = File(temp.root, "Documents").apply { mkdirs() }

        assertEquals(root.canonicalFile, resolveSafePath(listOf(root), "."))
        assertEquals(
            File(root, "a.txt").canonicalFile,
            resolveSafePath(listOf(root), "a.txt")
        )
        rejects(emptyList(), "a.txt")
    }
}
