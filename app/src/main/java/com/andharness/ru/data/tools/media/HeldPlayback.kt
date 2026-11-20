package com.andharness.ru.data.tools.media

import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer

/** A playback currently owned by `audio_play` and stoppable by `audio_stop`. */
internal class HeldPlayback(
    val player: MediaPlayer,
    val audioManager: AudioManager,
    val focusRequest: AudioFocusRequest,
    val path: String,
)
