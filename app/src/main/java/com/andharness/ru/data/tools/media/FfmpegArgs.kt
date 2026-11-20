package com.andharness.ru.data.tools.media

import java.util.Locale

/**
 * Time parsing/formatting shared by the audio/video editing tools. Accepts the forms the user is
 * likely to type (`90`, `90.5`, `1:30`, `0:01:30`, `1:02:03.25`) and normalises them to seconds so
 * the ffmpeg arguments are deterministic.
 *
 * Pure Kotlin, no Android imports, so it is directly unit-testable on the JVM.
 */
internal object MediaTime {

    /**
     * Parses [raw] into seconds, or returns `null` when it is empty or malformed. Supports
     * `S`, `S.ms`, `MM:SS[.ms]` and `HH:MM:SS[.ms]`. A fractional part is allowed only in the last
     * component; all components must be non-negative.
     */
    fun parseSeconds(raw: String?): Double? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parts = text.split(":")
        if (parts.size > 3) return null
        var total = 0.0
        parts.forEachIndexed { index, part ->
            val isLast = index == parts.size - 1
            if (!isLast && part.contains('.')) return null
            val value = part.toDoubleOrNull() ?: return null
            if (value < 0.0) return null
            total = total * 60.0 + value
        }
        return total
    }

    /** Formats [seconds] as a plain `%.3f` string (US decimal point, for ffmpeg arguments). */
    fun format(seconds: Double): String = String.format(Locale.US, "%.3f", seconds)

    /** Formats [value] with up to 5 decimals, stripping trailing zeros (for filter expressions). */
    fun formatCompact(value: Double): String {
        val formatted = String.format(Locale.US, "%.5f", value)
        return if (formatted.contains('.')) formatted.trimEnd('0').trimEnd('.') else formatted
    }
}

/**
 * Audio codec mapping used by `audio_convert`, and by the re-encoding paths of the other audio
 * tools. Only formats the bundled ffmpeg-kit build can actually encode are listed; anything else is
 * an honest refusal rather than a silent failure.
 */
internal object AudioFormats {

    /** Destination extension -> ffmpeg audio encoder name. */
    private val ENCODERS: Map<String, String> = mapOf(
        "mp3" to "libmp3lame",
        "wav" to "pcm_s16le",
        "ogg" to "libvorbis",
        "oga" to "libvorbis",
        "opus" to "libopus",
        "m4a" to "aac",
        "mp4" to "aac",
        "aac" to "aac",
        "flac" to "flac",
        "wma" to "wmav2",
    )

    /** Supported destination extensions, in a stable order for the error message. */
    val supportedExtensions: List<String> = ENCODERS.keys.sorted()

    /** The ffmpeg encoder for an extension, or `null` when it is not supported. */
    fun encoderFor(extension: String?): String? = ENCODERS[extension?.trim()?.lowercase()]
}

/**
 * Pure builders for the ffmpeg argument vectors used by the audio/video editing tools. Kept
 * free of file IO and Android APIs so every vector is unit-testable.
 *
 * Every vector starts with `-nostdin` (never read from stdin) and `-y` (overwrite): the handlers
 * already refuse an existing destination, so `-y` is only a safety net and `-nostdin` prevents the
 * ffmpeg process from ever blocking on terminal input.
 */
internal object FfmpegArgs {

    /** Video re-encode settings shared by the re-encoding video operations. */
    private val VIDEO_ENCODE = listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "23")

    private fun List<String>.withDefaultAudio(): List<String> = this + listOf("-c:a", "aac")

    /**
     * `-ss <start> -i src -t <duration>` (+ codec) + dest. Both `end` and `duration` are absolute
     * seconds; the caller resolves `end` into a duration so input seeking (`-ss` before `-i`) stays
     * fast and correct.
     */
    fun trim(
        src: String,
        dest: String,
        startSeconds: Double,
        durationSeconds: Double,
        reencode: Boolean,
        audio: Boolean,
        audioEncoder: String?,
    ): List<String> {
        val base = mutableListOf("-nostdin", "-y", "-ss", MediaTime.format(startSeconds), "-i", src)
        base += listOf("-t", MediaTime.format(durationSeconds))
        base += codecArgs(reencode, audio, audioEncoder)
        base += dest
        return base
    }

    /** `-f concat -safe 0 -i listFile` (+ codec) + dest. */
    fun concat(
        listFile: String,
        dest: String,
        reencode: Boolean,
        audio: Boolean,
        audioEncoder: String?,
    ): List<String> {
        val base = mutableListOf(
            "-nostdin", "-y", "-f", "concat", "-safe", "0", "-i", listFile,
        )
        base += codecArgs(reencode, audio, audioEncoder)
        base += dest
        return base
    }

    /** `-i src -vn` (+ `-c:a <encoder>|-c:a copy`) + optional bitrate/sample-rate + dest. */
    fun extractAudio(
        src: String,
        dest: String,
        reencode: Boolean,
        audioEncoder: String?,
        bitrateKbps: String?,
        sampleRate: String?,
    ): List<String> {
        val base = mutableListOf("-nostdin", "-y", "-i", src, "-vn")
        base += if (reencode && audioEncoder != null) listOf("-c:a", audioEncoder) else listOf("-c:a", "copy")
        bitrateKbps?.takeIf { it.isNotBlank() }?.let { base += listOf("-b:a", "${it}k") }
        sampleRate?.takeIf { it.isNotBlank() }?.let { base += listOf("-ar", it) }
        base += dest
        return base
    }

    /** `-i src -af <filter>` + encoder + dest (audio filters require a re-encode). */
    fun audioFilter(src: String, dest: String, filter: String, audioEncoder: String): List<String> =
        listOf("-nostdin", "-y", "-i", src, "-af", filter, "-c:a", audioEncoder, dest)

    /** `-i src -map 0 -c copy -metadata key=value…` + dest. */
    fun tagWrite(src: String, dest: String, tags: List<Pair<String, String>>): List<String> {
        val base = mutableListOf("-nostdin", "-y", "-i", src, "-map", "0", "-c", "copy")
        tags.forEach { (key, value) -> base += listOf("-metadata", "$key=$value") }
        base += dest
        return base
    }

    /** `-i src -vf scale=…` + H.264/AAC re-encode + dest. */
    fun resize(src: String, dest: String, scaleFilter: String): List<String> =
        listOf("-nostdin", "-y", "-i", src, "-vf", scaleFilter) + VIDEO_ENCODE.withDefaultAudio() + dest

    /** `-i src -c:v libx264 -b:v Nk [-c:a aac -b:a Mk]` + dest. */
    fun bitrate(src: String, dest: String, videoKbps: String, audioKbps: String?): List<String> {
        val base = mutableListOf("-nostdin", "-y", "-i", src)
        base += listOf("-c:v", "libx264", "-preset", "veryfast", "-b:v", "${videoKbps}k")
        base += listOf("-c:a", "aac")
        audioKbps?.takeIf { it.isNotBlank() }?.let { base += listOf("-b:a", "${it}k") }
        base += dest
        return base
    }

    /** `-i src [-vf scale=…] -c:v libx264 -crf C -preset P -c:a aac` + dest. */
    fun compress(
        src: String,
        dest: String,
        crf: String,
        preset: String,
        scaleFilter: String?,
    ): List<String> {
        val base = mutableListOf("-nostdin", "-y", "-i", src)
        scaleFilter?.let { base += listOf("-vf", it) }
        base += listOf("-c:v", "libx264", "-crf", crf, "-preset", preset, "-c:a", "aac", "-b:a", "128k")
        base += dest
        return base
    }

    /** `-i src -vf <transpose-filter> -c:v libx264 … -c:a copy` + dest. */
    fun rotate(src: String, dest: String, transposeFilter: String): List<String> =
        listOf("-nostdin", "-y", "-i", src, "-vf", transposeFilter) +
            VIDEO_ENCODE + listOf("-c:a", "copy", dest)

    /** Burn-in: `-i src -vf subtitles=<srt>` + H.264/AAC re-encode + dest. */
    fun subtitlesBurn(src: String, dest: String, srt: String): List<String> =
        listOf("-nostdin", "-y", "-i", src, "-vf", "subtitles=${escapeFilterPath(srt)}") +
            VIDEO_ENCODE.withDefaultAudio() + dest

    /** Mux: `-i src -i srt -c:v copy -c:a copy -c:s <codec>` + dest. */
    fun subtitlesMux(src: String, dest: String, srt: String, subtitleCodec: String): List<String> =
        listOf(
            "-nostdin", "-y", "-i", src, "-i", srt,
            "-c:v", "copy", "-c:a", "copy", "-c:s", subtitleCodec, dest,
        )

    /** `-i src` (+ codec) + dest: change container, copy or re-encode per [reencode]/[audio]. */
    fun remux(
        src: String,
        dest: String,
        reencode: Boolean,
        audio: Boolean,
        audioEncoder: String?,
    ): List<String> =
        listOf("-nostdin", "-y", "-i", src) + codecArgs(reencode, audio, audioEncoder) + dest

    /** Copy or re-encode, depending on [reencode]/[audio]. */
    private fun codecArgs(reencode: Boolean, audio: Boolean, audioEncoder: String?): List<String> = when {
        !reencode -> listOf("-c", "copy")
        audio -> listOf("-c:a", audioEncoder ?: "aac")
        else -> VIDEO_ENCODE.withDefaultAudio()
    }

    /**
     * Escapes a filesystem path for embedding in an ffmpeg filter argument (`subtitles=`): within a
     * filter graph, `\`, `:`, `'`, `,` and `[`/`]` are special, so each is backslash-escaped.
     */
    fun escapeFilterPath(path: String): String =
        path.replace("\\", "/")
            .replace("'", "\\'")
            .replace(":", "\\:")
            .replace(",", "\\,")
            .replace("[", "\\[")
            .replace("]", "\\]")

    /**
     * Builds the `scale=` filter for `video_resize` / `video_compress`: when a single side is given
     * the other follows the aspect ratio (and `-2` keeps it even for H.264); a `maxSide` bound uses
     * `force_original_aspect_ratio=decrease` on a square box.
     */
    fun scaleFilter(width: Int?, height: Int?, maxSide: Int?): String = when {
        maxSide != null ->
            "scale=$maxSide:$maxSide:force_original_aspect_ratio=decrease:force_divisible_by=2"
        width != null && height != null -> "scale=$width:$height"
        width != null -> "scale=$width:-2"
        height != null -> "scale=-2:$height"
        else -> "" // unreachable: the caller validates at least one bound
    }

    /**
     * Builds the `atempo` filter chain for a speed [factor]. `atempo` only accepts 0.5–2.0, so
     * out-of-range factors are decomposed into several stages (e.g. `3` -> `atempo=2,atempo=1.5`).
     */
    fun speedFilter(factor: Double): String {
        require(factor > 0.0) { "factor must be positive" }
        val stages = ArrayList<Double>()
        var remaining = factor
        while (remaining > 2.0) {
            stages.add(2.0)
            remaining /= 2.0
        }
        while (remaining < 0.5) {
            stages.add(0.5)
            remaining /= 0.5
        }
        stages.add(remaining)
        return stages.joinToString(",") { "atempo=${MediaTime.formatCompact(it)}" }
    }

    /** `transpose`/`hflip,vflip` filter for a 90/180/270 degree rotation. */
    fun rotateFilter(rotation: Int): String = when (rotation) {
        90 -> "transpose=1"
        180 -> "hflip,vflip"
        270 -> "transpose=2"
        else -> throw IllegalArgumentException("Угол должен быть 90, 180 или 270 (получено $rotation).")
    }

    /** Subtitle codec for a muxed track: `mov_text` for the MP4 family, `srt` otherwise. */
    fun subtitleCodecFor(extension: String): String = when (extension.lowercase()) {
        "mp4", "m4v", "mov" -> "mov_text"
        else -> "srt"
    }
}
