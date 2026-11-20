package com.andharness.ru.ui.communications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andharness.ru.R
import com.andharness.ru.ui.components.CenterMessage
import com.andharness.ru.ui.components.searchFieldColors
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation
import com.andharness.ru.ui.components.InternalTopBar
import com.andharness.ru.ui.settings.SettingsClickRow
import com.andharness.ru.ui.settings.SettingsHelperText
import com.andharness.ru.ui.settings.SettingsSection

/**
 * «Люди» — the user-facing list of the unified personal base: people the agent knows from calls,
 * SMS, messengers and transcripts. Tapping a row opens that person's timeline.
 *
 * Content is re-skinned to the unified internal-page style matching the Settings/Memory redesign:
 * the explainer is a [SettingsSection] with muted [SettingsHelperText], and every person is a
 * rounded [Surface] card (`shapes.large`, `surfaceContainerLow`) holding a [SettingsClickRow],
 * instead of the legacy flat list row behind a hairline divider. Spacing (16 dp gutters, 8 dp gaps)
 * and the `bodyLarge` / `bodySmall` typography follow the shared kit; the legacy `AccentPanel` /
 * `AppTokens` kit is no longer used here. All fixed UI copy is localized via `stringResource`.
 *
 * Note (follow-up): the `state.message` text built inside the ViewModel is not localized here.
 *
 * Honest states: loading, «людей пока нет» and «ничего не найдено» each say exactly what is going
 * on — no placeholder people are ever invented.
 *
 * References (official docs):
 * - Compose Material 3 `Scaffold`/`Surface`/`OutlinedTextField` —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary
 * - Compose lists — https://developer.android.com/develop/ui/compose/lists
 * - `stringResource` — https://developer.android.com/develop/ui/compose/resources#string-resources
 * - `hiltViewModel()` / `collectAsStateWithLifecycle()` —
 *   https://developer.android.com/training/dependency-injection/hilt-jetpack ,
 *   https://developer.android.com/reference/kotlin/androidx/lifecycle/compose/package-summary
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    onBack: () -> Unit,
    onOpenPerson: (Long) -> Unit,
    viewModel: PeopleViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val rowTracker = remember { EnterAnimationTracker() }


    Scaffold(
        topBar = {
            InternalTopBar(
                title = stringResource(R.string.home_row_people),
                onBack = onBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
        ) {
            SettingsSection(
                title = stringResource(R.string.people_section_about_title),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .enterAnimation(index = 0)
            ) {
                SettingsHelperText(stringResource(R.string.people_intro))
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text(stringResource(R.string.people_search_label)) },
                supportingText = {
                    Text(stringResource(R.string.people_search_support))
                },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = stringResource(R.string.people_search_clear),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = searchFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .enterAnimation(index = 1, tracker = rowTracker, itemKey = "people_search")
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val items = state.visiblePeople
                when {
                    state.isLoading && state.people.isEmpty() ->
                        CenterMessage(stringResource(R.string.people_loading))

                    state.isSearchActive && state.isSearching && items.isEmpty() ->
                        CenterMessage(stringResource(R.string.people_searching))

                    items.isEmpty() -> CenterMessage(
                        if (state.isSearchActive) {
                            stringResource(R.string.people_empty_search)
                        } else {
                            stringResource(R.string.people_empty)
                        }
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            items = items,
                            key = { _, item -> item.person.id }
                        ) { index, item ->
                            PersonCard(
                                title = item.person.displayName,
                                subtitle = item.hint,
                                onClick = { onOpenPerson(item.person.id) },
                                modifier = Modifier.enterAnimation(
                                    index = index + 2,
                                    tracker = rowTracker,
                                    itemKey = item.person.id
                                )
                            )
                        }
                    }
                }
            }

            // Честная строка ошибки: появляется только когда последнее действие не удалось.
            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
    }
}


/**
 * One person as a rounded card wrapping the shared [SettingsClickRow] (leading Person glyph, title
 * with an optional identity hint, trailing chevron). The card surface matches the unified style kit.
 */
@Composable
private fun PersonCard(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        SettingsClickRow(
            title = title,
            subtitle = subtitle,
            leadingIcon = Icons.Filled.Person,
            onClick = onClick,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}
