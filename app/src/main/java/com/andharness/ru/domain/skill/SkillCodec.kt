package com.andharness.ru.domain.skill

import com.andharness.ru.domain.serialization.AppJson
import com.andharness.ru.domain.serialization.decodeOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * Pure encode/decode/validation logic for skills. Depends only on kotlinx.serialization, so it is
 * exercised on the JVM (see `SkillCodecTest`); the Android-bound store [com.andharness.ru.data.skill.SkillStore]
 * only adds file I/O on top.
 *
 * Validation is intentionally strict and *honest*: the `skill_save` tool must tell the agent exactly
 * which step/parameter is wrong instead of storing a broken skill that would fail at replay time.
 *
 * kotlinx.serialization Json reference: https://kotlinlang.org/api/kotlinx.serialization/
 */
object SkillCodec {

    /** Hard cap on stored skills; the oldest is dropped when a new one is saved. */
    const val MAX_SKILLS = 200

    /** Hard cap on steps per skill (matches the UI-macro recorder's cap). */
    const val MAX_STEPS = 100

    /** Hard cap on declared parameters per skill. */
    const val MAX_PARAMETERS = 32

    private val json = AppJson.LenientEncodeDefaults

    private val pretty = AppJson.LenientEncodeDefaultsPretty

    fun encodeSkills(skills: List<Skill>): String = json.encodeToString(skills)

    fun encodeSkill(skill: Skill): String = json.encodeToString(skill)

    /** Pretty form used by `skill_get` so a human/agent can read the stored definition. */
    fun prettySkill(skill: Skill): String = pretty.encodeToString(skill)

    /**
     * Decodes the persisted skill list. A corrupt/foreign/missing blob degrades to an empty list
     * (same policy as `UiMacroCodec`), records without an id/name are dropped, and oversized data is
     * clamped so a hand-edited file can never break the tools.
     */
    fun decodeSkills(raw: String?): List<Skill> =
        json.decodeOrNull<List<Skill>>(raw)
            ?.filter { it.id.isNotBlank() && it.name.isNotBlank() }
            ?.map {
                it.copy(
                    steps = it.steps.take(MAX_STEPS),
                    parameters = it.parameters.take(MAX_PARAMETERS),
                )
            }
            ?.takeLast(MAX_SKILLS)
            ?: emptyList()

    /**
     * Parses the `steps` **tool argument**, tolerating the shapes a model actually produces. The tool
     * schema asks for a JSON **string**, but capable providers (and the app's own `ui_*` tools, whose
     * arguments are read string-or-value) may send a real JSON **array**; a model may also wrap the
     * JSON in a ``` code fence. All three are accepted and normalized here, so a valid skill is never
     * silently stored with zero steps nor rejected for a cosmetic shape. An absent/blank/null value
     * means "no steps yet".
     */
    fun parseStepsArg(element: JsonElement?): SkillParseResult<List<SkillStep>> = when (element) {
        null, is JsonNull -> SkillParseResult.Ok(emptyList())
        is JsonPrimitive -> parseSteps(element.content)
        else -> parseStepsElement(element)
    }

    /**
     * Parses the `parameters` **tool argument**, tolerating the same shapes as [parseStepsArg]
     * (JSON string, real JSON array, or a fenced JSON string).
     */
    fun parseParametersArg(element: JsonElement?): SkillParseResult<List<SkillParameter>> = when (element) {
        null, is JsonNull -> SkillParseResult.Ok(emptyList())
        is JsonPrimitive -> parseParameters(element.content)
        else -> parseParametersElement(element)
    }

    /**
     * Parses the `steps` value as text: a JSON array of step objects, each a tagged `SkillStep`
     * (`{"type":"tap","selector":{...}}`). A blank/absent value means "no steps yet"; a surrounding
     * ``` code fence is stripped before parsing.
     */
    fun parseSteps(raw: String?): SkillParseResult<List<SkillStep>> {
        val text = stripCodeFence(raw)
        if (text.isEmpty()) return SkillParseResult.Ok(emptyList())
        val element = parseElement(text) ?: return SkillParseResult.Err(
            "steps: не удалось разобрать JSON. Ожидается массив шагов, например " +
                "[{\"type\":\"launch_app\",\"packageName\":\"com.android.chrome\"}]."
        )
        return parseStepsElement(element)
    }

    private fun parseStepsElement(element: JsonElement): SkillParseResult<List<SkillStep>> {
        if (element !is JsonArray) {
            return SkillParseResult.Err("steps: ожидался JSON-массив шагов, а не ${describe(element)}.")
        }
        if (element.size > MAX_STEPS) {
            return SkillParseResult.Err("steps: слишком много шагов (${element.size}); максимум $MAX_STEPS.")
        }
        val steps = ArrayList<SkillStep>(element.size)
        element.forEachIndexed { index, stepElement ->
            val step = try {
                json.decodeFromJsonElement<SkillStep>(normalizeStep(stepElement))
            } catch (e: Exception) {
                return SkillParseResult.Err(
                    "steps: шаг #${index + 1} не распознан (${e.message ?: "неизвестный тип"}). " +
                        "Доступные type: tap, type, wait_for_text, scroll, key, launch_app, delay, assert_text, open_url."
                )
            }
            validateStep(index, step)?.let { return SkillParseResult.Err(it) }
            steps += step
        }
        return SkillParseResult.Ok(steps)
    }

    /**
     * Parses the `parameters` value as text: a JSON array of parameter objects
     * (`{"name":"адрес","type":"TEXT","required":true}`). A blank/absent value means "no parameters";
     * a surrounding ``` code fence is stripped before parsing.
     */
    fun parseParameters(raw: String?): SkillParseResult<List<SkillParameter>> {
        val text = stripCodeFence(raw)
        if (text.isEmpty()) return SkillParseResult.Ok(emptyList())
        val element = parseElement(text) ?: return SkillParseResult.Err(
            "parameters: не удалось разобрать JSON. Ожидается массив параметров, например " +
                "[{\"name\":\"адрес\",\"type\":\"TEXT\",\"required\":true}]."
        )
        return parseParametersElement(element)
    }

    private fun parseParametersElement(element: JsonElement): SkillParseResult<List<SkillParameter>> {
        if (element !is JsonArray) {
            return SkillParseResult.Err("parameters: ожидался JSON-массив параметров, а не ${describe(element)}.")
        }
        if (element.size > MAX_PARAMETERS) {
            return SkillParseResult.Err(
                "parameters: слишком много параметров (${element.size}); максимум $MAX_PARAMETERS."
            )
        }
        val parameters = ArrayList<SkillParameter>(element.size)
        val seen = LinkedHashSet<String>()
        element.forEachIndexed { index, parameterElement ->
            val parameter = try {
                json.decodeFromJsonElement<SkillParameter>(parameterElement)
            } catch (e: Exception) {
                return SkillParseResult.Err(
                    "parameters: параметр #${index + 1} не распознан (${e.message ?: "неверный формат"}). " +
                        "type должен быть TEXT, NUMBER или CHOICE."
                )
            }
            if (parameter.name.isBlank()) {
                return SkillParseResult.Err("parameters: у параметра #${index + 1} пустое имя (name).")
            }
            if (!seen.add(parameter.name)) {
                return SkillParseResult.Err("parameters: имя «${parameter.name}» повторяется.")
            }
            if (parameter.type == SkillParameterType.CHOICE && parameter.choices.isEmpty()) {
                return SkillParseResult.Err(
                    "parameters: у CHOICE-параметра «${parameter.name}» пустой список choices."
                )
            }
            parameters += parameter
        }
        return SkillParseResult.Ok(parameters)
    }

    /** Index of a skill matching [idOrName] (id first, then exact, then case-insensitive name). */
    fun find(skills: List<Skill>, idOrName: String?): Skill? {
        val query = idOrName?.trim().orEmpty()
        if (query.isEmpty()) return null
        return skills.firstOrNull { it.id == query }
            ?: skills.firstOrNull { it.name == query }
            ?: skills.firstOrNull { it.name.equals(query, ignoreCase = true) }
    }

    /**
     * Outcome of one skill inside an imported blob: the decoded [skill], or an honest [error] when it
     * could not be decoded/validated. [name] is a best-effort label for the report.
     */
    data class SkillImportItem(val name: String, val skill: Skill?, val error: String?)

    /** Whole-blob import parse outcome: per-item reports, or a single honest error. */
    sealed interface ImportParseResult {
        data class Ok(val items: List<SkillImportItem>) : ImportParseResult
        data class Err(val message: String) : ImportParseResult
    }

    /**
     * Parses an import blob produced by [encodeSkill]/[encodeSkills]: a single skill object or an
     * array of them. Unlike [decodeSkills] (which silently drops anything bad for file robustness),
     * this reports **per item** what was accepted and what was rejected, so `skill_import` can be
     * honest about a foreign blob without silently losing skills.
     */
    fun parseImport(raw: String?): ImportParseResult {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) {
            return ImportParseResult.Err("json: пустая строка — импортировать нечего.")
        }
        val element = parseElement(text)
            ?: return ImportParseResult.Err("json: не удалось разобрать JSON. Ожидается объект навыка или массив объектов.")
        val elements: List<JsonElement> = when (element) {
            is JsonArray -> element
            is JsonObject -> listOf(element)
            else -> return ImportParseResult.Err("json: ожидался объект навыка или массив объектов, а не ${describe(element)}.")
        }
        val items = ArrayList<SkillImportItem>(elements.size)
        elements.forEachIndexed { index, itemElement ->
            val fallbackName = rawName(itemElement) ?: "(элемент #${index + 1})"
            val skill = try {
                json.decodeFromJsonElement<Skill>(itemElement)
            } catch (e: Exception) {
                items += SkillImportItem(
                    fallbackName,
                    null,
                    "не распознан как навык (${e.message ?: "неверный формат"}).",
                )
                return@forEachIndexed
            }
            val problem = validateSkill(skill)
            if (problem != null) {
                items += SkillImportItem(skill.name.ifBlank { fallbackName }, null, problem)
            } else {
                items += SkillImportItem(
                    skill.name,
                    skill.copy(
                        steps = skill.steps.take(MAX_STEPS),
                        parameters = skill.parameters.take(MAX_PARAMETERS),
                    ),
                    null,
                )
            }
        }
        return ImportParseResult.Ok(items)
    }

    /**
     * Best-effort raw `name` of an import element, read straight from the JSON before decoding, so a
     * rejected item is still reported by its name instead of an anonymous index.
     */
    private fun rawName(element: JsonElement): String? =
        (element as? JsonObject)
            ?.get("name")
            ?.let { it as? JsonPrimitive }
            ?.content
            ?.takeIf { it.isNotBlank() }

    /**
     * Validates a decoded [skill]; returns the first problem (Russian) or `null` when it is a valid,
     * runnable definition. Used by `skill_import` so a foreign skill is checked with the same rules as
     * `skill_save` before it enters the library.
     */
    fun validateSkill(skill: Skill): String? {
        if (skill.id.isBlank()) return "у навыка пустой id — импорт невозможен."
        if (skill.name.isBlank()) return "у навыка пустое имя (name) — импорт невозможен."
        if (skill.steps.size > MAX_STEPS) return "в навыке больше $MAX_STEPS шагов."
        if (skill.parameters.size > MAX_PARAMETERS) return "в навыке больше $MAX_PARAMETERS параметров."
        skill.steps.forEachIndexed { index, step -> validateStep(index, step)?.let { return it } }
        return null
    }

    private fun validateStep(index: Int, step: SkillStep): String? {        val where = "steps: шаг #${index + 1}"
        fun selectorError(selector: SkillSelector): String? = when {
            selector.isEmpty() -> "$where: селектор пуст — укажи хотя бы text, textContains, resourceId или contentDescription."
            !selector.hasIdentity() -> "$where: в селекторе нет опознающего поля (text/textContains/resourceId/contentDescription)."
            else -> null
        }

        return when (step) {
            is SkillStep.Tap -> selectorError(step.selector)
            is SkillStep.Type -> selectorError(step.selector)
                ?: if (step.textTemplate.isBlank()) "$where: пустой textTemplate." else null
            is SkillStep.WaitForText -> selectorError(step.selector)
                ?: if (step.timeoutMs <= 0L) "$where: timeoutMs должен быть больше нуля." else null
            is SkillStep.Scroll -> selectorError(step.selector)
                ?: if (step.amount <= 0) "$where: amount должен быть больше нуля." else null
            is SkillStep.Key -> if (step.keyName.isBlank()) "$where: пустой keyName." else null
            is SkillStep.LaunchApp -> if (step.packageName.isBlank()) "$where: пустой packageName." else null
            is SkillStep.Delay -> if (step.ms <= 0L) "$where: ms должен быть больше нуля." else null
            is SkillStep.AssertText -> selectorError(step.selector)
                ?: if (step.expected.isBlank()) "$where: пустой expected." else null
            is SkillStep.OpenUrl -> if (step.urlTemplate.isBlank()) "$where: пустой urlTemplate." else null
        }
    }

    private fun parseElement(text: String): JsonElement? = try {
        Json.parseToJsonElement(text)
    } catch (_: Exception) {
        null
    }

    /**
     * Strips a single surrounding Markdown code fence (```` ```json ... ``` ````), which a model may
     * wrap around the JSON it puts in the `steps`/`parameters` string argument. A value without a
     * fence is returned trimmed and unchanged. Mirrors `domain/vision/PhotoAnalysis`'s fence strip.
     */
    private fun stripCodeFence(raw: String?): String {
        val text = raw?.trim().orEmpty()
        val fenced = CODE_FENCE.matchEntire(text) ?: return text
        return fenced.groupValues[1].trim()
    }

    /**
     * Tolerant step normalization applied before decoding. A model often writes the selector fields
     * **flat** on the step (`{"type":"tap","text":"..."}`), copying the shape of the `ui_*` tools,
     * whereas a skill step expects them nested under `selector`. When `selector` is absent but any
     * known selector key is present, those keys are folded into a `selector` object (the aliases
     * `id`/`desc` match `SkillRecordingConverter`). Unknown keys are ignored by the decoder, so this
     * only rescues a step that would otherwise fail with "Field 'selector' is required".
     */
    private fun normalizeStep(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        if (obj.containsKey("selector")) return element
        val selector = buildJsonObject {
            FLAT_SELECTOR_KEYS.forEach { (from, to) ->
                (obj[from] as? JsonPrimitive)?.let { put(to, it) }
            }
        }
        if (selector.isEmpty()) return element
        return JsonObject(obj + ("selector" to selector))
    }

    private fun describe(element: JsonElement): String = when (element) {
        is JsonArray -> "массив"
        is kotlinx.serialization.json.JsonObject -> "объект"
        is kotlinx.serialization.json.JsonPrimitive -> "значение"
        else -> "неожиданный JSON"
    }

    /** A single surrounding Markdown code fence: ```` ```lang\n...\n``` ````. */
    private val CODE_FENCE = Regex("^```[^\\n]*\\n([\\s\\S]*?)\\n?```$")

    /** Flat step-level keys folded into `selector`; aliases mirror `ui_*`/recorded-skill arguments. */
    private val FLAT_SELECTOR_KEYS: Map<String, String> = mapOf(
        "text" to "text",
        "textContains" to "textContains",
        "resourceId" to "resourceId",
        "id" to "resourceId",
        "contentDescription" to "contentDescription",
        "desc" to "contentDescription",
        "className" to "className",
        "index" to "index",
    )
}
