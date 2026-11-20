package com.andharness.ru.data.agent

import com.andharness.ru.domain.style.AddressForm
import com.andharness.ru.domain.style.ProfanityLexicon
import com.andharness.ru.domain.style.StyleActivation
import com.andharness.ru.domain.style.StyleMode
import com.andharness.ru.domain.style.StyleProfile
import com.andharness.ru.domain.style.StyleTrait
import com.andharness.ru.domain.style.StyleTraitKey

/**
 * Renders the learned style profile into a compact runtime-tail block.
 *
 * The block is injected into the volatile tail (never the byte-stable static system prompt), is
 * hard-capped, and always states the hierarchy: style changes FORM only, while facts, tools and
 * safety rules win. [StyleMode.NEUTRAL] renders nothing; [StyleMode.MANUAL] renders the user's card;
 * [StyleMode.AUTO] renders at most three active traits plus a short tone card.
 *
 * Pure and Android-free, so levels, caps and the never-mirror guards are JVM-tested.
 */
object StyleBlockBuilder {

    /** Hard cap of the whole block, in characters (~90 tokens). */
    const val MAX_BLOCK_CHARS = 360

    private const val HEADER = "СТИЛЬ (только форма; факты и безопасность выше):"

    private const val FOOTER = "Другим людям пиши нейтрально; стиль не переноси в SMS, письма и уведомления."

    private const val PROFANITY_LIMITS =
        "Мат: максимум 1 слово за ответ, только как усилитель, не в адрес людей; без оскорблений и агрессии."

    /** Renders the block for [profile]; `""` means "inject nothing". */
    fun render(profile: StyleProfile, limit: Int = MAX_BLOCK_CHARS): String {
        if (limit <= 0) return ""
        return when (profile.mode) {
            StyleMode.NEUTRAL -> ""
            StyleMode.MANUAL -> renderManual(profile.manualCard, limit)
            StyleMode.AUTO -> renderAuto(profile, limit)
        }
    }

    private fun renderManual(card: String?, limit: Int): String {
        val clean = card?.trim().orEmpty()
        if (clean.isEmpty()) return ""
        // A user could paste a slur into the manual card; never feed that back into the prompt.
        if (ProfanityLexicon.containsHardLanguage(clean)) return ""
        return fit(optional = listOf(clean), mandatory = listOf(FOOTER), limit = limit)
    }

    private fun renderAuto(profile: StyleProfile, limit: Int): String {
        val active = StyleActivation.activeTraits(profile, limit = 4)
            .filter { profile.profanityEnabled || it.key != StyleTraitKey.PROFANITY }
            .take(3)
        val card = profile.card?.trim()?.takeIf { it.isNotEmpty() }
            ?.takeIf { !ProfanityLexicon.containsHardLanguage(it) }
        if (active.isEmpty() && card == null) return ""

        val optional = ArrayList<String>(active.size + 1)
        active.forEach { trait -> line(trait)?.let { optional += it } }
        if (card != null) optional += "Тон: $card"
        // Safety lines are mandatory: they are reserved out of the cap first, so a long card or
        // three traits can never push the "no insults / one word / neutral to others" limits out.
        val mandatory = buildList {
            add(FOOTER)
            if (active.any { it.key == StyleTraitKey.PROFANITY }) add(PROFANITY_LIMITS)
        }
        return fit(optional = optional, mandatory = mandatory, limit = limit)
    }

    private fun line(trait: StyleTrait): String? = when (trait.key) {
        StyleTraitKey.ADDRESS -> when (StyleActivation.addressForm(trait)) {
            AddressForm.TY -> "Обращайся на «ты», по-дружески, без официоза."
            AddressForm.VY -> "Обращайся на «вы», вежливо и без панибратства."
        }

        StyleTraitKey.BREVITY -> "Пиши коротко: 1–3 предложения, без длинных вступлений."
        StyleTraitKey.PROFANITY -> "Пользователь сам крепко выражается — можно изредка и к месту."
        StyleTraitKey.SLANG -> "Уместен лёгкий сленг, по-дружески, без переигрывания."
        StyleTraitKey.EMOJI -> "Эмодзи — изредка и к месту."
        StyleTraitKey.PUNCTUATION -> "Можно расслабленно: строчные буквы, без педантичной пунктуации."
        StyleTraitKey.DIRECTNESS -> "Сразу по делу, без лишних предисловий."
        StyleTraitKey.HUMOR -> "Уместна лёгкая ирония, но никогда не над пользователем."
    }

    /**
     * Joins the header, as many optional lines as fit alongside the mandatory safety lines, then
     * the mandatory lines themselves. Truncation only ever hits the tail of a mandatory line when
     * the cap is absurdly small, never drops a safety rule at a normal cap.
     */
    private fun fit(optional: List<String>, mandatory: List<String>, limit: Int): String {
        val mandatoryText = mandatory.joinToString("") { "\n- $it" }
        val sb = StringBuilder(HEADER)
        val optionalBudget = (limit - mandatoryText.length).coerceAtLeast(0)
        for (line in optional) {
            val candidate = "\n- $line"
            if (sb.length + candidate.length > optionalBudget) break
            sb.append(candidate)
        }
        sb.append(mandatoryText)
        return if (sb.length > limit) sb.substring(0, limit) else sb.toString()
    }
}
