package com.andharness.ru.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.andharness.ru.ui.activity.AgentActivityScreen
import com.andharness.ru.ui.automation.AutomationRulesScreen
import com.andharness.ru.ui.chats.ChatListScreen
import com.andharness.ru.ui.chats.ChatScreen
import com.andharness.ru.ui.communications.CommunicationsScreen
import com.andharness.ru.ui.communications.PeopleScreen
import com.andharness.ru.ui.communications.PersonTimelineScreen
import com.andharness.ru.ui.communications.TranscriptScreen
import com.andharness.ru.ui.db.DatabaseViewerScreen
import com.andharness.ru.ui.home.HomeScreen
import com.andharness.ru.ui.language.LanguageChooserScreen
import com.andharness.ru.ui.license.LicenseScreen
import com.andharness.ru.ui.memory.MemoryScreen
import com.andharness.ru.ui.onboarding.OnboardingScreen
import com.andharness.ru.ui.schedule.ScheduledTasksScreen
import com.andharness.ru.ui.settings.ProviderConnectionScreen
import com.andharness.ru.ui.settings.SettingsScreen
import com.andharness.ru.ui.skills.SkillLibraryScreen
import com.andharness.ru.ui.suggestions.SuggestionsScreen
import com.andharness.ru.ui.permissions.PermissionsScreen

@Composable
fun AppNavHost(
    deepLink: ChatDeepLink? = null,
    onDeepLinkHandled: () -> Unit = {},
    startDestination: String = AppDestination.CHATS,
    /**
     * Экран после явного выбора языка: лицензия (если ещё не принята) или онбординг. Считает
     * [MainActivity] через [LicenseGate.afterLanguage], чтобы уже принявшему лицензию пользователю
     * не показали EULA повторно.
     */
    languageChosenDestination: String = AppDestination.LICENSE,
) {
    val navController = rememberNavController()

    /**
     * Back that can never "do nothing" (update.md bug #7). `popBackStack()` returns false when the
     * current destination is the back-stack root — which is exactly the state after a notification
     * deep link, a share/process-text hand-off or a widget tile, because those navigate with
     * `popUpTo(graph) { inclusive = true }`. In that state a plain `popBackStack()` silently
     * swallowed every tap on «Назад» and the chat screen looked frozen. Falling back to a real
     * destination guarantees Back always navigates.
     */
    fun backOr(fallback: String) {
        if (!navController.popBackStack()) {
            navController.navigate(fallback) { launchSingleTop = true }
        }
    }

    // §20: a notification tap opens the exact chat it refers to. `launchSingleTop` keeps a
    // repeated tap from stacking a second copy of the same chat screen. The handled nonce makes
    // this one-shot: a recomposition/rotation carrying the same link must not navigate again and
    // wipe the restored back stack. `deepLinkHighlight` is retained after [onDeepLinkHandled]
    // clears the upstream value so `ChatScreen` can still scroll to the linked message.
    var handledDeepLinkNonce by rememberSaveable { mutableStateOf(0L) }
    var deepLinkHighlight by remember { mutableStateOf<ChatDeepLink?>(null) }
    LaunchedEffect(deepLink?.nonce) {
        val link = deepLink ?: return@LaunchedEffect
        if (link.nonce == handledDeepLinkNonce) return@LaunchedEffect
        handledDeepLinkNonce = link.nonce
        deepLinkHighlight = link
        navController.navigate(DeepLinkRouting.route(link.chatId)) {
            launchSingleTop = true
            popUpTo(navController.graph.id) { inclusive = true }
        }
        // Ask the host to drop the value; a later tap carries a fresh nonce and navigates again.
        onDeepLinkHandled()
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(AppDestination.LANGUAGE) {
            // Самый первый экран первого запуска: выбор языка до лицензии. После выбора уходим на
            // лицензию (или сразу на онбординг, если она уже принята) и убираем шаг языка из
            // back stack — «Назад» на него не вернёт.
            LanguageChooserScreen(
                onChosen = {
                    navController.navigate(languageChosenDestination) {
                        popUpTo(AppDestination.LANGUAGE) { inclusive = true }
                    }
                }
            )
        }

        composable(AppDestination.LICENSE) {
            LicenseScreen(
                onAccepted = {
                    // После принятия лицензия уходит из back stack — «Назад» на онбординг
                    // не вернёт пользователя на экран соглашения.
                    navController.navigate(AppDestination.ONBOARDING) {
                        popUpTo(AppDestination.LICENSE) { inclusive = true }
                    }
                }
            )
        }

        composable(AppDestination.ONBOARDING) {
            OnboardingScreen(
                onFinished = {
                    // Onboarding is the gate: once configured (or already configured) the user
                    // lands on the chat list — the app's launch screen. Home is reachable from
                    // Settings (the «Разделы» row).
                    navController.navigate(AppDestination.CHATS) {
                        popUpTo(AppDestination.ONBOARDING) { inclusive = true }
                    }
                },
                // The permissions blurb on onboarding links to the same full inventory screen.
                onOpenPermissions = { navController.navigate(AppDestination.PERMISSIONS) }
            )
        }

        composable(AppDestination.HOME) {
            HomeScreen(
                onBack = { backOr(AppDestination.CHATS) },
                onChats = { backOr(AppDestination.CHATS) },
                onSettings = { navController.navigate(AppDestination.SETTINGS) },
                onProviderConnection = { navController.navigate(AppDestination.PROVIDER_CONNECTION) },
                onScheduledTasks = { navController.navigate(AppDestination.SCHEDULED_TASKS) },
                onAutomationRules = { navController.navigate(AppDestination.AUTOMATION_RULES) },
                onSkills = { navController.navigate(AppDestination.SKILLS) },
                onDatabase = { navController.navigate(AppDestination.DB_VIEWER) },
                onMemory = { navController.navigate(AppDestination.MEMORY) },
                onSuggestions = { navController.navigate(AppDestination.SUGGESTIONS) },
                onAgentActivity = { navController.navigate(AppDestination.AGENT_ACTIVITY) },
                onCommunications = { navController.navigate(AppDestination.COMMUNICATIONS) },
                onPeople = { navController.navigate(AppDestination.PEOPLE) }
            )
        }

        composable(AppDestination.CHATS) {
            ChatListScreen(
                onChatClick = { navController.navigate(AppDestination.chat(it)) },
                // The chat-list header no longer carries a gear; its bottom panel routes to Settings,
                // the provider connection and the Home hub («Разделы»). Settings still exposes Home too.
                onOpenSettings = { navController.navigate(AppDestination.SETTINGS) { launchSingleTop = true } },
                onOpenProvider = { navController.navigate(AppDestination.PROVIDER_CONNECTION) { launchSingleTop = true } },
                onOpenHome = { navController.navigate(AppDestination.HOME) { launchSingleTop = true } }
            )
        }

        composable(
            AppDestination.CHAT,
            arguments = listOf(navArgument("chatId") { type = NavType.LongType })
        ) { entry ->
            val chatId = entry.arguments?.getLong("chatId") ?: 0L
            ChatScreen(
                chatId = chatId,
                onBack = { backOr(AppDestination.CHATS) },
                highlightMessageId = deepLinkHighlight?.takeIf { it.chatId == chatId }?.messageId
            )
        }

        composable(AppDestination.SETTINGS) {
            SettingsScreen(
                onBack = { backOr(AppDestination.HOME) },
                // The Home hub is no longer reachable from the chat-list header (phase 2); Settings
                // now exposes it through the «Разделы» row.
                onOpenHome = { navController.navigate(AppDestination.HOME) { launchSingleTop = true } },
                onOpenPermissions = { navController.navigate(AppDestination.PERMISSIONS) },
                onReset = {
                    navController.navigate(AppDestination.ONBOARDING) {
                        popUpTo(AppDestination.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(AppDestination.PROVIDER_CONNECTION) {
            ProviderConnectionScreen(onBack = { backOr(AppDestination.HOME) })
        }

        composable(AppDestination.PERMISSIONS) {
            PermissionsScreen(onBack = { backOr(AppDestination.HOME) })
        }

        composable(AppDestination.SCHEDULED_TASKS) {
            ScheduledTasksScreen(
                onBack = { backOr(AppDestination.HOME) },
                onOpenChat = { navController.navigate(AppDestination.chat(it)) },
                onOpenChats = { navController.navigate(AppDestination.CHATS) }
            )
        }

        composable(AppDestination.AUTOMATION_RULES) {
            AutomationRulesScreen(
                onBack = { backOr(AppDestination.HOME) },
                onOpenChats = { navController.navigate(AppDestination.CHATS) }
            )
        }

        composable(AppDestination.SKILLS) {
            SkillLibraryScreen(onBack = { backOr(AppDestination.HOME) })
        }

        composable(AppDestination.DB_VIEWER) {
            DatabaseViewerScreen(
                onBack = { backOr(AppDestination.HOME) },
                onOpenChats = { navController.navigate(AppDestination.CHATS) }
            )
        }

        composable(AppDestination.MEMORY) {
            MemoryScreen(onBack = { backOr(AppDestination.HOME) })
        }

        composable(AppDestination.AGENT_ACTIVITY) {
            AgentActivityScreen(
                onBack = { backOr(AppDestination.HOME) },
                onOpenChats = { navController.navigate(AppDestination.CHATS) }
            )
        }

        composable(AppDestination.SUGGESTIONS) {
            SuggestionsScreen(onBack = { backOr(AppDestination.HOME) })
        }

        composable(AppDestination.COMMUNICATIONS) {
            CommunicationsScreen(
                onBack = { backOr(AppDestination.HOME) },
                onOpenTranscript = { navController.navigate(AppDestination.transcript(it)) },
                onOpenSettings = { navController.navigate(AppDestination.SETTINGS) }
            )
        }

        composable(
            AppDestination.TRANSCRIPT,
            arguments = listOf(navArgument("recordingId") { type = NavType.LongType })
        ) {
            TranscriptScreen(
                onBack = { backOr(AppDestination.COMMUNICATIONS) },
                onOpenPerson = { navController.navigate(AppDestination.personTimeline(it)) }
            )
        }

        composable(AppDestination.PEOPLE) {
            PeopleScreen(
                onBack = { backOr(AppDestination.HOME) },
                onOpenPerson = { navController.navigate(AppDestination.personTimeline(it)) }
            )
        }

        composable(
            AppDestination.PERSON_TIMELINE,
            arguments = listOf(navArgument("personId") { type = NavType.LongType })
        ) {
            PersonTimelineScreen(onBack = { backOr(AppDestination.PEOPLE) })
        }
    }
}
