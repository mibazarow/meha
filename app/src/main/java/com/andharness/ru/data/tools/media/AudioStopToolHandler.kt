package com.andharness.ru.data.tools.media

import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/** `audio_stop`: stops the single-slot playback, honest when nothing is playing. */
internal class AudioStopToolHandler : ToolHandler {

    override val name = "audio_stop"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val held = AudioPlaybackHolder.take()
            ?: return@withContext ToolResult(false, "Сейчас ничего не воспроизводится.")
        val playing = runCatching { held.player.isPlaying }.getOrDefault(false)
        AudioPlaybackHolder.release(held)
        if (playing) {
            ToolResult(true, "Воспроизведение остановлено: «${File(held.path).name}».")
        } else {
            ToolResult(true, "Воспроизведение уже завершено — плеер освобождён.")
        }
    }
}
