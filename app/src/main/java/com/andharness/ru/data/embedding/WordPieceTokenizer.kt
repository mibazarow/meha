package com.andharness.ru.data.embedding

/**
 * Pure-Kotlin BERT tokenizer (BasicTokenizer + WordPiece) for the bundled `rubert-tiny-turbo-int8`
 * embedding model. No Android dependency, so it is fully unit-testable on the JVM.
 *
 * The bundled model's `tokenizer_config.json` is `{"do_lower_case": false, "do_basic_tokenize":
 * true, "tokenize_chinese_chars": true, "model_max_length": 2048}` and `sentence_bert_config.json`
 * says `do_lower_case: false`. The vocab (`vocab.txt`, 83828 entries) is **cased**: it contains both
 * `А..Я` and `а..я`, so [doLowerCase] defaults to `false` and case is preserved. Lowercasing here
 * would map most Russian words to `[UNK]`.
 *
 * References:
 * - Original BERT tokenization (BasicTokenizer / WordPieceTokenizer / FullTokenizer) —
 *   https://github.com/google-research/bert/blob/master/tokenization.py
 * - HuggingFace `BertTokenizer` (`do_lower_case`, `do_basic_tokenize`, `tokenize_chinese_chars`,
 *   `never_split`, `##` continuing-subword prefix) —
 *   https://huggingface.co/docs/transformers/en/model_doc/bert#transformers.BertTokenizer
 * - Model card / files: https://huggingface.co/sergeyzh/rubert-tiny-turbo
 */
class WordPieceTokenizer(
    vocab: List<String>,
    private val doLowerCase: Boolean = false,
    private val tokenizeChineseChars: Boolean = true,
    private val maxInputCharsPerWord: Int = 100,
    private val continuingSubwordPrefix: String = "##",
    private val unkToken: String = "[UNK]",
    private val clsToken: String = "[CLS]",
    private val sepToken: String = "[SEP]",
    private val padToken: String = "[PAD]",
) {

    private val vocabIndex: Map<String, Int> = vocab.withIndex().associate { (index, token) -> token to index }

    /** Token `[UNK]` id (1 in the bundled vocab); used for unknown words and words that are too long. */
    val unkId: Int = vocabIndex[unkToken] ?: 1

    /** Token `[CLS]` id (2); prepended to every sequence. */
    val clsId: Int = vocabIndex[clsToken] ?: 2

    /** Token `[SEP]` id (3); appended to every sequence. */
    val sepId: Int = vocabIndex[sepToken] ?: 3

    /** Token `[PAD]` id (0); fills short sequences in a batch. */
    val padId: Int = vocabIndex[padToken] ?: 0

    /** Special tokens that must never be split on punctuation, matching `never_split`. */
    private val neverSplit: Set<String> = setOf(unkToken, sepToken, padToken, clsToken, "[MASK]")

    /**
     * Basic tokenization + WordPiece: splits [text] into WordPiece sub-tokens (no `[CLS]`/`[SEP]`).
     * Punctuation becomes its own token, CJK characters are separated, and unknown words collapse to
     * a single `[UNK]`.
     */
    fun tokenize(text: String): List<String> {
        val cleaned = cleanText(text)
        val spaced = if (tokenizeChineseChars) padChineseChars(cleaned) else cleaned
        val out = ArrayList<String>()
        for (raw in spaced.split(' ')) {
            if (raw.isEmpty()) continue
            if (neverSplit.contains(raw)) {
                out += raw
                continue
            }
            for (piece in splitOnPunctuation(raw)) {
                out += if (doLowerCase) piece.lowercase() else piece
            }
        }
        return out.flatMap(::wordPiece)
    }

    /**
     * Encodes [text] to token ids with `[CLS] ... [SEP]` and truncation to at most [maxLen] tokens.
     * Truncation keeps `[CLS]`, drops trailing wordpieces and always re-appends `[SEP]`, matching
     * HuggingFace's default `longest_first` truncation.
     */
    fun encode(text: String, maxLen: Int): List<Int> {
        require(maxLen >= 2) { "maxLen must leave room for [CLS] and [SEP]" }
        val ids = ArrayList<Int>()
        ids += clsId
        for (piece in tokenize(text)) ids += vocabIndex[piece] ?: unkId
        ids += sepId
        if (ids.size <= maxLen) return ids

        val truncated = ArrayList<Int>(maxLen)
        for (i in 0 until maxLen - 1) truncated += ids[i]
        truncated += sepId
        return truncated
    }

    /**
     * Encodes a batch with a shared sequence length (the longest encoded text in the batch), padding
     * shorter ones with `[PAD]`. Returns int64-friendly arrays plus an attention mask (`1` for real
     * tokens, `0` for padding) and an all-zero `token_type_ids`, matching the model's three inputs.
     */
    fun encodeBatch(texts: List<String>, maxLen: Int): TokenizedBatch {
        val encoded = texts.map { encode(it, maxLen) }
        val seqLen = encoded.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
        val inputIds = LongArray(texts.size * seqLen) { padId.toLong() }
        val attentionMask = LongArray(texts.size * seqLen)
        for (row in encoded.indices) {
            val ids = encoded[row]
            for (col in ids.indices) {
                inputIds[row * seqLen + col] = ids[col].toLong()
                attentionMask[row * seqLen + col] = 1L
            }
        }
        return TokenizedBatch(
            seqLen = seqLen,
            inputIds = inputIds,
            attentionMask = attentionMask,
            tokenTypeIds = LongArray(texts.size * seqLen)
        )
    }

    /** Greedy longest-match-first WordPiece over one whitespace/punctuation token. */
    private fun wordPiece(token: String): List<String> {
        if (token.length > maxInputCharsPerWord) return listOf(unkToken)
        val pieces = ArrayList<String>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var current: String? = null
            while (start < end) {
                val sub = token.substring(start, end)
                val candidate = if (start > 0) continuingSubwordPrefix + sub else sub
                if (vocabIndex.containsKey(candidate)) {
                    current = candidate
                    break
                }
                end--
            }
            if (current == null) {
                pieces.clear()
                pieces += unkToken
                break
            }
            pieces += current
            start = end
        }
        return pieces
    }

    /** Drops NUL/replacement/control characters and normalises every run of whitespace to one space. */
    private fun cleanText(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            // BERT keeps \t, \n and \r out of the "control" bucket so they normalise to a space.
            val isControl = Character.getType(cp) == Character.CONTROL.toInt() &&
                cp != '\t'.code && cp != '\n'.code && cp != '\r'.code
            when {
                cp == 0 || cp == 0xFFFD || isControl -> Unit
                Character.isWhitespace(cp) -> sb.append(' ')
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }

    /** Surrounds every CJK character with spaces, as BERT's `tokenize_chinese_chars` does. */
    private fun padChineseChars(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (ch in text) {
            if (isChineseChar(ch.code)) {
                sb.append(' ').append(ch).append(' ')
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** Splits [token] so each punctuation character becomes its own token (BERT `_run_split_on_punc`). */
    private fun splitOnPunctuation(token: String): List<String> {
        val out = ArrayList<String>()
        val buffer = StringBuilder()
        for (ch in token) {
            if (isPunctuation(ch.code)) {
                if (buffer.isNotEmpty()) {
                    out += buffer.toString()
                    buffer.setLength(0)
                }
                out += ch.toString()
            } else {
                buffer.append(ch)
            }
        }
        if (buffer.isNotEmpty()) out += buffer.toString()
        return out
    }

    private fun isPunctuation(cp: Int): Boolean {
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(cp)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    private fun isChineseChar(cp: Int): Boolean = cp in 0x4E00..0x9FFF ||
        cp in 0x3400..0x4DBF ||
        cp in 0x20000..0x2A6DF ||
        cp in 0x2A700..0x2B73F ||
        cp in 0x2B740..0x2B81F ||
        cp in 0x2B820..0x2CEAF ||
        cp in 0xF900..0xFAFF ||
        cp in 0x2F800..0x2FA1F

    companion object {
        /** Builds a tokenizer from already-split vocab lines (line number = token id). */
        fun fromVocabLines(lines: Sequence<String>): WordPieceTokenizer = WordPieceTokenizer(lines.toList())

        /**
         * Builds a tokenizer from the raw `vocab.txt` text. Uses [String.lineSequence] so a trailing
         * newline does not introduce a phantom token (the bundled vocab has exactly 83828 lines).
         */
        fun fromVocabText(text: String): WordPieceTokenizer = fromVocabLines(text.lineSequence())
    }
}
