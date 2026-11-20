package com.andharness.ru.data.tools.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.andharness.ru.data.tools.person.PersonReadSupport
import com.andharness.ru.data.tools.str
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.assist.AssistRepository
import com.andharness.ru.domain.assist.overlay.OverlayForeignWindow
import com.andharness.ru.domain.assist.overlay.OverlayWindowCandidate
import com.andharness.ru.domain.permission.SpecialAccess
import com.andharness.ru.domain.person.InteractionRepository
import com.andharness.ru.domain.person.PersonAliasRepository
import com.andharness.ru.domain.person.PersonIdentityRepository
import com.andharness.ru.domain.person.PersonRepository
import com.andharness.ru.domain.person.PersonResolver
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.service.AccessibilityAutomationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * `remember_screen`: reads the visible text of the CURRENT screen and saves it to long-term memory.
 *
 * This is the overlay quick-ask use case («запомни экран»): the user summons Меха over another app,
 * so `getRootInActiveWindow()` may be our own focusable overlay window. The app underneath is found
 * with the same honesty rule the overlay context reader uses: a foreign active root first, otherwise
 * `AccessibilityService.getWindows()` is enumerated and the topmost readable foreign application
 * window is picked by the pure, unit-tested [OverlayForeignWindow.pickIndex] (active → focused →
 * layer), never by type alone (an app overlay reports as `TYPE_APPLICATION`, so our own bubble/panel
 * could otherwise be mistaken for the foreground app — BUG-038). Our own package is always skipped.
 *
 * The tool is read-only on the device: it never taps, swipes or types. Text is flattened by the pure
 * [ScreenRememberSupport] (trim, drop blanks and consecutive duplicates, keep order, bound to
 * `max_chars`) and, when `save=true` (the default), stored as a memory note carrying the
 * `remember_screen` and `screen:<package>` tags. An optional `person` is resolved through the
 * existing [PersonReadSupport] resolver — «never guess»: an ambiguous/unknown name is reported and
 * the note is still saved, without inventing a `person:<id>` link.
 *
 * Honest failures: accessibility off, no readable foreign window, or a screen with no text all
 * produce an explicit message instead of a fabricated memory.
 *
 * References (official Android docs):
 * - `AccessibilityService.getRootInActiveWindow()` —
 *   https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getRootInActiveWindow()
 * - `AccessibilityService.getWindows()` —
 *   https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getWindows()
 * - `AccessibilityWindowInfo.getRoot()/getType()/isActive()/isFocused()/getLayer()` —
 *   https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo
 */
internal class RememberScreenToolHandler(
    private val context: Context,
    private val assistRepository: AssistRepository,
    private val personRepository: PersonRepository,
    private val personIdentityRepository: PersonIdentityRepository,
    private val personAliasRepository: PersonAliasRepository,
    private val interactionRepository: InteractionRepository,
) : ToolHandler {

    override val name = "remember_screen"

    /** Built lazily: resolving a person is only needed when `person` is actually supplied. */
    private val readSupport: PersonReadSupport by lazy {
        PersonReadSupport(
            personRepository,
            personIdentityRepository,
            personAliasRepository,
            interactionRepository,
        )
    }

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        val service = AccessibilityAutomationService.instance
            ?: return@withContext ToolResult(false, SpecialAccess.required(SpecialAccess.ACCESSIBILITY))
        try {
            val maxChars = ScreenRememberSupport.parseMaxChars(arguments.str("max_chars"))
            val root = foreignRoot(service)
                ?: return@withContext ToolResult(
                    false,
                    "Не нашёл читаемого окна приложения на экране (возможно, видно только окно Меха)."
                )
            val packageName = root.packageName?.toString()?.takeIf { it.isNotBlank() }
            val captured = ScreenRememberSupport.flatten(
                AccessibilityTree.collectTexts(root, MAX_NODES),
                maxChars,
            )
            if (captured.text.isEmpty()) {
                return@withContext ToolResult(
                    false,
                    "На экране нет читаемого текста — запоминать нечего (спецвозможности включены, " +
                        "но окно не отдаёт текст)."
                )
            }

            val save = arguments.str("save")?.toBooleanStrictOrNull() ?: true
            val note = arguments.str("note")
            val personRaw = arguments.str("person")
            val tagsRaw = arguments.str("tags")

            var savedId: Long? = null
            var personNote = ""
            if (save) {
                withContext(Dispatchers.IO) {
                    val person = personRaw?.let { resolvePerson(it) }
                    if (personRaw != null) {
                        personNote = if (person != null) {
                            " Человек: ${person.label} (#${person.id})."
                        } else {
                            " Человек «$personRaw» не распознан однозначно — запись сохранена без привязки."
                        }
                    }
                    val title = listOfNotNull(
                        note?.trim()?.takeIf { it.isNotEmpty() },
                        person?.label,
                    ).joinToString(", ").takeIf { it.isNotEmpty() }
                    val text = ScreenRememberSupport.buildNoteText(title, captured.text)
                    val tags = ScreenRememberSupport.buildTags(tagsRaw, packageName, person?.id)
                    savedId = runCatching { assistRepository.saveMemory(text, tags, importance = 0) }
                        .getOrNull()?.takeIf { it > 0L }
                }
            }

            val header = buildString {
                append(if (save) "Экран запомнен" else "Экран прочитан")
                savedId?.let { append(" #").append(it) }
                packageName?.let { append(" (").append(it).append(')') }
                append('.')
                if (captured.truncated) {
                    append(" Текст усечён до ").append(maxChars).append(" символов.")
                }
                if (save && savedId == null) {
                    append(" Не удалось сохранить в память — экран только прочитан.")
                }
                append(personNote)
            }
            ToolResult(true, header + "\n" + captured.text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(false, "Ошибка чтения экрана: ${e.message}")
        }
    }

    /**
     * Root of the app that is actually on screen, never our own overlay window. The foreign active
     * root is used first because it is exact; once our focusable quick-ask panel owns
     * `getRootInActiveWindow()`, the app underneath is found by enumerating the interactive windows
     * and applying the pure [OverlayForeignWindow.pickIndex] rule.
     */
    private fun foreignRoot(service: AccessibilityService): AccessibilityNodeInfo? {
        val ownPackage = context.packageName
        val active = service.rootInActiveWindow
        if (active != null && OverlayForeignWindow.isForeign(active.packageName?.toString(), ownPackage)) {
            return active
        }
        val windows = try {
            service.windows
        } catch (e: Exception) {
            null
        } ?: return null
        val roots = windows.map { window -> try { window.root } catch (e: Exception) { null } }
        val candidates = windows.indices.map { index ->
            OverlayWindowCandidate(
                type = windows[index].type,
                packageName = roots[index]?.packageName?.toString(),
                hasRoot = roots[index] != null,
                active = windows[index].isActive,
                focused = windows[index].isFocused,
                layer = windows[index].layer,
            )
        }
        val index = OverlayForeignWindow.pickIndex(candidates, ownPackage) ?: return null
        return roots[index]
    }

    /**
     * Resolves the optional `person` argument: a numeric id is used verbatim, a free-text name goes
     * through the shared [PersonReadSupport] resolver and is accepted only on an unambiguous
     * [PersonResolver.ResolveOutcome.RESOLVED] — otherwise `null`, so no id is guessed.
     */
    private suspend fun resolvePerson(raw: String): PersonRef? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        trimmed.toLongOrNull()?.let { return PersonRef(it, "персона #$it") }
        val result = runCatching { readSupport.resolveQuery(trimmed, null) }.getOrNull() ?: return null
        if (result.outcome != PersonResolver.ResolveOutcome.RESOLVED) return null
        val best = result.best ?: return null
        return PersonRef(best.person.id, best.person.displayName)
    }

    /** A resolved person: the id used for the `person:<id>` tag and a human label for the note. */
    private data class PersonRef(val id: Long, val label: String)

    private companion object {
        /** Upper bound on accessibility nodes read for the screen text (bounded, main-thread read). */
        const val MAX_NODES = 300
    }
}
