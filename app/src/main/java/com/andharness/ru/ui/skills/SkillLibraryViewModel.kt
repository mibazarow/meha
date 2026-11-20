package com.andharness.ru.ui.skills

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.andharness.ru.data.skill.SkillGcRunner
import com.andharness.ru.data.skill.SkillStore
import com.andharness.ru.data.tools.skill.SkillPlanner
import com.andharness.ru.data.tools.skill.SkillRunEngine
import com.andharness.ru.domain.skill.Skill
import com.andharness.ru.service.AccessibilityAutomationService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

/**
 * Backs the «Навыки» screen. It reads the existing Android-free [SkillStore] library and runs skills
 * through the existing [SkillRunEngine] (via its `skill_run` [com.andharness.ru.domain.tools.ToolHandler]),
 * so the UI executes exactly the same code path as the agent tool — no duplicated replay logic.
 *
 * Honest gating: before a run the view-model plans the skill with [SkillPlanner] and asks
 * [SkillLibraryLogic.unavailableReason]; a plan that cannot run (no steps, a broken step, a missing
 * required parameter, or the accessibility service off) is reported as a message instead of touching
 * the screen. The engine remains the final authority and re-checks at execute time.
 *
 * Editing is intentionally limited to [rename] (name + description): full step editing is out of
 * scope for this slice and stays with the agent's `skill_save` tool (see README/SPEC).
 */
@HiltViewModel
class SkillLibraryViewModel @Inject constructor(
    @ApplicationContext appContext: Context,
    private val store: SkillStore,
) : ViewModel() {

    private val engine = SkillRunEngine(appContext, store)

    private val _uiState = MutableStateFlow(SkillLibraryUiState())
    val uiState: StateFlow<SkillLibraryUiState> = _uiState.asStateFlow()

    init {
        // Idle-skill GC (M2): the library screen is where stale skills would be shown, so expired
        // ones (unused > 20 days) are collected here as well as at app start. Runs off the main
        // thread and is best-effort — the store StateFlow then reflects the deletions.
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = SkillGcRunner(store).run()
            if (deleted > 0) Log.i(TAG, "Skill GC deleted $deleted idle skill(s) from the library screen")
        }
        // The store owns the single source of truth; the screen only mirrors its StateFlow.
        viewModelScope.launch {
            store.skills.collect { skills ->
                _uiState.update { it.copy(skills = skills, isLoading = false) }
            }
        }
    }

    /** Applies the search query; filtering itself is delegated to the pure [SkillLibraryLogic]. */
    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query, message = null) }
    }

    fun showDetails(skill: Skill) = _uiState.update { it.copy(details = skill) }
    fun dismissDetails() = _uiState.update { it.copy(details = null) }

    fun dismissEdit() = _uiState.update { it.copy(editTarget = null) }

    fun askDelete(skill: Skill) = _uiState.update { it.copy(deleteTarget = skill, message = null) }
    fun dismissDelete() = _uiState.update { it.copy(deleteTarget = null) }

    /**
     * Runs [skill] straight away when it declares no parameters; otherwise opens the parameter
     * dialog so the user supplies the `{placeholders}` before the plan is built.
     */
    fun requestRun(skill: Skill) {
        if (skill.parameters.isEmpty()) {
            confirmRun(skill, emptyMap())
        } else {
            _uiState.update { it.copy(runTarget = skill, message = null) }
        }
    }

    fun dismissRun() = _uiState.update { it.copy(runTarget = null) }

    fun dismissReport() = _uiState.update { it.copy(report = null) }
    fun clearMessage() = _uiState.update { it.copy(message = null) }

    /**
     * Renames a skill and/or edits its description, reusing [SkillStore.save] (which drops the skill
     * into the same slot by id). A blank name is refused honestly — an anonymous skill is never saved.
     */
    fun rename(skillId: String, name: String, description: String) {
        val skill = _uiState.value.skills.firstOrNull { it.id == skillId } ?: return
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(message = "Имя навыка не может быть пустым.") }
            return
        }
        val stored = store.save(
            skill.copy(
                name = trimmed,
                description = description.trim(),
                updatedAt = System.currentTimeMillis(),
            )
        )
        _uiState.update {
            it.copy(
                editTarget = null,
                message = if (stored == null) "Не удалось сохранить навык." else null,
            )
        }
    }

    /** Deletes [skillId] through the store (the same path as the `skill_delete` tool). */
    fun delete(skillId: String) {
        val skill = _uiState.value.skills.firstOrNull { it.id == skillId } ?: return
        val removed = store.delete(skill.id)
        _uiState.update {
            it.copy(
                deleteTarget = null,
                message = if (removed) null else "Не удалось удалить навык «${skill.name}».",
            )
        }
    }

    /**
     * Plans and runs a skill with the supplied parameter values. The pre-flight gate reports the
     * first honest blocker (empty/broken plan, missing required parameter, accessibility off) without
     * touching the screen; otherwise the engine's handler is invoked and its rendered per-step report
     * is shown verbatim in the report dialog.
     */
    fun confirmRun(skill: Skill, values: Map<String, String>) {
        _uiState.update { it.copy(runTarget = null) }
        val plan = SkillPlanner.plan(skill, values)
        val unavailable = SkillLibraryLogic.unavailableReason(
            plan = plan,
            accessibilityEnabled = isAccessibilityEnabled(),
        )
        if (unavailable != null) {
            _uiState.update { it.copy(message = unavailable) }
            return
        }
        _uiState.update {
            it.copy(isRunning = true, runningSkillName = skill.name, message = null)
        }
        viewModelScope.launch {
            val result = runCatching {
                engine.handler().execute(runArguments(skill.id, values))
            }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { toolResult ->
                        state.copy(
                            isRunning = false,
                            runningSkillName = null,
                            report = SkillRunReport(skill.name, toolResult.success, toolResult.output),
                        )
                    },
                    onFailure = { error ->
                        state.copy(
                            isRunning = false,
                            runningSkillName = null,
                            message = "Ошибка запуска навыка: ${error.message ?: error::class.simpleName}",
                        )
                    },
                )
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean = AccessibilityAutomationService.isEnabled()

    /**
     * Builds the `skill_run` arguments the same way the agent would: `{ "id": ..., "values": {...} }`.
     * Values are only sent when non-empty, so a no-parameter skill produces the minimal call.
     */
    private fun runArguments(skillId: String, values: Map<String, String>): JsonObject {
        val fields = LinkedHashMap<String, JsonElement>()
        fields["id"] = JsonPrimitive(skillId)
        if (values.isNotEmpty()) {
            fields["values"] = JsonObject(values.mapValues { JsonPrimitive(it.value) })
        }
        return JsonObject(fields)
    }

    private companion object {
        const val TAG = "Andharness"
    }
}
