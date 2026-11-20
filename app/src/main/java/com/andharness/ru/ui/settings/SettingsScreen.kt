package com.andharness.ru.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andharness.ru.R
import com.andharness.ru.domain.permission.SpecialAccess
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.InternalTopBar
import com.andharness.ru.ui.permission.SpecialAccessIntents
import com.andharness.ru.ui.theme.ThemeViewModel

/**
 * Settings screen entry point. It owns the scaffold, the top bar, the scroll container and the
 * overlay-permission side effects, then composes one section composable per block (updates first —
 * the highlighted update-check block, then theme, language, search, incoming SMS, communications,
 * overlay, special access, prompt/actions, home) and the updater dialogs.
 *
 * Provider configuration is not here: it lives on the dedicated
 * [ProviderConnectionScreen] (`AppDestination.PROVIDER_CONNECTION`), reached from the home screen.
 * The semantic-search toggle lives here ([SettingsSemanticSearchSection]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenHome: () -> Unit,
    onOpenPermissions: () -> Unit,
    onReset: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    themeViewModel: ThemeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Hoisted so the scrolling content can be restored to the top when needed.
    val scrollState = rememberScrollState()

    // Трекер входной анимации: секции настроек входят каскадом (`and-fade-up`) один раз за визит
    // экрана, топ-бар не анимируется; диалоги сброса/удаления ключа не затронуты.
    val settingsTracker = remember { EnterAnimationTracker() }

    // Плавающий помощник: системный экран выдачи «Поверх других приложений» открывается один раз
    // по one-shot запросу из ViewModel, когда пользователь включил тумблер без разрешения.
    // https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_OVERLAY_PERMISSION
    val overlayContext = LocalContext.current
    LaunchedEffect(state.overlayPermissionPromptNonce) {
        val nonce = state.overlayPermissionPromptNonce
        if (nonce != 0L) {
            viewModel.onOverlayPermissionPromptShown()
            val intent = SpecialAccessIntents.intentFor(overlayContext, SpecialAccess.OVERLAY)
            if (intent != null) {
                // runCatching: a device without the dedicated screen must not crash the settings UI.
                runCatching { overlayContext.startActivity(intent) }
            }
        }
    }
    // Разрешение выдаётся в системных настройках, поэтому его реальное значение перечитываем при
    // каждом возвращении на экран — статус под тумблером всегда честный.
    val overlayLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(overlayLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshOverlayPermission()
        }
        overlayLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { overlayLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            InternalTopBar(
                title = stringResource(R.string.settings_title),
                onBack = onBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // `innerPadding` already applied the system insets (status/navigation bars). Tell
                // children those insets are consumed, otherwise `imePadding()` adds the navigation
                // bar height a second time when the keyboard opens.
                .consumeWindowInsets(innerPadding)
                // The keyboard shifts only the scrolling content; the `topBar` lives outside this
                // Column, so it stays pinned.
                // https://developer.android.com/develop/ui/compose/layouts/insets
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // «Обновления» — first block on the screen (highlighted update-check card).
                SettingsUpdatesSection(
                    state = state,
                    viewModel = viewModel,
                    tracker = settingsTracker,
                )
                SettingsThemeSection(
                    themeViewModel = themeViewModel,
                    tracker = settingsTracker,
                )
                SettingsLanguageSection(
                    state = state,
                    viewModel = viewModel,
                    tracker = settingsTracker,
                )
                SettingsSemanticSearchSection(
                    state = state,
                    viewModel = viewModel,
                    tracker = settingsTracker,
                )
                SettingsStyleSection(
                    state = state,
                    viewModel = viewModel,
                    tracker = settingsTracker,
                )
                SettingsIncomingSection(
                    state = state,
                    viewModel = viewModel,
                    tracker = settingsTracker,
                )
                SettingsCommunicationsSection(
                    state = state,
                    viewModel = viewModel,
                    tracker = settingsTracker,
                )
                SettingsOverlaySection(
                    state = state,
                    viewModel = viewModel,
                    tracker = settingsTracker,
                )
                SettingsSpecialAccessSection(
                    tracker = settingsTracker,
                    onOpenPermissions = onOpenPermissions,
                )
                SettingsPromptActionsSection(
                    state = state,
                    viewModel = viewModel,
                    tracker = settingsTracker,
                    onReset = onReset,
                )
                SettingsHomeSection(
                    tracker = settingsTracker,
                    onOpenHome = onOpenHome,
                )
            }
        }
    }

    SettingsUpdateDialogs(state = state, viewModel = viewModel)
}
