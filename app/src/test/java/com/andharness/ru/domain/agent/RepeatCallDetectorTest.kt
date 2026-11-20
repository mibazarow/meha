package com.andharness.ru.domain.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exact-repeat detection for tool calls (TOOLS_TASKS §3). */
class RepeatCallDetectorTest {

    @Test
    fun theFirstCallIsNeverARepeat() {
        val detector = RepeatCallDetector()
        assertFalse(detector.isExactRepeat("files_read", "{\"path\":\"/a\"}", "содержимое"))
    }

    @Test
    fun theSameCallWithTheSameOutputIsARepeat() {
        val detector = RepeatCallDetector()
        detector.isExactRepeat("files_read", "{\"path\":\"/a\"}", "нет доступа")

        assertTrue(
            "the model already received this exact result and its hint",
            detector.isExactRepeat("files_read", "{\"path\":\"/a\"}", "нет доступа")
        )
    }

    @Test
    fun theSameCallWithAChangedOutputIsProgressNotARepeat() {
        val detector = RepeatCallDetector()
        detector.isExactRepeat("flashlight_set", "{\"on\":true}", "включён")

        assertFalse(
            "a changed result is real progress and must never be suppressed",
            detector.isExactRepeat("flashlight_set", "{\"on\":true}", "уже включён")
        )
    }

    @Test
    fun differentArgumentsAreNeverARepeat() {
        val detector = RepeatCallDetector()
        detector.isExactRepeat("files_read", "{\"path\":\"/a\"}", "текст")

        assertFalse(
            detector.isExactRepeat("files_read", "{\"path\":\"/b\"}", "текст")
        )
    }

    @Test
    fun resetForgetsTheHistory() {
        val detector = RepeatCallDetector()
        detector.isExactRepeat("files_read", "{\"path\":\"/a\"}", "текст")
        detector.reset()

        assertFalse(detector.isExactRepeat("files_read", "{\"path\":\"/a\"}", "текст"))
    }
}
