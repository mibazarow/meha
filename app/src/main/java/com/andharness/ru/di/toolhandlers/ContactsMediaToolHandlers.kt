package com.andharness.ru.di.toolhandlers

import android.content.Context
import com.andharness.ru.data.tools.apps.AppsToolsHandler
import com.andharness.ru.data.tools.autofill.AutofillToolsHandler
import com.andharness.ru.data.tools.calendar.CalendarToolsHandler
import com.andharness.ru.data.tools.calls.CallsToolsHandler
import com.andharness.ru.data.tools.camera.CameraToolsHandler
import com.andharness.ru.data.tools.camera.PhotoAnalyzer
import com.andharness.ru.data.tools.contacts.ContactsToolsHandler
import com.andharness.ru.data.tools.location.LocationToolsHandler
import com.andharness.ru.data.tools.media.AudioToolsHandler
import com.andharness.ru.data.tools.media.MediaToolsHandler
import com.andharness.ru.data.tools.microphone.MicrophoneToolsHandler
import com.andharness.ru.data.tools.roles.RoleToolsHandler
import com.andharness.ru.data.tools.sms.SmsToolsHandler
import com.andharness.ru.data.tools.trust.TrustedSourceToolsHandler
import com.andharness.ru.data.tools.wallpaper.WallpaperToolsHandler
import com.andharness.ru.domain.undo.UndoRecorder
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.ui.UiInteractionBridge
import okhttp3.OkHttpClient

/** Contacts, SMS, calls, camera, location, apps, roles and media tool handlers. */
internal fun contactsMediaToolHandlers(
    context: Context,
    okHttpClient: OkHttpClient,
    undoRecorder: UndoRecorder,
    photoAnalyzer: PhotoAnalyzer,
    uiInteractionBridge: UiInteractionBridge,
): List<ToolHandler> =
    (ContactsToolsHandler(context, undoRecorder).handlers()
        + SmsToolsHandler(context).handlers()
        + CallsToolsHandler(context).handlers()
        + CameraToolsHandler(context, photoAnalyzer).handlers()
        + MicrophoneToolsHandler(context).handlers()
        + LocationToolsHandler(context).handlers()
        + CalendarToolsHandler(context, undoRecorder).handlers()
        + AppsToolsHandler(context, okHttpClient).handlers()
        + RoleToolsHandler(context, uiInteractionBridge).handlers()
        + AutofillToolsHandler(context).handlers()
        + TrustedSourceToolsHandler(context).handlers()
        + MediaToolsHandler(context, uiInteractionBridge).handlers()
        + WallpaperToolsHandler(context, okHttpClient).handlers()
        + AudioToolsHandler(context, uiInteractionBridge).handlers()
)
