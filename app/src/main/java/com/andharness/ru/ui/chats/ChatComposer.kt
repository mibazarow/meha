package com.andharness.ru.ui.chats

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.andharness.ru.R
import com.andharness.ru.domain.chat.PhotoCapture
import com.andharness.ru.ui.theme.BrandGold
import com.andharness.ru.ui.theme.BrandGoldBright
import com.andharness.ru.ui.theme.BrandGoldDeep
import com.andharness.ru.ui.theme.BrandGreen
import com.andharness.ru.ui.theme.BrandGreenBright
import com.andharness.ru.ui.theme.BrandGreenDeep
import com.andharness.ru.ui.theme.OnBrandGold
import com.andharness.ru.ui.theme.OnPrimaryGradient
import java.io.File

// Allocation strategy: the send-action stops are fixed brand tokens from `ui/theme/Color.kt` (no
// theme or state inputs), so each gradient brush is allocated once at file scope instead of on
// every recomposition; `Brush` instances are immutable and safe to share.
// https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/Brush#linearGradient(kotlin.Pair(kotlin.Float,androidx.compose.ui.graphics.Color),kotlin.Array((kotlin.Pair(kotlin.Float,androidx.compose.ui.graphics.Color))))
private val GreenSendBrush = Brush.linearGradient(
    0f to BrandGreenBright,
    0.55f to BrandGreen,
    1f to BrandGreenDeep
)

private val GoldSendBrush = Brush.linearGradient(
    0f to BrandGoldBright,
    0.55f to BrandGold,
    1f to BrandGoldDeep
)

/**
 * The chat composer (§19.2, §21): the staged-attachment notice, the attachment menu (image / file /
 * camera), the text field and a SINGLE contextual trailing action (Telegram-style). That one button
 * is one of three states, picked from the composer state:
 *  - streaming → Stop generation;
 *  - not streaming and there is something to send (`input` non-blank or a staged attachment) → Send;
 *  - not streaming and nothing to send → Microphone (press-and-hold to dictate).
 * Dictation uses the platform [SpeechDictation] (push-to-talk with the stock `SpeechRecognizer`, not
 * the whisper model); its result is INSERTED into the field (never auto-sent), which flips the same
 * button to Send. The per-message «Озвучить» action for TTS remains in the message bubble. The
 * composer also owns the attachment pickers, the capture flow and the runtime-permission requests
 * for the camera and the microphone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatComposer(
    state: ChatUiState,
    viewModel: ChatViewModel
) {
    val context = LocalContext.current

    // Push-to-talk dictation (redesign §2 phase 5 restoration), now behind the composer's single
    // contextual trailing button: when there is nothing to send the button IS the microphone,
    // implemented with the platform `SpeechRecognizer` — the same engine as the `speech_to_text`
    // tool — instead of the whisper model. Hold the mic to dictate; releasing inserts the text into
    // the field (it is NOT auto-sent) and the same button becomes Send. The controller is created
    // on the main thread and released on dispose.
    // References:
    // - SpeechRecognizer: https://developer.android.com/reference/android/speech/SpeechRecognizer
    // - RecognitionListener: https://developer.android.com/reference/android/speech/RecognitionListener
    // - RecognizerIntent: https://developer.android.com/reference/android/speech/RecognizerIntent
    // - detectTapGestures (press/release): https://developer.android.com/reference/kotlin/androidx/compose/foundation/gestures/package-summary#detectTapGestures(kotlinx.coroutines.CoroutineScope,androidx.compose.ui.input.pointer.PointerInputScope,kotlin.coroutines.SuspendFunction1,kotlin.coroutines.SuspendFunction1,kotlin.coroutines.SuspendFunction1)
    // - remembered launcher for a runtime permission:
    //   https://developer.android.com/training/permissions/requesting
    val dictation = remember { SpeechDictation(context) }
    var listening by remember { mutableStateOf(false) }

    DisposableEffect(dictation) {
        onDispose { dictation.destroy() }
    }

    // RECORD_AUDIO is a runtime permission (already declared in the manifest). It is requested on
    // demand; once granted the user is asked to hold the mic again.
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onComposerNotice(
            if (granted) {
                context.getString(R.string.chat_dictation_press_again)
            } else {
                context.getString(R.string.chat_dictation_permission)
            }
        )
    }

    dictation.onListeningChanged = { listening = it }
    dictation.onResult = { text ->
        // Insert the recognised text into the field — do NOT auto-send. The field becomes
        // non-blank, so the same trailing button turns into an enabled Send the user can edit or
        // tap. A blank result is dropped (the field is left as-is and the button stays the mic).
        val combined = (state.input.trim() + " " + text).trim()
        if (combined.isNotBlank()) {
            viewModel.onInputChange(combined)
        }
    }
    dictation.onError = { message -> viewModel.onComposerNotice(message) }

    val startDictation: () -> Unit = {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            dictation.start()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // §19.2: user attachments. Images go through the Photo Picker (API 33+, with a platform
    // fallback on older releases), any file through SAF `OpenDocument` with MIME `*/*`.
    // References: https://developer.android.com/training/data-storage/shared/photopicker and
    // https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.OpenDocument
    val imageAttachmentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) viewModel.addAttachment(uri)
    }

    val fileAttachmentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.addAttachment(uri)
    }

    var attachMenuOpen by remember { mutableStateOf(false) }

    // §19.2: «Сделать фото» — a full-resolution capture through the system camera app. The
    // `TakePicture` contract writes the image into the content URI we hand it, so a temp file is
    // created in the app cache and exposed with the app's EXISTING FileProvider authority
    // (`<cache-path>` in res/xml/file_paths.xml already covers `cacheDir`, so no path entry was
    // added). `TakePicturePreview` is deliberately NOT used: it returns a low-resolution thumbnail
    // that would make the attached photo useless for OCR/document tools.
    // References:
    // - ActivityResultContracts.TakePicture (saves into the provided content Uri, returns true on
    //   success):
    //   https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.TakePicture
    // - FileProvider / file_paths.xml paths:
    //   https://developer.android.com/training/secure-file-sharing/setup-sharing
    //   https://developer.android.com/reference/androidx/core/content/FileProvider#getUriForFile
    var captureFilePath by rememberSaveable { mutableStateOf<String?>(null) }

    val takePhotoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        // A cancel (success == false) leaves nothing to attach; the ViewModel deletes the temp file
        // and stages no attachment. The file is kept (across recreation too, via the saveable path)
        // until here so the cleanup always has it.
        val path = captureFilePath
        captureFilePath = null
        if (path != null) viewModel.onPhotoCaptured(success, File(path))
    }

    // Shared by the menu item (when CAMERA is already granted) and the permission callback.
    val launchCameraCapture: () -> Unit = {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            viewModel.onPhotoCaptureNotice("На устройстве нет камеры.")
        } else {
            try {
                val dir = File(context.cacheDir, "captures").apply { mkdirs() }
                val file = File(dir, PhotoCapture.tempFileName(System.currentTimeMillis()))
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                captureFilePath = file.absolutePath
                takePhotoLauncher.launch(uri)
            } catch (e: Exception) {
                // No camera app can handle the capture (ActivityNotFoundException), or FileProvider
                // could not expose the temp file — report honestly instead of crashing.
                viewModel.onPhotoCaptureNotice(
                    "Не удалось открыть камеру: ${e.message ?: "нет подходящего приложения."}"
                )
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCameraCapture()
        } else {
            viewModel.onPhotoCaptureNotice(
                "Нет разрешения на камеру — фото не сделано. Разрешите доступ к камере " +
                    "и попробуйте снова."
            )
        }
    }

    // §19.2/§19.4: honest status when a limit was exceeded or a file could not be read.
    if (state.attachmentNotice != null) {
        Text(
            text = state.attachmentNotice!!,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        val isStreamingForInput = state.isStreaming
        Box {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .clickable(enabled = !isStreamingForInput) {
                        viewModel.clearAttachmentNotice()
                        attachMenuOpen = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_attach_file),
                    contentDescription = stringResource(R.string.chat_composer_attach_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(26.dp)
                )
            }
            DropdownMenu(
                expanded = attachMenuOpen,
                onDismissRequest = { attachMenuOpen = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_composer_attach_image)) },
                    onClick = {
                        attachMenuOpen = false
                        imageAttachmentLauncher.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_search_result_file)) },
                    onClick = {
                        attachMenuOpen = false
                        fileAttachmentLauncher.launch(arrayOf("*/*"))
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_composer_attach_camera)) },
                    onClick = {
                        attachMenuOpen = false
                        // CAMERA is a runtime permission; request it in context and only
                        // then open the capture. https://developer.android.com/training/permissions/requesting
                        val granted = context.checkSelfPermission(
                            Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            launchCameraCapture()
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        TextField(
            value = state.input,
            onValueChange = viewModel::onInputChange,
            placeholder = { Text(stringResource(R.string.chat_composer_placeholder)) },
            enabled = !state.isStreaming,
            maxLines = 5,
            // Filled, rounded field per the design (`--r-xl`, `--md-surface-container-high`
            // equivalent kept theme-driven via `surfaceContainerLow`). The filled indicator
            // shares the container colour, so the field reads as borderless.
            shape = MaterialTheme.shapes.extraLarge,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                focusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedIndicatorColor = MaterialTheme.colorScheme.surfaceContainerLow,
                disabledIndicatorColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            modifier = Modifier
                .weight(1f)
                .minimumInteractiveComponentSize()
                .heightIn(min = 50.dp)
                .testTag("chat_composer_input")
        )
        Spacer(modifier = Modifier.width(8.dp))
        val isStreaming = state.isStreaming
        val canSend = state.input.isNotBlank() || state.attachments.isNotEmpty()
        // Single contextual trailing action (Telegram-style). `state.input` is the single source of
        // truth for which action it shows: with something to send it is Send, otherwise the mic.
        // While streaming it is always Stop generation.
        val showMic = !isStreaming && !canSend
        // D2: the send action carries the brand gradient; the streaming stop state falls back to
        // `error`, and the inactive mic state uses the theme's surfaceVariant. The gradient is gold
        // in the light theme, green in the dark; the theme signal is the resolved `surface` luminance
        // (same pattern as `AccentPanel`).
        // https://developer.android.com/reference/kotlin/androidx/compose/ui/graphics/package-summary#(androidx.compose.ui.graphics.Color).luminance()
        val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        val brandGradient = if (darkTheme) GreenSendBrush else GoldSendBrush
        Box(
            modifier = Modifier
                .size(50.dp)
                .testTag(
                    when {
                        isStreaming -> "chat_stop_button"
                        showMic -> "chat_dictation_button"
                        else -> "chat_send_button"
                    }
                )
                .background(
                    brush = when {
                        isStreaming -> SolidColor(MaterialTheme.colorScheme.error)
                        showMic -> SolidColor(
                            if (listening) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                        canSend -> brandGradient
                        else -> SolidColor(MaterialTheme.colorScheme.surfaceVariant)
                    },
                    shape = CircleShape
                )
                // The gesture depends on the state: the mic uses press-and-hold
                // (`detectTapGestures(onPress = …)` with `tryAwaitRelease` — start on press, stop on
                // release, cancel on an aborted gesture) while send/stop stay a plain `clickable`.
                // References:
                // - detectTapGestures / tryAwaitRelease (press-and-hold):
                //   https://developer.android.com/reference/kotlin/androidx/compose/foundation/gestures/package-summary#detectTapGestures(kotlinx.coroutines.CoroutineScope,androidx.compose.ui.input.pointer.PointerInputScope,kotlin.coroutines.SuspendFunction1,kotlin.coroutines.SuspendFunction1,kotlin.coroutines.SuspendFunction1)
                // - Modifier.pointerInput:
                //   https://developer.android.com/reference/kotlin/androidx/compose/ui/input/pointer/package-summary#pointerInput
                // - Modifier.clickable:
                //   https://developer.android.com/reference/kotlin/androidx/compose/foundation/package-summary#clickable
                .then(
                    if (showMic) {
                        Modifier.pointerInput(showMic) {
                            detectTapGestures(
                                onPress = {
                                    startDictation()
                                    val released = tryAwaitRelease()
                                    if (released) dictation.stop() else dictation.cancel()
                                }
                            )
                        }
                    } else {
                        Modifier.clickable(enabled = isStreaming || canSend) {
                            if (isStreaming) viewModel.stopGeneration() else viewModel.send()
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            when {
                isStreaming -> Icon(
                    painter = painterResource(R.drawable.ic_stop),
                    contentDescription = stringResource(R.string.chat_composer_stop_cd),
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(22.dp)
                )
                showMic -> Icon(
                    painter = painterResource(R.drawable.ic_mic),
                    contentDescription = stringResource(
                        if (listening) {
                            R.string.chat_dictation_listening_cd
                        } else {
                            R.string.chat_composer_dictate_cd
                        }
                    ),
                    tint = if (listening) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(24.dp)
                )
                else -> Icon(
                    painter = painterResource(R.drawable.ic_send),
                    contentDescription = stringResource(R.string.overlay_quick_ask_send),
                    tint = if (canSend) {
                        if (darkTheme) OnPrimaryGradient else OnBrandGold
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }

    if (state.error != null) {
        Text(
            text = state.error!!,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
}
