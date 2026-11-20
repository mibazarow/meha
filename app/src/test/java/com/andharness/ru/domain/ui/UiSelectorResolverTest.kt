package com.andharness.ru.domain.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [UiSelectorResolver], the pure semantic-selector engine behind the `ui_*` tools.
 *
 * They lock in the app-version-resilience contract: a step can be replayed by text /
 * contentDescription / resourceId / className / index instead of raw coordinates, ranked by
 * specificity, and a non-clickable label resolves up to its nearest clickable ancestor.
 */
class UiSelectorResolverTest {

    // ---- exact text ----

    @Test
    fun exactTextMatches() {
        val tree = row(label("Save"), label("Cancel"))
        val match = UiSelectorResolver.resolve(UiSelector(text = "Save"), tree)
        assertNotNull(match)
        assertEquals("Save", match!!.node.text)
    }

    @Test
    fun exactTextDoesNotMatchSubstring() {
        val tree = row(label("Save changes"))
        assertNull(UiSelectorResolver.resolve(UiSelector(text = "Save"), tree))
    }

    @Test
    fun exactTextIsCaseInsensitive() {
        val tree = row(label("Save"))
        assertEquals("Save", UiSelectorResolver.resolve(UiSelector(text = "save"), tree)?.node?.text)
        assertEquals("Save", UiSelectorResolver.resolve(UiSelector(text = "SAVE"), tree)?.node?.text)
    }

    // ---- contains ----

    @Test
    fun textContainsMatchesSubstringCaseInsensitively() {
        val tree = row(label("Save changes"))
        val match = UiSelectorResolver.resolve(UiSelector(textContains = "changes"), tree)
        assertEquals("Save changes", match?.node?.text)
        assertEquals("Save changes", UiSelectorResolver.resolve(UiSelector(textContains = "CHAN"), tree)?.node?.text)
    }

    // ---- resourceId ----

    @Test
    fun resourceIdMatchesShortName() {
        val tree = row(button(text = "OK", id = "login_button"))
        assertEquals("login_button", UiSelectorResolver.resolve(UiSelector(resourceId = "login_button"), tree)?.node?.resourceId)
    }

    @Test
    fun resourceIdMatchesFullyQualifiedSelector() {
        val tree = row(button(text = "OK", id = "login_button"))
        val match = UiSelectorResolver.resolve(UiSelector(resourceId = "com.example.app:id/login_button"), tree)
        assertEquals("login_button", match?.node?.resourceId)
    }

    // ---- contentDescription ----

    @Test
    fun contentDescriptionExactAndPartial() {
        val image = UiNode(contentDescription = "Search products", className = "android.widget.ImageView")
        val tree = row(image)
        assertEquals("Search products", UiSelectorResolver.resolve(UiSelector(contentDescription = "Search products"), tree)?.node?.contentDescription)
        assertEquals("Search products", UiSelectorResolver.resolve(UiSelector(contentDescription = "products"), tree)?.node?.contentDescription)
    }

    @Test
    fun textFallsBackToContentDescriptionWhenNodeHasNoText() {
        val icon = UiNode(contentDescription = "Search", className = "android.widget.ImageView", clickable = true)
        val tree = row(icon)
        assertSame(icon, UiSelectorResolver.resolve(UiSelector(text = "search"), tree)?.node)
        assertSame(icon, UiSelectorResolver.resolve(UiSelector(textContains = "ear"), tree)?.node)
    }

    @Test
    fun textPrefersVisibleTextOverContentDescription() {
        // When a node has both, the explicit contentDescription criterion still restricts to the desc.
        val node = UiNode(text = "Profile", contentDescription = "Open profile menu")
        val tree = row(node)
        assertSame(node, UiSelectorResolver.resolve(UiSelector(text = "Profile"), tree)?.node)
        assertNull(UiSelectorResolver.resolve(UiSelector(text = "Open"), tree))
        assertSame(node, UiSelectorResolver.resolve(UiSelector(contentDescription = "Open"), tree)?.node)
    }

    // ---- className ----

    @Test
    fun classNameExactAndPartial() {
        val tree = row(button(text = "OK"))
        assertNotNull(UiSelectorResolver.resolve(UiSelector(className = "android.widget.Button"), tree))
        assertNotNull(UiSelectorResolver.resolve(UiSelector(className = "Button"), tree))
        assertNull(UiSelectorResolver.resolve(UiSelector(className = "android.widget.TextView"), tree))
    }

    // ---- index disambiguation ----

    @Test
    fun indexSelectsNthRankedMatchOneBased() {
        val first = label("OK")
        val second = label("OK")
        val third = label("OK")
        val tree = row(first, second, third)
        assertEquals(3, UiSelectorResolver.resolveAll(UiSelector(text = "OK"), tree).size)
        assertSame(first, UiSelectorResolver.resolve(UiSelector(text = "OK", index = 1), tree)?.node)
        assertSame(third, UiSelectorResolver.resolve(UiSelector(text = "OK", index = 3), tree)?.node)
    }

    @Test
    fun indexOutOfRangeIsNoMatch() {
        val tree = row(label("OK"))
        assertNull(UiSelectorResolver.resolve(UiSelector(text = "OK", index = 2), tree))
    }

    // ---- multiple matches ranking ----

    @Test
    fun moreSpecificNodeOutranksWeakerMatch() {
        val weak = label("Submit")
        val strong = button(text = "Submit", id = "submit_button")
        val tree = row(weak, strong)
        val ranked = UiSelectorResolver.resolveAll(
            UiSelector(text = "Submit", resourceId = "submit_button"), tree
        )
        // Only `strong` satisfies both criteria, so it is the single, more specific match.
        assertEquals(1, ranked.size)
        assertEquals("submit_button", ranked.first().node.resourceId)
        assertTrue(ranked.first().score > 0)

        // With only a text criterion both match; the clickable one is preferred.
        val both = UiSelectorResolver.resolveAll(UiSelector(text = "Submit"), tree)
        assertEquals(2, both.size)
        assertSame(strong, both.first().node)
    }

    @Test
    fun scoreSumsCriteriaAndExplainsReasons() {
        val tree = row(button(text = "OK", id = "ok_button", desc = "Confirm"))
        val match = UiSelectorResolver.resolve(
            UiSelector(text = "OK", resourceId = "ok_button", contentDescription = "Confirm"), tree
        )
        assertNotNull(match)
        assertEquals(3, match!!.reasons.size)
        assertEquals(50 + 45 + 35, match.score)
    }

    // ---- clickable ancestor ----

    @Test
    fun nonClickableMatchWalksUpToNearestClickableAncestor() {
        val clickableRow = UiNode(
            resourceId = "row",
            clickable = true,
            children = listOf(label("Send")),
        )
        val tree = row(clickableRow)
        val match = UiSelectorResolver.resolve(UiSelector(text = "Send"), tree)
        assertNotNull(match)
        assertFalse(match!!.node.clickable)
        val target = UiSelectorResolver.bestClickable(match)
        assertEquals("row", target?.resourceId)
    }

    @Test
    fun nearestClickableAncestorWinsOverHigherOnes() {
        val outer = UiNode(resourceId = "outer", clickable = true, children = listOf(
            UiNode(resourceId = "inner", clickable = true, children = listOf(label("Tap"))),
        ))
        val tree = row(outer)
        val match = UiSelectorResolver.resolve(UiSelector(text = "Tap"), tree)!!
        assertEquals("inner", UiSelectorResolver.bestClickable(match)?.resourceId)
    }

    @Test
    fun clickableNodeIsItsOwnTarget() {
        val tree = row(button(text = "Go"))
        val match = UiSelectorResolver.resolve(UiSelector(text = "Go"), tree)!!
        assertSame(match.node, UiSelectorResolver.bestClickable(match))
    }

    @Test
    fun noClickableAncestorYieldsNull() {
        val tree = row(label("Plain"))
        val match = UiSelectorResolver.resolve(UiSelector(text = "Plain"), tree)!!
        assertNull(UiSelectorResolver.bestClickable(match))
    }

    // ---- no match / empty selector ----

    @Test
    fun noMatchReturnsNull() {
        val tree = row(label("Save"))
        assertNull(UiSelectorResolver.resolve(UiSelector(text = "Delete"), tree))
        assertTrue(UiSelectorResolver.resolveAll(UiSelector(text = "Delete"), tree).isEmpty())
    }

    @Test
    fun emptySelectorAndNullTreeMatchNothing() {
        val tree = row(label("Save"))
        assertTrue(UiSelector(text = "  ", textContains = null).isEmpty)
        assertNull(UiSelectorResolver.resolve(UiSelector(), tree))
        assertNull(UiSelectorResolver.resolve(UiSelector(text = "Save"), null))
        assertNull(UiSelectorResolver.resolve(UiSelector(text = "Save", index = 1), null))
    }

    @Test
    fun blankFieldsAreIgnored() {
        val tree = row(label("Save"))
        // A blank text field must not turn the selector into an impossible match.
        val match = UiSelectorResolver.resolve(UiSelector(text = "  ", resourceId = "nope"), tree)
        assertNull(match)
    }

    // ---- fixtures ----

    private fun row(vararg children: UiNode): UiNode = UiNode(
        resourceId = "root",
        className = "android.widget.FrameLayout",
        clickable = false,
        children = children.toList(),
    )

    private fun label(text: String): UiNode = UiNode(
        text = text,
        className = "android.widget.TextView",
        bounds = UiBounds(0, 0, 100, 20),
    )

    private fun button(text: String? = null, id: String? = null, desc: String? = null): UiNode = UiNode(
        text = text,
        contentDescription = desc,
        resourceId = id,
        className = "android.widget.Button",
        clickable = true,
        bounds = UiBounds(0, 20, 100, 40),
    )
}
