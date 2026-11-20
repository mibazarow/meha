package com.andharness.ru.data.communications.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure [WhisperJsonParser]. The fixtures mirror the real output of whisper.cpp
 * v1.9.4 `whisper-cli -oj` as produced by `output_json()` in `examples/cli/cli.cpp` (revision
 * 927cfce34f31707e17f2bff35c349632fb9e2c3a): `offsets` are integer milliseconds, `timestamps` are
 * `"HH:MM:SS,mmm"` strings.
 */
class WhisperJsonParserTest {

    @Test
    fun parsesOffsetsAsMillisecondsAndPreservesOrder() {
        val segments = WhisperJsonParser.parse(REALISTIC_JSON)

        assertEquals(2, segments.size)
        assertEquals(
            WhisperSegment(startMs = 0L, endMs = 2340L, text = "Привет, мир."),
            segments[0],
        )
        assertEquals(
            WhisperSegment(startMs = 2340L, endMs = 4560L, text = "Это тест."),
            segments[1],
        )
    }

    @Test
    fun offsetsWinOverTimestampsWhenBothPresent() {
        // offsets say 0..100 ms, timestamps say 9 s: the parser must trust offsets.
        val segments = WhisperJsonParser.parse(PREFERENCE_JSON)

        assertEquals(1, segments.size)
        assertEquals(0L, segments[0].startMs)
        assertEquals(100L, segments[0].endMs)
    }

    @Test
    fun numericTimestampsFallBackAsTenMillisecondTicks() {
        val segments = WhisperJsonParser.parse(NUMERIC_TIMESTAMP_JSON)

        assertEquals(2, segments.size)
        assertEquals(WhisperSegment(1000L, 2500L, "Hello"), segments[0])
        assertEquals(WhisperSegment(2500L, 4300L, "world"), segments[1])
    }

    @Test
    fun stringTimestampsFallBackWhenOffsetsAbsent() {
        val segments = WhisperJsonParser.parse(STRING_TIMESTAMP_JSON)

        assertEquals(1, segments.size)
        assertEquals(62_500L, segments[0].startMs)
        assertEquals(63_250L, segments[0].endMs)
        assertEquals("Таймкод", segments[0].text)
    }

    @Test
    fun skipsBlankTextAndTrimsWhitespace() {
        val segments = WhisperJsonParser.parse(REALISTIC_JSON)

        // Third entry has whitespace-only text and is dropped; the others are trimmed.
        assertTrue(segments.none { it.text.isBlank() })
        assertEquals("Привет, мир.", segments[0].text)
        assertEquals("Это тест.", segments[1].text)
    }

    @Test
    fun malformedOrEmptyInputYieldsEmptyList() {
        for (bad in listOf("", "   ", "not json", "{", "{\"transcription\": 42}", "[]")) {
            assertEquals("input=<$bad>", emptyList<WhisperSegment>(), WhisperJsonParser.parse(bad))
        }
    }

    private companion object {
        /** Realistic v1.9.4 document: offsets (ms) + formatted timestamps, one blank segment. */
        val REALISTIC_JSON = """
            {
                "systeminfo": "AVX = 0 | NEON = 1 | F16C = 0",
                "model": {
                    "type": "tiny",
                    "multilingual": true,
                    "vocab": 51865
                },
                "params": {
                    "model": "/data/model.bin",
                    "language": "auto",
                    "translate": false
                },
                "result": {
                    "language": "ru"
                },
                "transcription": [
                    {
                        "timestamps": {
                            "from": "00:00:00,000",
                            "to": "00:00:02,340"
                        },
                        "offsets": {
                            "from": 0,
                            "to": 2340
                        },
                        "text": " Привет, мир."
                    },
                    {
                        "timestamps": {
                            "from": "00:00:02,340",
                            "to": "00:00:04,560"
                        },
                        "offsets": {
                            "from": 2340,
                            "to": 4560
                        },
                        "text": "  Это тест.  "
                    },
                    {
                        "timestamps": {
                            "from": "00:00:05,000",
                            "to": "00:00:06,000"
                        },
                        "offsets": {
                            "from": 5000,
                            "to": 6000
                        },
                        "text": "   "
                    }
                ]
            }
        """.trimIndent()

        /** offsets deliberately contradict timestamps to prove precedence. */
        val PREFERENCE_JSON = """
            {
                "transcription": [
                    {
                        "timestamps": { "from": "00:00:09,000", "to": "00:00:09,100" },
                        "offsets": { "from": 0, "to": 100 },
                        "text": "pref"
                    }
                ]
            }
        """.trimIndent()

        /** No offsets: numeric `timestamps` are 10 ms ticks and must be multiplied by 10. */
        val NUMERIC_TIMESTAMP_JSON = """
            {
                "result": { "language": "en" },
                "transcription": [
                    {
                        "timestamps": { "from": 100, "to": 250 },
                        "text": " Hello"
                    },
                    {
                        "timestamps": { "from": 250, "to": 430 },
                        "text": "world "
                    }
                ]
            }
        """.trimIndent()

        /** No offsets: real v1.9.4 clock-string timestamps must be parsed to milliseconds. */
        val STRING_TIMESTAMP_JSON = """
            {
                "transcription": [
                    {
                        "timestamps": { "from": "00:01:02,500", "to": "00:01:03,250" },
                        "text": " Таймкод"
                    }
                ]
            }
        """.trimIndent()
    }
}
