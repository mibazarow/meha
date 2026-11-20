package com.andharness.ru.domain.retrieval

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Shared pure-Kotlin retrieval primitive (update.md idea #3), reused by tool retrieval (idea #1)
 * and by long-term memory recall. Zero dependencies, works offline on any phone:
 *
 * - [tokenize] normalises Russian and English text: lower-case, split on non-letters/digits, drop
 *   stop-words, apply light suffix stripping, add a short-prefix token (tolerant to RU inflection:
 *   "налоговая"/"налоговой" both collapse to the same prefix) and expand a synonym/transliteration
 *   map ("смс"↔"sms", "налоговая"↔"фнс", "деньги"↔"₽", "фото"↔"photo", "машина"↔"авто", …).
 * - [bm25] ranks documents with Okapi BM25 over the expanded token bags.
 *
 * Honest scope: this lexical+synonym layer IS the shipped "semantic" mechanism — no model, no
 * download. A real embedding model is an OPTIONAL reranker behind the [Embedder] hook ([fuse]);
 * the default is [NoOpEmbedder], so shipped behaviour never depends on a model being present.
 */
object TextRetriever {

    const val MIN_TOKEN_LENGTH = 2
    const val PREFIX_LENGTH = 4
    const val BM25_K1 = 1.2
    const val BM25_B = 0.75

    /** Private-use marker so a prefix token can never collide with a real word. */
    internal const val PREFIX_MARK = "\u0001p"

    /**
     * One document to rank: [id] is opaque to the retriever, [tokens] come from [tokenize]. Tokens in
     * [boostedTokens] (e.g. explicit tags) are counted with [boost] times the term frequency and are
     * part of the document length, so a field can be weighted without duplicating text.
     */
    data class Doc(
        val id: String,
        val tokens: List<String>,
        val boostedTokens: List<String> = emptyList(),
        val boost: Double = 2.0
    )

    /** A scored document; only documents with a positive score are returned. */
    data class Hit(val id: String, val score: Double)

    /**
     * Splits, normalises, drops stop-words, expands cross-script synonyms and adds a short-prefix
     * token. The prefix token makes the match tolerant to Russian inflection, which is what keeps
     * recall usable without a full morphological analyser.
     */
    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        for (raw in preNormalize(text).lowercase().split(TOKEN_SPLIT)) {
            if (raw.length < MIN_TOKEN_LENGTH || raw in STOPWORDS) continue
            val stemmed = stem(raw)
            if (stemmed.length >= MIN_TOKEN_LENGTH && stemmed !in STOPWORDS) {
                out += stemmed
                if (stemmed.length >= PREFIX_LENGTH) out += PREFIX_MARK + stemmed.take(PREFIX_LENGTH)
            }
            SYNONYMS[raw]?.let { out += it }
            if (stemmed != raw) SYNONYMS[stemmed]?.let { out += it }
        }
        return out
    }

    /** Stem-only normalisation (no synonyms, no prefix token) for callers that build weighted bags. */
    fun normalize(token: String): String {
        val lower = token.lowercase()
        return if (lower.length < MIN_TOKEN_LENGTH) lower else stem(lower)
    }

    /** BM25 over [docs] for [query]; returns up to [limit] positive-scoring hits, best first. */
    fun bm25(query: String, docs: List<Doc>, limit: Int = Int.MAX_VALUE): List<Hit> =
        bm25Tokens(tokenize(query).distinct(), docs, limit)

    /** BM25 with pre-tokenised query terms (already expanded/weighted by the caller). */
    fun bm25Tokens(queryTokens: List<String>, docs: List<Doc>, limit: Int = Int.MAX_VALUE): List<Hit> {
        if (queryTokens.isEmpty() || docs.isEmpty()) return emptyList()

        val df = HashMap<String, Int>()
        for (doc in docs) {
            for (term in (doc.tokens + doc.boostedTokens).toHashSet()) df.merge(term, 1) { a, b -> a + b }
        }
        val total = docs.size.toDouble()
        val avgLength = docs.map { it.tokens.size + it.boostedTokens.size }.average().coerceAtLeast(1.0)

        val hits = ArrayList<Hit>(docs.size)
        for (doc in docs) {
            val tf = HashMap<String, Double>()
            for (term in doc.tokens) tf.merge(term, 1.0) { a, b -> a + b }
            for (term in doc.boostedTokens) tf.merge(term, doc.boost) { a, b -> a + b }
            val docLength = (doc.tokens.size + doc.boostedTokens.size).coerceAtLeast(1)
            var score = 0.0
            for (term in queryTokens) {
                val freq = tf[term] ?: continue
                val docFreq = df[term] ?: continue
                val idf = ln(1.0 + (total - docFreq + 0.5) / (docFreq + 0.5))
                val norm = freq * (BM25_K1 + 1) /
                    (freq + BM25_K1 * (1 - BM25_B + BM25_B * docLength / avgLength))
                score += idf * norm
            }
            // No score threshold: a single overlapping (possibly synonym/inflection) term is a real,
            // if weak, match and is ranked low rather than discarded.
            if (score > 0.0) hits += Hit(doc.id, score)
        }
        return hits.sortedByDescending { it.score }.take(limit)
    }

    // ---------------------------------------------------------------- optional embeddings hook

    /**
     * OPTIONAL embeddings extension point. The shipped retrieval is lexical+synonyms (see above);
     * plugging in an on-device model (e.g. MediaPipe Text Embedder or a small ONNX/TFLite model) is
     * a later, isolated change: implement this interface, pass it to [fuse], and nothing else moves.
     * No model dependency is bundled today — deliberately.
     */
    fun interface Embedder {
        /** Returns an embedding for [text], or null when the model is unavailable/cannot embed. */
        fun embed(text: String): FloatArray?
    }

    /** Default [Embedder]: always unavailable, so [fuse] keeps the pure lexical ranking. */
    object NoOpEmbedder : Embedder {
        override fun embed(text: String): FloatArray? = null
    }

    /** Cosine similarity in [-1, 1]; 0.0 for missing/degenerate vectors. */
    fun cosine(a: FloatArray?, b: FloatArray?): Double {
        if (a == null || b == null || a.isEmpty() || a.size != b.size) return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na <= 0.0 || nb <= 0.0) return 0.0
        return dot / (sqrt(na) * sqrt(nb))
    }

    /**
     * Fuses a lexical ranking with embedding similarity: `lexicalWeight * lexicalScore +
     * embeddingWeight * cosine`. [queryVector] and [docVectors] are keyed by [Hit.id]; whenever the
     * query vector is null (e.g. [NoOpEmbedder]) OR a document has no vector, the lexical order is
     * preserved untouched — the fallback is never worse than today's behaviour.
     */
    fun fuse(
        lexical: List<Hit>,
        queryVector: FloatArray?,
        docVectors: Map<String, FloatArray?>,
        lexicalWeight: Double = 1.0,
        embeddingWeight: Double = 0.6
    ): List<Hit> {
        if (queryVector == null || lexical.isEmpty()) return lexical
        if (docVectors.values.all { it == null }) return lexical
        return lexical
            .map { hit ->
                Hit(
                    hit.id,
                    lexicalWeight * hit.score + embeddingWeight * cosine(queryVector, docVectors[hit.id])
                )
            }
            .sortedByDescending { it.score }
    }

    // ---------------------------------------------------------------- normalisation internals

    /** Currency/typographic symbols that carry meaning but never survive the letter/digit split. */
    private val SYMBOL_WORDS: List<Pair<String, String>> = listOf(
        "₽" to " рубль ",
        "$" to " доллар ",
        "€" to " евро ",
        "£" to " фунт "
    )

    private fun preNormalize(text: String): String {
        var out = text
        for ((symbol, word) in SYMBOL_WORDS) if (out.contains(symbol)) out = out.replace(symbol, word)
        return out
    }

    private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{N}]+")

    // Longest-first so the longest matching ending wins (e.g. "ировать" before "ать").
    private val RU_SUFFIXES: List<String> = listOf(
        "ированиями", "ованиями",
        "ающий", "ующий", "ивший", "ывший", "аться", "иться", "еться",
        "ениями", "ениях", "ениям", "ение", "ении", "ений", "ением", "ения", "ению",
        "ами", "ями", "ого", "его", "ому", "ему", "ыми", "ими", "ых", "их",
        "ая", "яя", "ое", "ее", "ые", "ие", "ой", "ей", "ий", "ый",
        "ом", "ем", "ам", "ям", "ах", "ях", "ов", "ев", "ию", "ия", "ии",
        "ать", "ять", "ить", "еть", "ыть", "уть", "ешь", "ишь", "ует", "ает", "ист",
        "ся", "сь", "а", "я", "е", "о", "ы", "и", "й", "ь", "у", "ю"
    ).sortedByDescending { it.length }

    private val EN_SUFFIXES: List<String> = listOf(
        "ations", "ation", "ings", "ing", "ies", "ied", "es", "ed", "ly", "s"
    ).sortedByDescending { it.length }

    private fun stem(token: String): String {
        if (token.length <= 3) return token
        val isCyrillic = token.any { it in '\u0400'..'\u04FF' }
        val suffixes = if (isCyrillic) RU_SUFFIXES else EN_SUFFIXES
        for (suffix in suffixes) {
            if (token.length - suffix.length >= 3 && token.endsWith(suffix)) {
                return token.dropLast(suffix.length)
            }
        }
        return token
    }

    private val STOPWORDS: Set<String> = setOf(
        // Russian
        "и", "а", "но", "же", "бы", "ли", "не", "ни", "в", "во", "на", "за", "из", "от", "до",
        "по", "при", "про", "без", "над", "под", "с", "со", "у", "к", "ко", "о", "об", "для",
        "что", "как", "где", "когда", "сколько", "какой", "какая", "какое", "какие", "это",
        "этот", "эта", "эти", "мне", "меня", "тебя", "его", "её", "она", "они", "мы", "вы",
        "еще", "ещё", "уже", "если", "или", "да", "нет", "все", "весь", "всё", "пожалуйста",
        "можно", "нужно", "надо", "сделай", "покажи", "дай", "хочу", "будет", "был", "была",
        // English
        "the", "a", "an", "of", "to", "in", "on", "for", "and", "or", "is", "are", "be", "with",
        "my", "me", "please", "can", "you", "i", "it", "this", "that", "do", "does"
    )

    /**
     * High-value cross-script/abbreviation/synonym bridges. Keys are matched against both the raw
     * token and its stem, so a single stem-keyed entry (e.g. "налогов") covers every inflected form
     * that stems to it. Adding tokens only increases recall — a wrong entry cannot hide a document.
     */
    private val SYNONYMS: Map<String, List<String>> = mapOf(
        // messaging / comms
        "смс" to listOf("sms"),
        "sms" to listOf("смс"),
        "эсэмэс" to listOf("смс", "sms"),
        "звонок" to listOf("call"),
        "вызов" to listOf("call"),
        "call" to listOf("звонок", "вызов"),
        "телефон" to listOf("phone"),
        "phone" to listOf("телефон"),
        "почт" to listOf("mail", "email"),
        "mail" to listOf("почт"),
        "email" to listOf("почт"),
        "контакт" to listOf("contact"),
        "contact" to listOf("контакт"),
        // documents / files / web
        "пдф" to listOf("pdf"),
        "pdf" to listOf("пдф"),
        "файл" to listOf("file"),
        "file" to listOf("файл"),
        "папк" to listOf("folder", "dir"),
        "скриншот" to listOf("screenshot"),
        "screenshot" to listOf("скриншот"),
        "вайфай" to listOf("wifi"),
        "wifi" to listOf("вайфай"),
        "интернет" to listOf("web"),
        "сайт" to listOf("web", "url"),
        "web" to listOf("интернет", "сайт"),
        "ссылк" to listOf("url", "web"),
        "url" to listOf("ссылк"),
        // media
        "фото" to listOf("photo", "image"),
        "photo" to listOf("фото", "снимок"),
        "image" to listOf("фото", "изображен"),
        "видео" to listOf("video"),
        "video" to listOf("видео"),
        // notes / reminders
        "заметк" to listOf("note"),
        "note" to listOf("заметк"),
        "будильник" to listOf("alarm"),
        "напоминан" to listOf("reminder", "alarm"),
        "reminder" to listOf("напоминан", "будильник"),
        // taxes — the motivating example: "налоговая" must reach an "ФНС, декларация" memory
        "налог" to listOf("фнс"),
        "налогов" to listOf("фнс"),
        "фнс" to listOf("налогов", "налог"),
        "деклараци" to listOf("фнс", "налогов"),
        "tax" to listOf("налог", "налогов", "фнс"),
        // money — деньги ↔ ₽ (the ₽ symbol is rewritten to "рубль" before tokenising)
        "деньг" to listOf("рубл", "money", "cash"),
        "деньги" to listOf("деньг", "рубл"),
        "рубл" to listOf("деньг", "money"),
        "money" to listOf("деньг", "рубл"),
        "cash" to listOf("деньг"),
        "баланс" to listOf("balance", "счет"),
        "balance" to listOf("баланс"),
        // transport — машина ↔ авто
        "машин" to listOf("авто", "автомобил", "car"),
        "машина" to listOf("машин", "авто"),
        "авто" to listOf("машин", "автомобил", "car"),
        "автомобил" to listOf("машин", "авто", "car"),
        "car" to listOf("машин", "авто"),
        // people / work / misc durable facts
        "работ" to listOf("work", "job"),
        "work" to listOf("работ"),
        "встреч" to listOf("meeting"),
        "meeting" to listOf("встреч"),
        "цвет" to listOf("color", "colour"),
        "color" to listOf("цвет"),
        "друг" to listOf("friend"),
        "friend" to listOf("друг"),
        "адрес" to listOf("address"),
        "address" to listOf("адрес"),
        "имя" to listOf("name"),
        "name" to listOf("имя"),
        "врач" to listOf("doctor"),
        "doctor" to listOf("врач"),
        "лекарств" to listOf("medicine"),
        "medicine" to listOf("лекарств"),
        "пароль" to listOf("password"),
        "password" to listOf("пароль")
    )
}
