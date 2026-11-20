package com.andharness.ru.data.communications.speech

import com.andharness.ru.domain.serialization.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Pure (Android-free, JVM-testable) parser for the JSON emitted by whisper.cpp v1.9.4 `whisper-cli`
 * with `-oj`. Field names are confirmed against `output_json()` in
 * `examples/cli/cli.cpp` of revision `927cfce34f31707e17f2bff35c349632fb9e2c3a`:
 * https://github.com/ggml-org/whisper.cpp/blob/927cfce34f31707e17f2bff35c349632fb9e2c3a/examples/cli/cli.cpp
 *
 * Shape (abridged):
 * ```
 * {
 *   "result": { "language": "en" },
 *   "transcription": [
 *     {
 *       "timestamps": { "from": "00:00:00,000", "to": "00:00:02,340" },
 *       "offsets":    { "from": 0, "to": 2340 },
 *       "text": " Hello world"
 *     }
 *   ]
 * }
 * ```
 * `offsets.from`/`offsets.to` are integer **milliseconds** (`t0 * 10`). `timestamps.from`/`.to` are
 * formatted clock strings (`to_timestamp(t, true)` in `examples/common-whisper.cpp`).
 *
 * Tolerant by design:
 * - prefers `offsets` (already ms);
 * - falls back to `timestamps`, accepting either a formatted string (`"HH:MM:SS,mmm"` /
 *   `"HH:MM:SS.mmm"`) or a bare JSON number (whisper.cpp's internal 10 ms ticks → multiplied by 10);
 * - skips blank/whitespace-only text and trims the rest;
 * - preserves transcription order;
 * - never throws: malformed/empty input yields an empty list.
 */
object WhisperJsonParser {

    /** Lenient enough to accept minor whitespace/format drift without accepting garbage silently. */
    private val lenient = AppJson.LenientIsLenient

    private val CLOCK = Regex("""(\d+):(\d{2}):(\d{2})[.,](\d{1,3})""")

    /** Parses [json] into ordered [WhisperSegment]s; returns an empty list on any malformed input. */
    fun parse(json: String): List<WhisperSegment> = try {
        val root = lenient.parseToJsonElement(json) as? JsonObject
        val transcription = root?.get("transcription") as? JsonArray
        transcription?.mapNotNull { segmentOf(it as? JsonObject) } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun segmentOf(obj: JsonObject?): WhisperSegment? {
        if (obj == null) return null
        val text = (obj["text"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (text.isNullOrBlank()) return null
        val (startMs, endMs) = offsetsOf(obj) ?: return null
        return WhisperSegment(startMs = startMs, endMs = endMs, text = text)
    }

    /** `offsets` (ms) take precedence; otherwise `timestamps` are converted to ms. */
    private fun offsetsOf(obj: JsonObject): Pair<Long, Long>? {
        (obj["offsets"] as? JsonObject)?.let { offsets ->
            val from = (offsets["from"] as? JsonPrimitive)?.longOrNull
            val to = (offsets["to"] as? JsonPrimitive)?.longOrNull
            if (from != null && to != null) return from to to
        }
        (obj["timestamps"] as? JsonObject)?.let { timestamps ->
            val from = timestamps["from"].timestampMs()
            val to = timestamps["to"].timestampMs()
            if (from != null && to != null) return from to to
        }
        return null
    }

    /**
     * Interprets a `timestamps` value as milliseconds: a string is parsed as a `HH:MM:SS,mmm` clock
     * value, a bare number is whisper.cpp's internal 10 ms tick (`× 10`).
     *
     * The [JsonPrimitive.isString] check matters: kotlinx.serialization's `longOrNull` is lenient and
     * would silently read `"00:01:02,500"` as `0` (it parses the leading `"00"`). Strings must
     * therefore be routed to [parseClock] first.
     */
    private fun JsonElement?.timestampMs(): Long? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive.isString) return primitive.contentOrNull?.let(::parseClock)
        return primitive.longOrNull?.let { it * 10 }
    }

    private fun parseClock(value: String): Long? {
        val match = CLOCK.matchEntire(value.trim()) ?: return null
        val hours = match.groupValues[1].toLong()
        val minutes = match.groupValues[2].toLong()
        val seconds = match.groupValues[3].toLong()
        val millis = match.groupValues[4].padEnd(3, '0').take(3).toLong()
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
    }
}
