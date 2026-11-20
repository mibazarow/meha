package com.andharness.ru.ui.skills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.andharness.ru.R
import com.andharness.ru.ui.components.EnterAnimationTracker
import com.andharness.ru.ui.components.enterAnimation
import com.andharness.ru.ui.components.InternalTopBar
import com.andharness.ru.ui.components.searchFieldColors
import com.andharness.ru.ui.settings.SettingsHelperText
import com.andharness.ru.ui.settings.SettingsSection

/**
 * «Навыки» — the user-facing library of saved skills (M2, SPEC §24.2). It lists the skills the agent
 * created via `skill_save`, lets the user search them, run one (through the same `skill_run` engine
 * the agent uses), delete one (confirmed — deletion is permanent) and inspect its steps/parameters.
 *
 * Content is re-skinned to the unified internal-page style matching the Settings/Memory redesign: the
 * explainer is a [SettingsSection] with muted [SettingsHelperText] and every skill is a rounded
 * [androidx.compose.material3.Surface] card (see [SkillRow]) instead of a flat row followed by a
 * divider. Spacing (16 dp gutters, 8 dp gaps) and `bodyLarge` / `bodySmall` typography follow the
 * shared kit. The top bar is the screen's fixed header; the search field carries `imePadding()` so
 * the keyboard never covers it (insets are consumed once via `consumeWindowInsets`). All UI strings
 * come from resources.
 *
 * Note (follow-up): the row/step/parameter metadata lines produced by [SkillLibraryLogic] are
 * presentation helpers that return Russian `String`s without a `Context`; localizing them is a
 * separate refactor of that helper plus its JVM tests.
 *
 * References (official docs):
 * - Material 3 components (Scaffold / TopAppBar / AlertDialog / OutlinedTextField / Surface) —
 *   https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary
 * - Compose lists — https://developer.android.com/develop/ui/compose/lists
 * - Window insets (consumeWindowInsets + imePadding) —
 *   https://developer.android.com/develop/ui/compose/layouts/insets
 * - `stringResource` for localized UI strings —
 *   https://developer.android.com/develop/ui/compose/resources#string-resources
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillLibraryScreen(
    onBack: () -> Unit,
    viewModel: SkillLibraryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val rowTracker = remember { EnterAnimationTracker() }


    Scaffold(
        topBar = {
            InternalTopBar(
                title = stringResource(R.string.home_row_skills),
                onBack = onBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // `innerPadding` already applied the system insets; tell children so `imePadding()`
                // does not add the navigation-bar height a second time while the keyboard is open.
                .consumeWindowInsets(innerPadding)
                .imePadding()
        ) {
            SettingsSection(
                title = stringResource(R.string.skills_section_about_title),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .enterAnimation(index = 0)
            ) {
                SettingsHelperText(stringResource(R.string.skills_intro))
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                label = { Text(stringResource(R.string.skills_search_label)) },
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
                                contentDescription = stringResource(R.string.memory_search_clear),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                colors = searchFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .enterAnimation(index = 1, tracker = rowTracker, itemKey = "skills_search")
            )

            if (state.isRunning) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Text(
                        text = stringResource(
                            R.string.skills_running,
                            state.runningSkillName.orEmpty()
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val skills = state.visibleSkills
                when {
                    state.isLoading && state.skills.isEmpty() ->
                        CenterMessage(stringResource(R.string.schedule_loading))

                    state.isSearchActive && skills.isEmpty() ->
                        CenterMessage(
                            stringResource(
                                R.string.skills_empty_search,
                                state.query.trim()
                            )
                        )

                    skills.isEmpty() -> CenterMessage(stringResource(R.string.skills_empty))

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(
                            items = skills,
                            key = { _, skill -> skill.id }
                        ) { index, skill ->
                            SkillRow(
                                skill = skill,
                                running = state.isRunning,
                                onOpen = { viewModel.showDetails(skill) },
                                onRun = { viewModel.requestRun(skill) },
                                onDelete = { viewModel.askDelete(skill) },
                                modifier = Modifier.enterAnimation(
                                    index = index + 2,
                                    tracker = rowTracker,
                                    itemKey = skill.id
                                )
                            )
                        }
                    }
                }
            }

            state.message?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }

    SkillLibraryDialogs(state = state, viewModel = viewModel)
}
