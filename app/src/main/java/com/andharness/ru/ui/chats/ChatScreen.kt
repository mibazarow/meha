package com.andharness.ru.ui.chats

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.print.PrintManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andharness.ru.R
import com.andharness.ru.domain.ui.UiRequest
import com.andharness.ru.domain.ui.UiResult
import com.andharness.ru.ui.permission.SpecialAccessIntents
import com.andharness.ru.ui.theme.ChatLinkDark
import com.andharness.ru.ui.theme.ChatLinkLight

/**
 * The chat screen entry point. It owns the one-per-screen wiring that the section composables must
 * share: the observed state, the single Markwon instance, the system back handling, the
 * lifecycle-driven voice hooks and the request launchers the agent's [UiRequest]s are answered
 * with. The visual sections live in their own files: [ChatTopBar], [ChatMessageList],
 * [AttachmentTiles] and [ChatComposer].
 */
@Composable
fun ChatScreen(
    chatId: Long,
    onBack: () -> Unit,
    highlightMessageId: Long? = null,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // In-chat search (redesign §4 «Экран чата», phase 9): when the user taps a result, the screen
    // exits search mode and hands this message id to the transcript, which scrolls to it once.
    // `null` means "no pending scroll".
    var searchScrollMessageId by remember { mutableStateOf<Long?>(null) }

    // Reference clock for the search-result time labels, captured once per visit (like the chat
    // list).
    val now = remember { System.currentTimeMillis() }

    // update.md bug #7: the chat screen must never be a dead end. The system Back is routed through
    // the same callback as the top-bar «Назад» button, whose host falls back to a real destination
    // when there is nothing to pop (a notification deep link clears the back stack). Without this,
    // Back on the chat was either a silent no-op or killed the whole app — both look like a freeze.
    // Reference: https://developer.android.com/guide/navigation/navigation-custom-back
    BackHandler { onBack() }

    // §18/§19: ONE Markwon instance for the whole screen. Building it inside every bubble
    // re-created the parser/theme per message; here it is built once and shared by all messages.
    // Links are tinted with a fixed readable blue (`ChatLinkLight`/`ChatLinkDark`, chosen by the
    // resolved `surface` luminance so a theme switch rebuilds the instance), underlined,
    // auto-linked (Linkify) and opened through the scheme allow-list resolver — see
    // `buildChatMarkwon`.
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val linkColorArgb = (if (darkTheme) ChatLinkDark else ChatLinkLight).toArgb()
    val markwon = remember(linkColorArgb) { buildChatMarkwon(context, linkColorArgb) }

    // update.md: optional «использовано X / N» indicator for the last compiled request.
    val contextUsage = contextUsageText(state.contextUsedTokens, state.contextBudgetTokens)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.grantPermission() else viewModel.denyPermission()
    }

    val specialPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // User returned from the system settings screen; re-check the permission. Services like
        // accessibility/notification-listener connect slightly after returning, so wait briefly
        // and re-check a few times before giving up. Delegated to the ViewModel so leaving the
        // screen cannot cancel the re-check and strand the agent until its gate timeout.
        val permission = state.pendingPermission
        if (permission != null) {
            viewModel.awaitPermissionResult(permission)
        }
    }

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Persist access across process death; see
            // https://developer.android.com/training/data-storage/shared/documents-files
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // Best-effort: the picker may not have granted persistable access.
            }
            viewModel.submitUiResult(UiResult.UriResult(uri))
        } else {
            viewModel.submitUiResult(UiResult.Cancelled)
        }
    }

    val documentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.submitUiResult(UiResult.UriResult(uri))
        } else {
            viewModel.submitUiResult(UiResult.Cancelled)
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            viewModel.submitUiResult(UiResult.UriResult(uri))
        } else {
            viewModel.submitUiResult(UiResult.Cancelled)
        }
    }

    val intentSenderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.submitUiResult(
            UiResult.ConsentResult(result.resultCode == android.app.Activity.RESULT_OK)
        )
    }

    // Screen-capture (MediaProjection) consent: launch the system dialog and hand the raw
    // resultCode/data back to the tool, which forwards it to the recording foreground service.
    // https://developer.android.com/media/grow/media-projection
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.submitUiResult(UiResult.ActivityResult(result.resultCode, result.data))
    }

    // Device-admin activation (BUG-021): the tool builds DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN
    // WITHOUT FLAG_ACTIVITY_NEW_TASK and asks this Activity to show it. The raw result is handed
    // back so the tool can re-check DevicePolicyManager.isAdminActive and report honestly.
    // https://developer.android.com/guide/topics/admin/device-admin
    val deviceAdminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.submitUiResult(UiResult.ActivityResult(result.resultCode, result.data))
    }

    // A pending UiRequest lives in the bridge until the user answers, so it survives Activity
    // recreation. A bare `LaunchedEffect(state.uiRequest)` would therefore re-open the same
    // picker/dialog after a rotation; the saveable one-shot flag handles each request exactly once
    // and resets when the request is cleared (result submitted or cancelled). The request carries
    // no nonce, so a boolean is enough — only one request can be pending at a time.
    var uiRequestHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.uiRequest) {
        val req = state.uiRequest
        if (req == null) {
            uiRequestHandled = false
            return@LaunchedEffect
        }
        if (uiRequestHandled) return@LaunchedEffect
        uiRequestHandled = true
        when (req) {
            is UiRequest.OpenDocumentTree -> treeLauncher.launch(null)
            is UiRequest.OpenDocument -> documentLauncher.launch(req.mimeTypes.toTypedArray())
            is UiRequest.CreateDocument -> createDocumentLauncher.launch(req.suggestedName)
            is UiRequest.MediaConsent -> intentSenderLauncher.launch(
                IntentSenderRequest.Builder(req.intentSender).build()
            )
            is UiRequest.ScreenCaptureConsent -> screenCaptureLauncher.launch(req.intent)
            // System-role request reuses the same StartActivityForResult launcher and reports the
            // raw resultCode/data back. https://developer.android.com/reference/android/app/role/RoleManager
            is UiRequest.RoleRequest -> screenCaptureLauncher.launch(req.intent)
            // Device-admin activation must be shown from an Activity (no FLAG_ACTIVITY_NEW_TASK);
            // if the screen cannot be launched at all, report an honest failure instead of hanging.
            is UiRequest.DeviceAdminRequest -> try {
                deviceAdminLauncher.launch(req.intent)
            } catch (e: Exception) {
                viewModel.submitUiResult(
                    UiResult.Failed("Не удалось открыть экран активации администратора: ${e.message}")
                )
            }
            // System print dialog (BUG-023): PrintManager.print() throws
            // "Can print only from an activity" unless the service was obtained from an Activity
            // context, so the tool hands the adapter here and this Activity runs the call.
            // https://developer.android.com/reference/android/print/PrintManager#print(java.lang.String,%20android.print.PrintDocumentAdapter,%20android.print.PrintAttributes)
            is UiRequest.PrintRequest -> {
                val printManager = context.findActivity()
                    ?.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                if (printManager == null) {
                    viewModel.submitUiResult(
                        UiResult.Failed("Системная служба печати недоступна на этом устройстве.")
                    )
                } else {
                    try {
                        // Returns as soon as the system print UI is up; the user still has to
                        // confirm, so the result means "dialog shown", never "printed".
                        printManager.print(req.jobName, req.adapter, req.attributes)
                        viewModel.submitUiResult(UiResult.PrintDialogShown)
                    } catch (e: Exception) {
                        viewModel.submitUiResult(
                            UiResult.Failed("Не удалось открыть системный диалог печати: ${e.message}")
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(state.pendingPermission) {
        val permission = state.pendingPermission
        if (permission != null) {
            if (viewModel.isSpecialPermission(permission)) {
                val intent = SpecialAccessIntents.intentFor(context, permission)
                android.util.Log.d("Andharness", "perm request: $permission special=${viewModel.isSpecialPermission(permission)} action=${intent?.action}")
                if (intent != null) {
                    val handled = try {
                        specialPermissionLauncher.launch(intent)
                        true
                    } catch (e: ActivityNotFoundException) {
                        false
                    }
                    // Some devices lack the dedicated settings screen for this permission.
                    // Deny gracefully so the orchestrator doesn't hang, instead of crashing.
                    if (!handled) viewModel.denyPermission()
                }
            } else {
                permissionLauncher.launch(permission)
            }
        }
    }

    // §21: never keep talking off-screen — stop the assistant's per-message TTS when the chat
    // screen is no longer visible. (Dictation was removed in redesign §2 phase 5.)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.onLeaveForeground()
    }

    Scaffold(
        topBar = {
            Column {
                ChatTopBar(
                    title = state.title.ifBlank { stringResource(R.string.chat_title_fallback) },
                    onBack = onBack,
                    onSearchClick = viewModel::toggleSearch
                )
                // The in-chat search bar sits directly below the header while search mode is on
                // (redesign §4 «Экран чата», phase 9). The trailing ✕ exits search.
                if (state.isSearchActive) {
                    ChatInChatSearchBar(
                        query = state.searchQuery,
                        onQueryChange = viewModel::onSearchQueryChange,
                        onClose = viewModel::closeSearch,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // `innerPadding` уже применил системные inset'ы (status/navigation bars). Сообщаем
                // дочерним модификаторам, что эта часть inset'ов израсходована, иначе `imePadding()`
                // добавит высоту navigation bar второй раз, когда откроется клавиатура.
                .consumeWindowInsets(innerPadding)
                // Клавиатура сдвигает вверх только контент (список + поле ввода); `topBar` лежит
                // вне этого Column, поэтому остаётся неподвижным.
                // https://developer.android.com/develop/ui/compose/layouts/insets
                .imePadding()
        ) {
            if (state.isSearchActive) {
                // Search mode: replace the transcript + composer with this chat's results. Tapping a
                // result scrolls to that message and leaves search mode.
                ChatInChatSearchResults(
                    matches = state.searchMatches,
                    isSearching = state.isSearching,
                    now = now,
                    onMatchClick = { match ->
                        searchScrollMessageId = match.messageId
                        viewModel.closeSearch()
                    },
                    modifier = Modifier.weight(1f)
                )
                return@Column
            }

            if (contextUsage != null) {
                Text(
                    text = contextUsage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }
            // Concurrent-run visibility (SPEC §20): what the agent runs in the background + whether
            // THIS chat's request is waiting for a slot. Kept to one honest line each, no new screen.
            if (state.backgroundRuns.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.chat_background_prefix,
                        state.backgroundRuns.joinToString(", ")
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }
            if (state.queuedInLine) {
                Text(
                    text = stringResource(R.string.chat_queued_in_line),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            }

            ChatMessageList(
                state = state,
                highlightMessageId = highlightMessageId,
                searchScrollMessageId = searchScrollMessageId,
                onSearchScrollConsumed = { searchScrollMessageId = null },
                markwon = markwon,
                onSpeak = viewModel::speakMessage,
                modifier = Modifier.weight(1f)
            )

            // §19.2: staged attachments are shown as tiles above the input until the message is sent.
            if (state.attachments.isNotEmpty()) {
                AttachmentTiles(
                    attachments = state.attachments,
                    enabled = !state.isStreaming,
                    onRemove = viewModel::removeAttachment
                )
            }

            ChatComposer(state = state, viewModel = viewModel)
        }
    }
}

/** Разворачивает [ContextWrapper]-обёртки Compose до реальной [Activity]. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
