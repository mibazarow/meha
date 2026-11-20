package com.andharness.ru.data.tools.media

import android.content.Context
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.ui.UiInteractionBridge

/**
 * Builds the audio / media-session [ToolHandler]s: `audio_play`, `audio_stop`, `audio_mode_set`,
 * `audio_stream_analysis`, `audio_capture_app`, `media_control_other`, `media_sessions_list`, in
 * their original registration order. This class is a thin coordinator; each tool lives in its own
 * `<Tool>Handler` class and the shared media-session plumbing is in [MediaSessionSupport].
 *
 * References (official Android docs):
 * - Audio focus (`AudioFocusRequest`, `requestAudioFocus`, `abandonAudioFocusRequest`, API 26+):
 *   https://developer.android.com/media/optimize/audio-focus
 * - `AudioManager` (`setMode`, `MODE_*`, `setSpeakerphoneOn`):
 *   https://developer.android.com/reference/android/media/AudioManager
 * - `AudioRecord` (`Builder`, `getMinBufferSize`, `read`, `startRecording`, `release`):
 *   https://developer.android.com/reference/android/media/AudioRecord
 * - Playback capture (`AudioPlaybackCaptureConfiguration`) + MediaProjection consent:
 *   https://developer.android.com/media/platform/av-capture
 * - `MediaController` / `TransportControls` and `MediaSessionManager.getActiveSessions()`:
 *   https://developer.android.com/reference/android/media/session/MediaController
 *   https://developer.android.com/reference/android/media/session/MediaSessionManager
 */
class AudioToolsHandler(
    private val context: Context,
    private val uiBridge: UiInteractionBridge,
) {

    fun handlers(): List<ToolHandler> {
        val sessions = MediaSessionSupport(context)
        return listOf(
            AudioPlayToolHandler(context),
            AudioStopToolHandler(),
            AudioModeSetToolHandler(context),
            AudioStreamAnalysisToolHandler(context),
            AudioCaptureAppToolHandler(context, uiBridge),
            MediaControlOtherToolHandler(sessions),
            MediaSessionsListToolHandler(sessions),
        )
    }
}
