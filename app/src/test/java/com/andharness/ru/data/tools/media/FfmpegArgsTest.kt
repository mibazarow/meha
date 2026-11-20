package com.andharness.ru.data.tools.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure parts of the audio/video editing pipeline: time parsing, audio-format
 * mapping and the ffmpeg argument vectors. The actual ffmpeg encode is device-only (native libs)
 * and is reported separately; these tests pin the deterministic logic that feeds it.
 */
class FfmpegArgsTest {

    // ---- MediaTime ----

    @Test
    fun parseSecondsAcceptsPlainSeconds() {
        assertEquals(90.0, MediaTime.parseSeconds("90")!!, 0.0001)
        assertEquals(90.5, MediaTime.parseSeconds("90.5")!!, 0.0001)
    }

    @Test
    fun parseSecondsAcceptsClockForms() {
        assertEquals(90.0, MediaTime.parseSeconds("1:30")!!, 0.0001)
        assertEquals(90.0, MediaTime.parseSeconds("0:01:30")!!, 0.0001)
        assertEquals(3723.0, MediaTime.parseSeconds("1:02:03")!!, 0.0001)
        assertEquals(90.5, MediaTime.parseSeconds("0:01:30.5")!!, 0.0001)
    }

    @Test
    fun parseSecondsRejectsMalformedInput() {
        assertNull(MediaTime.parseSeconds(null))
        assertNull(MediaTime.parseSeconds(""))
        assertNull(MediaTime.parseSeconds("abc"))
        assertNull(MediaTime.parseSeconds("-5"))
        assertNull(MediaTime.parseSeconds("1:2:3:4"))
        assertNull(MediaTime.parseSeconds("1.5:30"))
    }

    @Test
    fun formatUsesDotDecimal() {
        assertEquals("90.000", MediaTime.format(90.0))
    }

    // ---- AudioFormats ----

    @Test
    fun audioEncodersAreMappedByExtension() {
        assertEquals("libmp3lame", AudioFormats.encoderFor("mp3"))
        assertEquals("aac", AudioFormats.encoderFor("M4A"))
        assertEquals("flac", AudioFormats.encoderFor("flac"))
        assertEquals("pcm_s16le", AudioFormats.encoderFor("wav"))
        assertNull(AudioFormats.encoderFor("xyz"))
        assertNull(AudioFormats.encoderFor(null))
    }

    // ---- FfmpegArgs ----

    @Test
    fun trimWithCopyKeepsTracks() {
        val args = FfmpegArgs.trim(
            src = "/in.m4a", dest = "/out.m4a",
            startSeconds = 10.0, durationSeconds = 5.0,
            reencode = false, audio = true, audioEncoder = null,
        )
        assertEquals(
            listOf("-nostdin", "-y", "-ss", "10.000", "-i", "/in.m4a", "-t", "5.000", "-c", "copy", "/out.m4a"),
            args,
        )
    }

    @Test
    fun trimWithReencodeUsesAudioEncoder() {
        val args = FfmpegArgs.trim(
            src = "/in.wav", dest = "/out.mp3",
            startSeconds = 0.0, durationSeconds = 3.0,
            reencode = true, audio = true, audioEncoder = "libmp3lame",
        )
        assertTrue(args.contains("-c:a") && args.contains("libmp3lame"))
        assertTrue(!args.contains("copy"))
    }

    @Test
    fun concatUsesConcatDemuxer() {
        val args = FfmpegArgs.concat("/tmp/list.txt", "/out.m4a", reencode = false, audio = true, audioEncoder = null)
        assertEquals(
            listOf("-nostdin", "-y", "-f", "concat", "-safe", "0", "-i", "/tmp/list.txt", "-c", "copy", "/out.m4a"),
            args,
        )
    }

    @Test
    fun extractAudioCopiesOrReencodes() {
        val copy = FfmpegArgs.extractAudio("/in.mp4", "/out.m4a", reencode = false, audioEncoder = null, null, null)
        assertTrue(copy.containsAll(listOf("-vn", "-c:a", "copy")))
        val enc = FfmpegArgs.extractAudio("/in.mp4", "/out.mp3", reencode = true, audioEncoder = "libmp3lame", "192", "44100")
        assertTrue(enc.containsAll(listOf("-c:a", "libmp3lame", "-b:a", "192k", "-ar", "44100")))
    }

    @Test
    fun tagWriteEmitsMetadataPairs() {
        val args = FfmpegArgs.tagWrite("/in.mp3", "/out.mp3", listOf("title" to "Song", "artist" to "Band"))
        assertTrue(args.contains("-map") && args.contains("0") && args.contains("-c") && args.contains("copy"))
        assertEquals(2, args.count { it == "-metadata" })
        assertTrue(args.contains("title=Song"))
        assertTrue(args.contains("artist=Band"))
    }

    @Test
    fun scaleFilterHandlesEachVariant() {
        assertEquals("scale=1080:1080:force_original_aspect_ratio=decrease:force_divisible_by=2", FfmpegArgs.scaleFilter(null, null, 1080))
        assertEquals("scale=1280:-2", FfmpegArgs.scaleFilter(1280, null, null))
        assertEquals("scale=-2:720", FfmpegArgs.scaleFilter(null, 720, null))
        assertEquals("scale=640:480", FfmpegArgs.scaleFilter(640, 480, null))
    }

    @Test
    fun speedFilterChainsAtempoBeyondItsRange() {
        assertEquals("atempo=1", FfmpegArgs.speedFilter(1.0))
        assertEquals("atempo=2,atempo=1.5", FfmpegArgs.speedFilter(3.0))
        assertEquals("atempo=0.5,atempo=0.5", FfmpegArgs.speedFilter(0.25))
        assertEquals("atempo=1.5", FfmpegArgs.speedFilter(1.5))
    }

    @Test
    fun rotateFilterMapsAngles() {
        assertEquals("transpose=1", FfmpegArgs.rotateFilter(90))
        assertEquals("hflip,vflip", FfmpegArgs.rotateFilter(180))
        assertEquals("transpose=2", FfmpegArgs.rotateFilter(270))
        assertThrows(IllegalArgumentException::class.java) { FfmpegArgs.rotateFilter(45) }
    }

    @Test
    fun subtitleCodecDependsOnContainer() {
        assertEquals("mov_text", FfmpegArgs.subtitleCodecFor("mp4"))
        assertEquals("srt", FfmpegArgs.subtitleCodecFor("mkv"))
    }

    @Test
    fun escapeFilterPathEscapesSpecialCharacters() {
        assertEquals("/a b/x.srt", FfmpegArgs.escapeFilterPath("/a b/x.srt"))
        assertEquals("a\\:b\\,c", FfmpegArgs.escapeFilterPath("a:b,c"))
    }

    @Test
    fun remuxCopiesOrReencodesVideo() {
        val copy = FfmpegArgs.remux("/in.mp4", "/out.mkv", reencode = false, audio = false, audioEncoder = null)
        assertEquals(listOf("-nostdin", "-y", "-i", "/in.mp4", "-c", "copy", "/out.mkv"), copy)
        val enc = FfmpegArgs.remux("/in.mp4", "/out.mp4", reencode = true, audio = false, audioEncoder = null)
        assertTrue(enc.containsAll(listOf("-c:v", "libx264", "-c:a", "aac")))
    }
}
