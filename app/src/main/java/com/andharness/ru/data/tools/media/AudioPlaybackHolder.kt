package com.andharness.ru.data.tools.media

import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer

/**
 * Process-wide single-slot holder of the current playback, so `audio_stop` (a separate tool
 * invocation in the same process) can stop what `audio_play` started. All access is synchronized
 * because the two tools may run on different dispatcher threads.
 */
internal object AudioPlaybackHolder {

    private var current: HeldPlayback? = null

    @Synchronized
    fun hold(player: MediaPlayer, audioManager: AudioManager, focusRequest: AudioFocusRequest, path: String) {
        current?.let { release(it) }
        current = HeldPlayback(player, audioManager, focusRequest, path)
    }

    @Synchronized
    fun take(): HeldPlayback? {
        val held = current
        current = null
        return held
    }

    @Synchronized
    fun releaseIfSame(player: MediaPlayer) {
        val held = current ?: return
        if (held.player === player) {
            current = null
            release(held)
        }
    }

    @Synchronized
    fun clear() {
        current?.let { release(it) }
        current = null
    }

    fun release(held: HeldPlayback) {
        runCatching { held.player.reset() }
        runCatching { held.player.release() }
        runCatching { held.audioManager.abandonAudioFocusRequest(held.focusRequest) }
    }
}
