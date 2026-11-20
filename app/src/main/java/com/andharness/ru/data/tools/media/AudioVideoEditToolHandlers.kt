package com.andharness.ru.data.tools.media

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Implementation of the audio/video editing tools (bug-items 11 and 12). Every operation writes a
 * NEW file, never touches the source, and validates its inputs before invoking ffmpeg (see
 * [FfmpegRunner]); a validation problem is reported as an honest failure, never a fake success.
 *
 * The pure argument building lives in [FfmpegArgs]; this class only does path resolution, argument
 * validation and the post-run verification that the destination exists and is non-empty.
 */
internal class AudioVideoEditOps(private val io: MediaPathIo) {

    // ---- audio ----

    fun trim(arguments: JsonObject, audio: Boolean): ToolResult {
        val src = inputFile(arguments)
        val dest = destFile(arguments)
        val start = arguments.text("start")
            ?.let { MediaTime.parseSeconds(it) ?: throw invalid("start", it) } ?: 0.0
        val duration = trimDuration(arguments, start)
        val reencode = arguments.bool("reencode")
        val encoder = if (audio && reencode) requireAudioEncoder(dest) else null
        val verb = if (audio) "Аудио обрезано" else "Видео обрезано"
        return execute(FfmpegArgs.trim(src.path, dest.path, start, duration, reencode, audio, encoder), dest, verb)
    }

    fun concat(arguments: JsonObject, audio: Boolean): ToolResult {
        val files = inputFiles(arguments)
        val dest = destFile(arguments)
        val reencode = arguments.bool("reencode")
        val encoder = if (audio && reencode) requireAudioEncoder(dest) else null
        val listFile = File(files.first().parentFile, ".meha_concat_${System.nanoTime()}.txt")
        val verb = if (audio) "Аудио склеено" else "Видео склеено"
        return try {
            listFile.writeText(files.joinToString("\n") { "file ${concatQuote(it.absolutePath)}" } + "\n")
            execute(FfmpegArgs.concat(listFile.path, dest.path, reencode, audio, encoder), dest, verb)
        } finally {
            listFile.delete()
        }
    }

    fun convertAudio(arguments: JsonObject): ToolResult {
        val src = inputFile(arguments)
        val dest = destFile(arguments)
        val encoder = arguments.text("codec")
            ?.let { AudioFormats.encoderFor(it) ?: throw unsupportedAudioFormat(it) }
            ?: requireAudioEncoder(dest)
        val bitrate = optionalPositiveIntText(arguments.text("bitrate"), "bitrate")
        val sampleRate = optionalPositiveIntText(arguments.text("sample_rate"), "sample_rate")
        return execute(
            FfmpegArgs.extractAudio(src.path, dest.path, true, encoder, bitrate, sampleRate),
            dest,
            "Аудио сконвертировано",
        )
    }

    fun volume(arguments: JsonObject): ToolResult {
        val src = inputFile(arguments)
        val dest = destFile(arguments)
        val gainRaw = arguments.text("gain_db")
        val percentRaw = arguments.text("percent")
        if (gainRaw == null && percentRaw == null) {
            throw IllegalArgumentException("Укажите gain_db или percent.")
        }
        if (gainRaw != null && percentRaw != null) {
            throw IllegalArgumentException("Укажите ровно один параметр: gain_db или percent.")
        }
        val filter = if (gainRaw != null) {
            val db = gainRaw.toDoubleOrNull() ?: throw invalid("gain_db", gainRaw)
            "volume=${MediaTime.formatCompact(db)}dB"
        } else {
            val percent = percentRaw!!.toDoubleOrNull() ?: throw invalid("percent", percentRaw)
            if (percent <= 0.0) throw IllegalArgumentException("percent должен быть больше нуля.")
            "volume=${MediaTime.formatCompact(percent / 100.0)}"
        }
        val encoder = requireAudioEncoder(dest)
        return execute(FfmpegArgs.audioFilter(src.path, dest.path, filter, encoder), dest, "Громкость изменена")
    }

    fun speed(arguments: JsonObject): ToolResult {
        val src = inputFile(arguments)
        val dest = destFile(arguments)
        val factorRaw = arguments.text("factor") ?: throw IllegalArgumentException("Укажите factor — множитель скорости.")
        val factor = factorRaw.toDoubleOrNull() ?: throw invalid("factor", factorRaw)
        if (factor <= 0.0 || factor > 100.0) {
            throw IllegalArgumentException("factor должен быть положительным (разумно 0.25–10).")
        }
        val encoder = requireAudioEncoder(dest)
        return execute(
            FfmpegArgs.audioFilter(src.path, dest.path, FfmpegArgs.speedFilter(factor), encoder),
            dest,
            "Темп изменён",
        )
    }

    fun extractAudio(arguments: JsonObject): ToolResult {
        val src = inputFile(arguments)
        val dest = destFile(arguments)
        val reencode = arguments.bool("reencode")
        val encoder = if (reencode) requireAudioEncoder(dest) else null
        return execute(
            FfmpegArgs.extractAudio(src.path, dest.path, reencode, encoder, null, null),
            dest,
            "Аудиодорожка извлечена",
        )
    }

    fun tagWrite(arguments: JsonObject): ToolResult {
        val src = inputFile(arguments)
        val dest = destFile(arguments)
        val mapping = listOf(
            "title" to "title", "artist" to "artist", "album" to "album",
            "album_artist" to "album_artist", "genre" to "genre", "track" to "track",
            "date" to "date", "comment" to "comment",
        )
        val tags = mapping.mapNotNull { (argKey, metaKey) -> arguments.text(argKey)?.let { metaKey to it } }
        if (tags.isEmpty()) {
            throw IllegalArgumentException("Укажите хотя бы одно поле метаданных (title, artist, album, …).")
        }
        return execute(FfmpegArgs.tagWrite(src.path, dest.path, tags), dest, "Метаданные записаны")
    }

    // ---- video ----

    fun resize(arguments: JsonObject): ToolResult {
        val src = inputFile(arguments)
        val dest = destFile(arguments)
        val width = optionalPositiveInt(arguments.text("width"), "width")
        val height = optionalPositiveInt(arguments.text("height"), "height")
        val maxSide = optionalPositiveInt(arguments.text("maxSide"), "maxSide")
        if (width == null && height == null && maxSide == null) {
            throw IllegalArgumentException("Укажите width, height или maxSide.")
        }
        val filter = FfmpegArgs.scaleFilter(width, height, maxSide)
        return execute(FfmpegArgs.resize(src.path, dest.path, filter), dest, "Разрешение изменено")
    }

    fun bitrate(arguments: JsonObject): ToolResult {
        val src = inputFile(arguments)
        val dest = destFile(arguments)
        val video = arguments.text("video_bitrate")
            ?: throw IllegalArgumentException("Укажите video_bitrate — битрейт видео в кбит/с.")
        optionalPositiveInt(video, "video_bitrate")
        val audio = optionalPositiveInt(arguments.text("audio_bitrate"), "audio_bitrate")
        return execute(FfmpegArgs.bitrate(src.path, dest.path, video, audio?.toString()), dest, "Битрейт изменён")
    }

    fun compress(arguments: JsonObject): ToolResult {
        val src = inputFile(arguments)
        val dest = destFile(arguments)
        val crf = arguments.text("crf") ?: "28"
        val crfInt = crf.toIntOrNull()?.takeIf { it in 0..51 }
            ?: throw IllegalArgumentException("crf должен быть целым от 0 до 51 (получено «$crf»).")
        val preset = arguments.text("preset") ?: "medium"
        if (preset !in PRESETS) {
            throw IllegalArgumentException("Неизвестный preset «$preset». Допустимо: ${PRESETS.joinToString(", ")}.")
        }
        val maxSide = optionalPositiveInt(arguments.text("maxSide"), "maxSide")
        val scale = maxSide?.let { FfmpegArgs.scaleFilter(null, null, it) }
        return execute(FfmpegArgs.compress(src.path, dest.path, crfInt.toString(), preset, scale), dest, "Видео сжато")
    }

    fun rotate(arguments: JsonObject): ToolResult {
        val src = inputFile(arguments)
        val dest = destFile(arguments)
        val raw = arguments.text("rotation") ?: throw IllegalArgumentException("Укажите rotation: 90, 180 или 270.")
        val rotation = raw.toIntOrNull() ?: throw invalid("rotation", raw)
        val filter = FfmpegArgs.rotateFilter(rotation)
        return execute(FfmpegArgs.rotate(src.path, dest.path, filter), dest, "Видео повёрнуто")
    }

    fun subtitles(arguments: JsonObject): ToolResult {
        val src = inputFile(arguments)
        val dest = destFile(arguments)
        val srtRaw = arguments.text("srt") ?: throw IllegalArgumentException("Укажите srt — путь к файлу субтитров.")
        val srt = io.resolveMediaPath(srtRaw)
        if (!srt.exists() || !srt.isFile) {
            throw IllegalArgumentException("Файл субтитров не найден: «${srt.name}».")
        }
        return when (arguments.text("mode")?.lowercase() ?: "mux") {
            "burn" -> execute(FfmpegArgs.subtitlesBurn(src.path, dest.path, srt.path), dest, "Субтитры вожжены")
            "mux", "" -> execute(
                FfmpegArgs.subtitlesMux(src.path, dest.path, srt.path, FfmpegArgs.subtitleCodecFor(dest.extension)),
                dest,
                "Субтитры вложены",
            )
            else -> throw IllegalArgumentException("mode должен быть burn или mux.")
        }
    }

    fun convertVideo(arguments: JsonObject): ToolResult {
        val src = inputFile(arguments)
        val dest = destFile(arguments)
        val reencode = arguments.bool("reencode")
        val encoder = if (reencode) requireAudioEncoder(dest) else null
        return execute(FfmpegArgs.remux(src.path, dest.path, reencode, audio = false, audioEncoder = encoder), dest, "Видео сконвертировано")
    }

    // ---- validation helpers ----

    private fun trimDuration(arguments: JsonObject, start: Double): Double {
        val durationRaw = arguments.text("duration")
        val endRaw = arguments.text("end")
        val duration = when {
            durationRaw != null -> MediaTime.parseSeconds(durationRaw) ?: throw invalid("duration", durationRaw)
            endRaw != null -> {
                val end = MediaTime.parseSeconds(endRaw) ?: throw invalid("end", endRaw)
                if (end <= start) throw IllegalArgumentException("end должен быть больше start.")
                end - start
            }
            else -> throw IllegalArgumentException("Укажите end или duration.")
        }
        if (duration <= 0.0) throw IllegalArgumentException("Длительность должна быть больше нуля.")
        return duration
    }

    private fun inputFile(arguments: JsonObject): File {
        val raw = arguments.text("path") ?: throw IllegalArgumentException("Укажите path — путь к исходному файлу.")
        val file = io.resolveMediaPath(raw)
        if (!file.exists() || !file.isFile) throw IllegalArgumentException("Файл не найден: «${file.name}».")
        return file
    }

    private fun inputFiles(arguments: JsonObject): List<File> {
        val raw = arguments.text("paths")
            ?: throw IllegalArgumentException("Укажите paths — пути к файлам через запятую или точку с запятой.")
        val files = raw.split(',', ';').map { it.trim() }.filter { it.isNotBlank() }
            .map { io.resolveMediaPath(it) }
        if (files.size < 2) throw IllegalArgumentException("Нужно минимум два файла для склейки.")
        files.forEach { if (!it.exists() || !it.isFile) throw IllegalArgumentException("Файл не найден: «${it.name}».") }
        return files
    }

    private fun destFile(arguments: JsonObject): File {
        val raw = arguments.text("dest") ?: throw IllegalArgumentException("Укажите dest — путь к новому файлу.")
        val file = io.resolveMediaPath(raw)
        if (file.exists()) {
            throw IllegalArgumentException("Файл назначения уже существует: «${file.absolutePath}». Перезапись запрещена.")
        }
        return file
    }

    private fun requireAudioEncoder(dest: File): String =
        AudioFormats.encoderFor(dest.extension) ?: throw unsupportedAudioFormat(dest.extension)

    private fun unsupportedAudioFormat(extension: String): IllegalArgumentException =
        IllegalArgumentException(
            "Неподдерживаемый аудиоформат «${extension.ifBlank { "без расширения" }}». " +
                "Поддерживается: ${AudioFormats.supportedExtensions.joinToString(", ")}.",
        )

    private fun optionalPositiveInt(raw: String?, name: String): Int? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        val parsed = value.toIntOrNull() ?: throw invalid(name, value)
        if (parsed <= 0) throw IllegalArgumentException("$name должен быть больше нуля.")
        return parsed
    }

    /** Validates a positive-integer string and returns it unchanged (for pass-through to ffmpeg). */
    private fun optionalPositiveIntText(raw: String?, name: String): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        optionalPositiveInt(value, name)
        return value
    }

    private fun invalid(name: String, value: String): IllegalArgumentException =
        IllegalArgumentException("Некорректный $name: «$value».")

    /** Runs ffmpeg, then verifies the destination was actually produced (non-empty). */
    private fun execute(args: List<String>, dest: File, verb: String): ToolResult {
        val parent = dest.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return ToolResult(false, "Не удалось создать каталог для «${dest.absolutePath}».")
        }
        val outcome = FfmpegRunner.run(args)
        if (!outcome.success) {
            dest.delete()
            return ToolResult(false, "ffmpeg не смог выполнить операцию: ${outcome.message}")
        }
        if (!dest.exists() || dest.length() <= 0L) {
            dest.delete()
            return ToolResult(false, "Результат не записан (файл пуст или отсутствует): «${dest.absolutePath}».")
        }
        return ToolResult(true, "$verb: «${dest.absolutePath}» (${dest.length()} байт).")
    }

    private companion object {
        val PRESETS = setOf(
            "ultrafast", "superfast", "veryfast", "faster", "fast", "medium",
            "slow", "slower", "veryslow",
        )

        /** Quotes a path for the ffmpeg concat demuxer's `file '…'` directive. */
        fun concatQuote(path: String): String {
            val normalized = path.replace("\\", "/")
            return "'" + normalized.replace("'", "'\\''") + "'"
        }
    }
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.bool(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.content?.trim().equals("true", ignoreCase = true)

/**
 * Common [ToolHandler] shell for the audio/video editing tools: runs the operation off the main
 * thread, turns an invalid-argument exception into an honest failure, and lets any other failure be
 * reported without killing the agent loop. The concrete tool supplies only its name and [run].
 */
internal abstract class AudioVideoEditToolHandler(protected val ops: AudioVideoEditOps) : ToolHandler {

    protected abstract fun run(arguments: JsonObject): ToolResult

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            run(arguments)
        } catch (e: IllegalArgumentException) {
            ToolResult(false, e.message ?: "Некорректные аргументы.")
        } catch (e: OutOfMemoryError) {
            ToolResult(false, "Недостаточно памяти для обработки медиа.")
        } catch (e: Exception) {
            ToolResult(false, "Не удалось обработать медиа: ${e.message}")
        }
    }
}

/** Registers the audio/video editing handlers in catalog order (bug-items 11 and 12). */
internal class AudioVideoEditToolsHandler(io: MediaPathIo) {

    private val ops = AudioVideoEditOps(io)

    fun handlers(): List<ToolHandler> = listOf(
        AudioTrimToolHandler(ops),
        AudioConcatToolHandler(ops),
        AudioConvertToolHandler(ops),
        AudioVolumeToolHandler(ops),
        AudioSpeedToolHandler(ops),
        AudioExtractFromVideoToolHandler(ops),
        AudioTagWriteToolHandler(ops),
        VideoTrimToolHandler(ops),
        VideoConcatToolHandler(ops),
        VideoResizeToolHandler(ops),
        VideoBitrateToolHandler(ops),
        VideoCompressToolHandler(ops),
        VideoRotateToolHandler(ops),
        VideoExtractAudioToolHandler(ops),
        VideoSubtitlesToolHandler(ops),
        VideoConvertToolHandler(ops),
    )
}

private class AudioTrimToolHandler(ops: AudioVideoEditOps) : AudioVideoEditToolHandler(ops) {
    override val name = "audio_trim"
    override fun run(arguments: JsonObject) = ops.trim(arguments, audio = true)
}

private class AudioConcatToolHandler(ops: AudioVideoEditOps) : AudioVideoEditToolHandler(ops) {
    override val name = "audio_concat"
    override fun run(arguments: JsonObject) = ops.concat(arguments, audio = true)
}

private class AudioConvertToolHandler(ops: AudioVideoEditOps) : AudioVideoEditToolHandler(ops) {
    override val name = "audio_convert"
    override fun run(arguments: JsonObject) = ops.convertAudio(arguments)
}

private class AudioVolumeToolHandler(ops: AudioVideoEditOps) : AudioVideoEditToolHandler(ops) {
    override val name = "audio_volume"
    override fun run(arguments: JsonObject) = ops.volume(arguments)
}

private class AudioSpeedToolHandler(ops: AudioVideoEditOps) : AudioVideoEditToolHandler(ops) {
    override val name = "audio_speed"
    override fun run(arguments: JsonObject) = ops.speed(arguments)
}

private class AudioExtractFromVideoToolHandler(ops: AudioVideoEditOps) : AudioVideoEditToolHandler(ops) {
    override val name = "audio_extract_from_video"
    override fun run(arguments: JsonObject) = ops.extractAudio(arguments)
}

private class AudioTagWriteToolHandler(ops: AudioVideoEditOps) : AudioVideoEditToolHandler(ops) {
    override val name = "audio_tag_write"
    override fun run(arguments: JsonObject) = ops.tagWrite(arguments)
}

private class VideoTrimToolHandler(ops: AudioVideoEditOps) : AudioVideoEditToolHandler(ops) {
    override val name = "video_trim"
    override fun run(arguments: JsonObject) = ops.trim(arguments, audio = false)
}

private class VideoConcatToolHandler(ops: AudioVideoEditOps) : AudioVideoEditToolHandler(ops) {
    override val name = "video_concat"
    override fun run(arguments: JsonObject) = ops.concat(arguments, audio = false)
}

private class VideoResizeToolHandler(ops: AudioVideoEditOps) : AudioVideoEditToolHandler(ops) {
    override val name = "video_resize"
    override fun run(arguments: JsonObject) = ops.resize(arguments)
}

private class VideoBitrateToolHandler(ops: AudioVideoEditOps) : AudioVideoEditToolHandler(ops) {
    override val name = "video_bitrate"
    override fun run(arguments: JsonObject) = ops.bitrate(arguments)
}

private class VideoCompressToolHandler(ops: AudioVideoEditOps) : AudioVideoEditToolHandler(ops) {
    override val name = "video_compress"
    override fun run(arguments: JsonObject) = ops.compress(arguments)
}

private class VideoRotateToolHandler(ops: AudioVideoEditOps) : AudioVideoEditToolHandler(ops) {
    override val name = "video_rotate"
    override fun run(arguments: JsonObject) = ops.rotate(arguments)
}

private class VideoExtractAudioToolHandler(ops: AudioVideoEditOps) : AudioVideoEditToolHandler(ops) {
    override val name = "video_extract_audio"
    override fun run(arguments: JsonObject) = ops.extractAudio(arguments)
}

private class VideoSubtitlesToolHandler(ops: AudioVideoEditOps) : AudioVideoEditToolHandler(ops) {
    override val name = "video_subtitles"
    override fun run(arguments: JsonObject) = ops.subtitles(arguments)
}

private class VideoConvertToolHandler(ops: AudioVideoEditOps) : AudioVideoEditToolHandler(ops) {
    override val name = "video_convert"
    override fun run(arguments: JsonObject) = ops.convertVideo(arguments)
}
