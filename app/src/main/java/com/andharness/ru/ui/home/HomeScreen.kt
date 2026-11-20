package com.andharness.ru.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andharness.ru.R
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation
import com.andharness.ru.ui.components.InternalTopBar
import com.andharness.ru.ui.settings.SettingsClickRow
import com.andharness.ru.ui.settings.SettingsSection
import com.andharness.ru.ui.theme.AlwaysDarkSurface
import com.andharness.ru.ui.theme.BrandGold
import com.andharness.ru.ui.theme.OnBrandGold

/** The author's website shown in the footer; the URL and its label are locale-independent. */
private const val AUTHOR_SITE_URL = "https://bazarow.ru"
private const val AUTHOR_SITE_LABEL = "bazarow.ru"

/**
 * Start screen / navigation hub (D13: Home stays, there is no bottom navigation). The content is
 * rendered in the unified internal-page style introduced by the Settings redesign: every block is a
 * [SettingsSection] — a clear title above one rounded surface card — and each entry inside it is a
 * [SettingsClickRow] (optional leading glyph, title, subtitle, trailing chevron). This replaces the
 * legacy `AppTokens` kit (uppercase section headers, dense list rows and hairline separators) so
 * the hub reads like the rest of the internal screens. The
 * [com.andharness.ru.ui.components.AccentPanel] status panel is likewise replaced by a
 * `Подключение` [SettingsSection].
 *
 * Behavior is unchanged: the same navigation callbacks, the same one-shot `and-fade-up` entry
 * cascade per visit (motion kit D1, via [EnterAnimationTracker]) and the same footer link. The
 * status string itself still comes verbatim from [HomeViewModel]; this screen only renders it and
 * adds a status dot (brand `primary` when the LLM connection is configured).
 *
 * References (official docs):
 * - Material 3 components — https://developer.android.com/develop/ui/compose/components
 * - `Surface` (rounded card that clips its content to the shape) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary#Surface(androidx.compose.ui.Modifier,androidx.compose.ui.graphics.Shape,androidx.compose.ui.graphics.Color,androidx.compose.ui.graphics.Color,androidx.compose.ui.unit.Dp,androidx.compose.ui.unit.Dp,androidx.compose.foundation.BorderStroke,kotlin.Function0)
 * - Lists in Compose — https://developer.android.com/develop/ui/compose/lists
 * - `Scaffold` container/content color and `WindowInsets` (status-bar inset for the top bar) —
 *   https://developer.android.com/develop/ui/compose/system/material-insets
 * - `stringResource` for localized UI strings —
 *   https://developer.android.com/develop/ui/compose/resources#string-resources
 */
@Composable
fun HomeScreen(
    onBack: () -> Unit,
    onChats: () -> Unit,
    onSettings: () -> Unit,
    onProviderConnection: () -> Unit,
    onScheduledTasks: () -> Unit,
    onAutomationRules: () -> Unit,
    onSkills: () -> Unit,
    onDatabase: () -> Unit,
    onMemory: () -> Unit,
    onSuggestions: () -> Unit,
    onAgentActivity: () -> Unit,
    onCommunications: () -> Unit,
    onPeople: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Трекер входной анимации: каскадный вход блоков (`and-fade-up`) проигрывается ровно один раз
    // за визит экрана и не повторяется при рекомпозициях (D1). Шапка не анимируется.
    val homeTracker = remember { EnterAnimationTracker() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            InternalTopBar(
                title = stringResource(R.string.app_name),
                onBack = onBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HomeStatusPanel(
                status = state.status,
                configured = state.configured,
                onConfigure = onProviderConnection,
                modifier = Modifier
                    .fillMaxWidth()
                    .enterAnimation(
                        index = 0,
                        tracker = homeTracker,
                        itemKey = "home_status"
                    )
            )

            HomeSection(
                label = stringResource(R.string.home_section_main),
                index = 1,
                tracker = homeTracker,
                itemKey = "home_section_main",
                containerColor = BrandGold
            ) {
                SettingsClickRow(
                    title = stringResource(R.string.home_row_chats),
                    subtitle = stringResource(R.string.home_row_chats_sub),
                    leadingIcon = Icons.AutoMirrored.Filled.List,
                    onClick = onChats,
                    contentColor = OnBrandGold
                )
            }

            HomeSection(
                label = stringResource(R.string.home_section_automation),
                index = 2,
                tracker = homeTracker,
                itemKey = "home_section_automation"
            ) {
                SettingsClickRow(
                    title = stringResource(R.string.home_row_schedules),
                    subtitle = stringResource(R.string.home_row_schedules_sub),
                    leadingIcon = Icons.Filled.DateRange,
                    onClick = onScheduledTasks
                )
                SettingsClickRow(
                    title = stringResource(R.string.home_row_automation),
                    subtitle = stringResource(R.string.home_row_automation_sub),
                    leadingIcon = Icons.Filled.Build,
                    onClick = onAutomationRules
                )
                SettingsClickRow(
                    title = stringResource(R.string.home_row_skills),
                    subtitle = stringResource(R.string.home_row_skills_sub),
                    leadingIcon = Icons.Filled.PlayArrow,
                    onClick = onSkills
                )
                SettingsClickRow(
                    title = stringResource(R.string.home_row_suggestions),
                    subtitle = stringResource(R.string.home_row_suggestions_sub),
                    leadingIcon = Icons.Filled.Star,
                    onClick = onSuggestions
                )
            }

            HomeSection(
                label = stringResource(R.string.home_section_more),
                index = 3,
                tracker = homeTracker,
                itemKey = "home_section_more"
            ) {
                SettingsClickRow(
                    title = stringResource(R.string.home_row_provider),
                    subtitle = stringResource(R.string.home_row_provider_sub),
                    leadingIcon = Icons.Filled.Build,
                    onClick = onProviderConnection
                )
                SettingsClickRow(
                    title = stringResource(R.string.home_row_database),
                    subtitle = stringResource(R.string.home_row_database_sub),
                    leadingIcon = Icons.Filled.Search,
                    onClick = onDatabase
                )
                SettingsClickRow(
                    title = stringResource(R.string.home_row_memory),
                    subtitle = stringResource(R.string.home_row_memory_sub),
                    leadingIcon = Icons.Filled.Favorite,
                    onClick = onMemory
                )
                SettingsClickRow(
                    title = stringResource(R.string.home_row_activity),
                    subtitle = stringResource(R.string.home_row_activity_sub),
                    leadingIcon = Icons.Filled.CheckCircle,
                    onClick = onAgentActivity
                )
                SettingsClickRow(
                    title = stringResource(R.string.home_row_comms),
                    subtitle = stringResource(R.string.home_row_comms_sub),
                    leadingIcon = Icons.Filled.Call,
                    onClick = onCommunications
                )
                SettingsClickRow(
                    title = stringResource(R.string.home_row_people),
                    subtitle = stringResource(R.string.home_row_people_sub),
                    leadingIcon = Icons.Filled.Person,
                    onClick = onPeople
                )
                SettingsClickRow(
                    title = stringResource(R.string.home_row_settings),
                    subtitle = stringResource(R.string.home_row_settings_sub),
                    leadingIcon = Icons.Filled.Settings,
                    onClick = onSettings
                )
            }

            HomeFooter(modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * Subtle footer at the very bottom of the Home scroll: a muted copyright line plus a tappable
 * link to the author's website. The copyright is localized; the URL label is a domain and stays a
 * constant.
 *
 * The link uses the Compose-idiomatic [LocalUriHandler] (`AndroidUriHandler.openUri` opens the
 * given URL in the system browser). `openUri` throws [IllegalArgumentException] when the URI is
 * invalid or no app can handle it, so the call is wrapped in `runCatching` — a missing browser must
 * never crash the hub screen.
 *
 * References (official docs):
 * - `AndroidUriHandler.openUri` / `UriHandler` —
 *   https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/AndroidUriHandler
 * - `LocalUriHandler` usage in text interactions —
 *   https://developer.android.com/develop/ui/compose/text/user-interactions
 */
@Composable
private fun HomeFooter(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = modifier.padding(top = 8.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = stringResource(R.string.home_footer_copyright),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = AUTHOR_SITE_LABEL,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(role = Role.Button) {
                    runCatching { uriHandler.openUri(AUTHOR_SITE_URL) }
                }
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

/**
 * Connection status in the unified style: a `Подключение` [SettingsSection] whose card holds a
 * gold status dot plus the LLM status line from [HomeViewModel]. The card is painted the dark bar
 * black (`#151B15`); the status line uses white ink and the dot is gold (translucent when not
 * configured — the "not configured" text alone already says what is wrong).
 *
 * When the connection is not configured a [SettingsClickRow] to [onConfigure] is shown beneath the
 * status, so the panel doubles as the next step; a configured connection stays a plain,
 * non-interactive status line.
 */
@Composable
private fun HomeStatusPanel(
    status: String,
    configured: Boolean,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsSection(
        title = stringResource(R.string.home_section_connection),
        modifier = modifier,
        containerColor = AlwaysDarkSurface
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = if (configured) {
                            BrandGold
                        } else {
                            BrandGold.copy(alpha = 0.6f)
                        },
                        shape = CircleShape
                    )
            )
            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        if (!configured) {
            SettingsClickRow(
                title = stringResource(R.string.home_configure),
                leadingIcon = Icons.Filled.Settings,
                onClick = onConfigure,
                contentColor = Color.White
            )
        }
    }
}

/**
 * One hub section in the unified style: a [SettingsSection] (title above a rounded card) whose
 * [SettingsClickRow]s share the card's rhythm. Applied with a one-shot `and-fade-up` entry cascade
 * step so the hub keeps its motion while the visual kit is unified.
 */
@Composable
private fun HomeSection(
    label: String,
    index: Int,
    tracker: EnterAnimationTracker,
    itemKey: String,
    containerColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsSection(
        title = label,
        modifier = Modifier.enterAnimation(index = index, tracker = tracker, itemKey = itemKey),
        containerColor = containerColor,
        content = content
    )
}
