package com.andharness.ru.domain.skill

import com.andharness.ru.domain.ui.UiBounds
import com.andharness.ru.domain.ui.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure recorder conversion (M2 slice 3): recorded `ui_*` actions become semantic
 * [SkillStep]s, a coordinate-only action without a captured node is skipped honestly (never recorded
 * as a brittle coordinate step), and the cap is enforced.
 */
class SkillRecordingConverterTest {

    private fun action(tool: String, args: String, node: RecordedNode? = null) =
        RecordedSkillAction(tool, args, node)

    @Test
    fun tapWithRecordedTextSelectorBecomesSubstringTap() {
        val result = SkillRecordingConverter.convert(listOf(action("ui_tap", "{\"text\":\"Оплатить\"}")))
        assertEquals(1, result.steps.size)
        val tap = result.steps.single() as SkillStep.Tap
        assertEquals("Оплатить", tap.selector.textContains)
        assertTrue(result.converted.single().contains("тап"))
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun tapWithExactFlagBecomesExactTextTap() {
        val tap = SkillRecordingConverter
            .convert(listOf(action("ui_tap", "{\"text\":\"Оплатить\",\"exact\":\"true\"}")))
            .steps.single() as SkillStep.Tap
        assertEquals("Оплатить", tap.selector.text)
        assertNull(tap.selector.textContains)
    }

    @Test
    fun tapWithRecordedResourceIdWins() {
        val tap = SkillRecordingConverter
            .convert(listOf(action("ui_tap", "{\"id\":\"com.app:id/pay\"}")))
            .steps.single() as SkillStep.Tap
        assertEquals("com.app:id/pay", tap.selector.resourceId)
    }

    @Test
    fun coordinateTapWithCapturedNodeBecomesSemanticTap() {
        val node = RecordedNode(text = "Оплатить", resourceId = "pay", className = "android.widget.Button")
        val result = SkillRecordingConverter.convert(listOf(action("ui_tap", "{\"x\":\"100\",\"y\":\"200\"}", node)))
        val tap = result.steps.single() as SkillStep.Tap
        assertEquals("pay", tap.selector.resourceId)
        assertNull(tap.selector.text)
        assertNull(tap.selector.contentDescription)
    }

    @Test
    fun coordinateTapWithoutCapturedNodeIsSkippedHonestly() {
        val result = SkillRecordingConverter.convert(listOf(action("ui_tap", "{\"x\":\"100\",\"y\":\"200\"}")))
        assertTrue(result.steps.isEmpty())
        val note = result.skipped.single()
        assertTrue(note, note.contains("координаты 100,200"))
        assertTrue(note, note.contains("семантического селектора нет"))
        assertTrue(note, note.contains("не записывать действие по координатам"))
    }

    @Test
    fun contentDescriptionFallbackWhenNoResourceId() {
        val node = RecordedNode(contentDescription = "Закрыть")
        val tap = SkillRecordingConverter
            .convert(listOf(action("ui_tap", "{\"x\":\"1\",\"y\":\"2\"}", node)))
            .steps.single() as SkillStep.Tap
        assertEquals("Закрыть", tap.selector.contentDescription)
        assertNull(tap.selector.resourceId)
    }

    @Test
    fun typeUsesSelectorTextAndPayload() {
        val result = SkillRecordingConverter.convert(
            listOf(action("ui_type", "{\"selectorText\":\"Поиск\",\"text\":\"Москва\"}"))
        )
        val type = result.steps.single() as SkillStep.Type
        assertEquals("Поиск", type.selector.textContains)
        assertEquals("Москва", type.textTemplate)
    }

    @Test
    fun typeFallsBackToFocusedNodeSemantics() {
        val node = RecordedNode(contentDescription = "Поле поиска")
        val type = SkillRecordingConverter
            .convert(listOf(action("ui_type", "{\"text\":\"Москва\"}", node)))
            .steps.single() as SkillStep.Type
        assertEquals("Поле поиска", type.selector.contentDescription)
        assertEquals("Москва", type.textTemplate)
    }

    @Test
    fun keyEventSupportedKeyBecomesKeyStepAndUnsupportedIsSkipped() {
        val back = SkillRecordingConverter.convert(listOf(action("key_event", "{\"key\":\"BACK\"}")))
        assertEquals(SkillStep.Key("BACK"), back.steps.single())

        val enter = SkillRecordingConverter.convert(listOf(action("key_event", "{\"key\":\"enter\"}")))
        assertTrue(enter.steps.isEmpty())
        assertTrue(enter.skipped.single().contains("не поддерживается"))
    }

    @Test
    fun scrollWithCapturedScrollableBecomesScrollAndWithoutIsSkipped() {
        val node = RecordedNode(text = "Лента")
        val withNode = SkillRecordingConverter
            .convert(listOf(action("ui_scroll", "{\"direction\":\"down\"}", node)))
        val scroll = withNode.steps.single() as SkillStep.Scroll
        assertEquals(SkillScrollDirection.DOWN, scroll.direction)
        assertEquals("Лента", scroll.selector.text)

        val without = SkillRecordingConverter.convert(listOf(action("ui_scroll", "{\"direction\":\"down\"}")))
        assertTrue(without.steps.isEmpty())
        assertTrue(without.skipped.single().contains("контейнер не опознан"))
    }

    @Test
    fun swipeLongPressAndMultitouchAreSkipped() {
        val result = SkillRecordingConverter.convert(
            listOf(
                action("ui_swipe", "{\"x1\":\"1\",\"y1\":\"1\",\"x2\":\"2\",\"y2\":\"2\"}"),
                action("ui_long_press", "{\"x\":\"1\",\"y\":\"2\"}"),
                action("ui_gesture_multi", "{\"cx\":\"1\",\"cy\":\"2\"}"),
            )
        )
        assertTrue(result.steps.isEmpty())
        assertEquals(3, result.skipped.size)
        assertTrue(result.skipped.all { it.contains("не поддерживается") })
    }

    @Test
    fun malformedRecordedArgumentsLeadToAnHonestSkip() {
        val result = SkillRecordingConverter.convert(listOf(action("ui_tap", "{not json")))
        assertTrue(result.steps.isEmpty())
        assertTrue(result.skipped.single().contains("некорректные аргументы"))
    }

    @Test
    fun stepsAreCappedAndOverflowIsReported() {
        val actions = (1..SkillCodec.MAX_STEPS + 3).map {
            action("ui_tap", "{\"text\":\"Кнопка $it\"}")
        }
        val result = SkillRecordingConverter.convert(actions)
        assertEquals(SkillCodec.MAX_STEPS, result.steps.size)
        assertEquals(3, result.skipped.size)
        assertTrue(result.skipped.all { it.contains("лимит ${SkillCodec.MAX_STEPS}") })
    }

    @Test
    fun withLaunchStepPrependsLaunchForKnownPackage() {
        val steps = listOf(SkillStep.Tap(SkillSelector(resourceId = "calc_keypad_btn_clear")))
        val withLaunch = SkillRecordingConverter.withLaunchStep(steps, "com.sec.android.app.popupcalculator")

        assertEquals(2, withLaunch.size)
        assertEquals(SkillStep.LaunchApp("com.sec.android.app.popupcalculator"), withLaunch.first())
        assertEquals(steps, withLaunch.drop(1))
    }

    @Test
    fun withLaunchStepDoesNotDuplicateAnExistingLeadingLaunch() {
        val steps = listOf(
            SkillStep.LaunchApp("com.taxi.app"),
            SkillStep.Tap(SkillSelector(text = "Куда")),
        )
        assertEquals(steps, SkillRecordingConverter.withLaunchStep(steps, "com.taxi.app"))
    }

    @Test
    fun withLaunchStepIsANoOpForBlankOrMissingPackageAndEmptySteps() {
        val steps = listOf(SkillStep.Key("BACK"))
        assertEquals(steps, SkillRecordingConverter.withLaunchStep(steps, null))
        assertEquals(steps, SkillRecordingConverter.withLaunchStep(steps, "   "))
        assertTrue(SkillRecordingConverter.withLaunchStep(emptyList(), "com.app").isEmpty())
    }

    @Test
    fun nodeHitTestPrefersDeepestSmallestNode() {        val deep = UiNode(
            contentDescription = "Кнопка",
            resourceId = "btn",
            bounds = UiBounds(100, 100, 300, 300),
        )
        val container = UiNode(bounds = UiBounds(0, 0, 1000, 1000), children = listOf(deep))
        val root = UiNode(bounds = UiBounds(0, 0, 1000, 1000), children = listOf(container))

        assertEquals("btn", NodeHitTest.findAt(root, 200, 200)?.resourceId)
        assertEquals(container, NodeHitTest.findAt(root, 700, 700))
        assertNull(NodeHitTest.findAt(root, 5000, 5000))
        assertNull(NodeHitTest.findAt(null, 10, 10))
    }
}
