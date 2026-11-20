package com.andharness.ru.domain.skill

import com.andharness.ru.domain.serialization.arg
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

object SkillRecordingConverter {

    private val SUPPORTED_KEYS = setOf(
        "back", "назад", "home", "домой", "recents", "недавние",
        "notifications", "шторка", "quick_settings",
    )

    /**
     * Converts the recorded [actions] in order, stopping (honestly) once [SkillCodec.MAX_STEPS] steps
     * have been produced. Never throws: a malformed captured argument only makes that one action
     * skipped with a note.
     */
    fun convert(actions: List<RecordedSkillAction>): SkillConversion {
        val steps = ArrayList<SkillStep>()
        val converted = ArrayList<String>()
        val skipped = ArrayList<String>()
        actions.forEachIndexed { index, action ->
            val no = index + 1
            if (steps.size >= SkillCodec.MAX_STEPS) {
                skipped += "шаг $no (${action.tool}): достигнут лимит ${SkillCodec.MAX_STEPS} шагов — дальше пропущено"
                return@forEachIndexed
            }
            val args = parseArgs(action.argsJson)
            val outcome = if (args == null) {
                Conversion(null, "шаг $no (${action.tool}): некорректные аргументы записи — пропущено")
            } else {
                convertOne(no, action, args)
            }
            if (outcome.step != null) {
                steps += outcome.step
                converted += "${no}. ${outcome.note}"
            } else {
                skipped += outcome.note
            }
        }
        return SkillConversion(steps, converted, skipped)
    }

    /**
     * BUG-032 fix: a recording captures only `ui_*` actions, never the app launch, so a skill made
     * from it fails at step 1 when run from the «Навыки» library (the foreground app is Меха, not the
     * target). When the target package is known and the steps do not already begin with a
     * [SkillStep.LaunchApp], prepend one so the replay starts by bringing the app forward. A blank
     * package or an empty/already-launching step list is returned unchanged (no invented package), and
     * the result is still capped at [SkillCodec.MAX_STEPS].
     */
    fun withLaunchStep(steps: List<SkillStep>, appPackage: String?): List<SkillStep> {
        val pkg = appPackage?.takeIf { it.isNotBlank() } ?: return steps
        if (steps.isEmpty()) return steps
        if (steps.first() is SkillStep.LaunchApp) return steps
        return (listOf(SkillStep.LaunchApp(pkg)) + steps).take(SkillCodec.MAX_STEPS)
    }

    private data class Conversion(val step: SkillStep?, val note: String)

    private fun convertOne(no: Int, action: RecordedSkillAction, args: JsonObject): Conversion =
        when (action.tool) {
            "ui_tap" -> {
                val selector = selectorFor(args, textIsSelector = true, node = action.node)
                if (selector == null) {
                    Conversion(null, unexpressible(no, action, args))
                } else {
                    Conversion(SkillStep.Tap(selector), "тап по элементу (${describe(selector)})")
                }
            }

            "ui_type" -> {
                val text = args.arg("text")
                val selector = selectorFor(args, textIsSelector = false, node = action.node)
                when {
                    text.isNullOrBlank() ->
                        Conversion(null, "шаг $no (ui_type): пустой текст — пропущено")
                    selector == null ->
                        Conversion(null, unexpressible(no, action, args))
                    else ->
                        Conversion(SkillStep.Type(selector, text), "ввод «$text» в (${describe(selector)})")
                }
            }

            "key_event" -> {
                val key = args.arg("key") ?: args.arg("keyName")
                if (key != null && key.lowercase() in SUPPORTED_KEYS) {
                    Conversion(SkillStep.Key(key), "системная клавиша $key")
                } else {
                    Conversion(
                        null,
                        "шаг $no (key_event): клавиша «${key ?: "?"}» не поддерживается навыками " +
                            "(back|home|recents|notifications|quick_settings) — пропущено",
                    )
                }
            }

            "ui_scroll" -> {
                val selector = selectorFor(args, textIsSelector = true, node = action.node)
                if (selector == null) {
                    Conversion(
                        null,
                        "шаг $no (ui_scroll): контейнер не опознан (нет id/описания/текста) — " +
                            "прокрутка пропущена, семантического селектора нет",
                    )
                } else {
                    val direction = directionOf(args.arg("direction"))
                    Conversion(
                        SkillStep.Scroll(selector, direction, 1),
                        "прокрутка ${direction.name} (${describe(selector)})",
                    )
                }
            }

            "ui_long_press" ->
                Conversion(null, "шаг $no (ui_long_press): долгое нажатие не поддерживается навыками — пропущено")

            "ui_swipe" ->
                Conversion(null, "шаг $no (ui_swipe): свайп не поддерживается навыками — пропущен")

            "ui_gesture_multi" ->
                Conversion(null, "шаг $no (ui_gesture_multi): мультитач не поддерживается навыками — пропущен")

            else ->
                Conversion(null, "шаг $no (${action.tool}): действие не поддерживается навыками — пропущено")
        }

    /**
     * Honest note for an action that used raw coordinates (or had no semantic identity): coordinates
     * cannot be stored as a [SkillStep] because replaying them after an app update would hit the wrong
     * place, so the action is skipped instead of being faked into a selector.
     */
    private fun unexpressible(no: Int, action: RecordedSkillAction, args: JsonObject): String {
        val x = args.arg("x")
        val y = args.arg("y")
        val where = if (x != null && y != null) " (координаты $x,$y)" else ""
        return "шаг $no (${action.tool}):$where семантического селектора нет — пропущено, " +
            "чтобы не записывать действие по координатам"
    }

    /**
     * Builds a [SkillSelector] for a recorded action. An explicit selector from the recorded arguments
     * wins (it is exactly what the agent used, including the historic `text`-as-substring rule); when
     * there is none, the captured node's identifying fields are used with the resolver's priority
     * (`resourceId` -> `contentDescription` -> `text`). Returns `null` when neither yields identity.
     */
    private fun selectorFor(
        args: JsonObject,
        textIsSelector: Boolean,
        node: RecordedNode?,
    ): SkillSelector? {
        val exact = args.arg("exact")?.toBooleanStrictOrNull() ?: false
        val rawText = if (textIsSelector) args.arg("text") else args.arg("selectorText")
        val explicit = SkillSelector(
            text = if (exact) rawText else null,
            textContains = args.arg("textContains") ?: if (!exact) rawText else null,
            resourceId = args.arg("resourceId") ?: args.arg("id"),
            contentDescription = args.arg("contentDescription") ?: args.arg("desc"),
            index = args.arg("index")?.toIntOrNull()?.takeIf { it >= 1 },
        )
        if (explicit.hasIdentity()) return explicit

        if (node != null) {
            val resourceId = node.resourceId?.takeIf { it.isNotBlank() }
            val description = node.contentDescription?.takeIf { it.isNotBlank() }
            val fromNode = SkillSelector(
                text = if (resourceId == null && description == null) node.text?.takeIf { it.isNotBlank() } else null,
                resourceId = resourceId,
                contentDescription = if (resourceId == null) description else null,
            )
            if (fromNode.hasIdentity()) return fromNode
        }
        return null
    }

    private fun directionOf(raw: String?): SkillScrollDirection = when (raw?.lowercase()) {
        "up", "backward", "вверх" -> SkillScrollDirection.UP
        "left", "влево" -> SkillScrollDirection.LEFT
        "right", "вправо" -> SkillScrollDirection.RIGHT
        else -> SkillScrollDirection.DOWN
    }

    private fun describe(selector: SkillSelector): String = listOfNotNull(
        selector.resourceId?.takeIf { it.isNotBlank() }?.let { "id=«$it»" },
        selector.contentDescription?.takeIf { it.isNotBlank() }?.let { "desc=«$it»" },
        selector.text?.takeIf { it.isNotBlank() }?.let { "text=«$it»" },
        selector.textContains?.takeIf { it.isNotBlank() }?.let { "text~«$it»" },
        selector.index?.let { "index=$it" },
    ).joinToString(", ").ifBlank { "селектор" }

    private fun parseArgs(raw: String): JsonObject? = try {
        Json.parseToJsonElement(raw).jsonObject
    } catch (_: Exception) {
        null
    }
}
