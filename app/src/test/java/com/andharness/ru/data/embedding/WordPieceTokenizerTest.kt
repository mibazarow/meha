package com.andharness.ru.data.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM coverage of the WordPiece tokenizer used by the bundled embedding model: special tokens,
 * truncation, padding/attention, cased behaviour, subwords, punctuation and the real `vocab.txt`.
 */
class WordPieceTokenizerTest {

    private val vocab = listOf(
        "[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]",
        "привет", "мир", "Привет", "##кет", "!", "hello", "Москва",
    )
    private val tokenizer = WordPieceTokenizer(vocab)

    @Test
    fun specialTokenIdsMatchTheBundledVocabLayout() {
        assertEquals(0, tokenizer.padId)
        assertEquals(1, tokenizer.unkId)
        assertEquals(2, tokenizer.clsId)
        assertEquals(3, tokenizer.sepId)
    }

    @Test
    fun emptyTextEncodesToClsAndSepOnly() {
        assertEquals(listOf(2, 3), tokenizer.encode("", 8))
        assertEquals(emptyList<String>(), tokenizer.tokenize(""))
    }

    @Test
    fun encodeAddsClsAndSepAroundKnownTokens() {
        assertEquals(listOf(2, 5, 3), tokenizer.encode("привет", 8))
    }

    @Test
    fun caseIsPreservedBecauseTheVocabIsCased() {
        // rubert-tiny-turbo has do_lower_case=false: "Привет" must not collapse to "привет".
        assertEquals(listOf("Привет"), tokenizer.tokenize("Привет"))
        // "москва" is absent (only "Москва" exists) and has no known subwords -> a single [UNK].
        assertEquals(listOf("[UNK]"), tokenizer.tokenize("москва"))
    }

    @Test
    fun unknownWordsCollapseToASingleUnk() {
        assertEquals(listOf("[UNK]"), tokenizer.tokenize("незнакомоеслово"))
    }

    @Test
    fun greedyWordPieceUsesContinuingSubwordPrefix() {
        assertEquals(listOf("привет", "##кет"), tokenizer.tokenize("приветкет"))
    }

    @Test
    fun punctuationIsSplitIntoSeparateTokens() {
        assertEquals(listOf("привет", "!"), tokenizer.tokenize("привет!"))
        assertEquals(listOf(2, 5, 9, 3), tokenizer.encode("привет!", 8))
    }

    @Test
    fun whitespaceAndControlCharactersAreNormalised() {
        assertEquals(listOf("привет", "мир"), tokenizer.tokenize("привет\n\tмир\u0000"))
        assertEquals(listOf("привет", "мир"), tokenizer.tokenize("  привет   мир  "))
    }

    @Test
    fun truncationKeepsClsAndAlwaysReAppendsSep() {
        // "привет мир" -> [5, 6]; full ids [2,5,6,3]; maxLen=3 keeps [CLS], first piece, [SEP].
        assertEquals(listOf(2, 5, 3), tokenizer.encode("привет мир", 3))
    }

    @Test
    fun encodeBatchPadsToLongestSequenceAndMasksPadding() {
        val batch = tokenizer.encodeBatch(listOf("привет", "привет мир"), 8)
        assertEquals(4, batch.seqLen)
        assertEquals(listOf(2L, 5L, 3L, 0L, 2L, 5L, 6L, 3L), batch.inputIds.toList())
        assertEquals(listOf(1L, 1L, 1L, 0L, 1L, 1L, 1L, 1L), batch.attentionMask.toList())
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L), batch.tokenTypeIds.toList())
    }

    @Test
    fun fromVocabTextIgnoresTheTrailingNewline() {
        val fromText = WordPieceTokenizer.fromVocabText("[PAD]\n[UNK]\n[CLS]\n[SEP]\n[MASK]\nпривет\n")
        assertEquals(listOf(2, 5, 3), fromText.encode("привет", 8))
    }

    @Test
    fun realVocabIsCasedAndTokenizesRussianWithoutUnk() {
        val vocabFile = firstExisting(
            "src/main/assets/models/embedding/vocab.txt",
            "app/src/main/assets/models/embedding/vocab.txt",
        ) ?: error("vocab.txt not found from ${File(".").absolutePath}")

        val real = WordPieceTokenizer.fromVocabText(vocabFile.readText())
        assertEquals(0, real.padId)
        assertEquals(1, real.unkId)
        assertEquals(2, real.clsId)
        assertEquals(3, real.sepId)

        // Common Russian words are real vocab entries, not subword fragments and not [UNK].
        assertEquals(listOf("Привет", ",", "мир", "!"), real.tokenize("Привет, мир!"))

        val ids = real.encode("Сегодня хорошая погода", 16)
        assertEquals(5, ids.size)
        assertEquals(real.clsId, ids.first())
        assertEquals(real.sepId, ids.last())
        assertTrue("no [UNK] expected for a plain Russian sentence", ids.none { it == real.unkId })
    }

    private fun firstExisting(vararg paths: String): File? =
        paths.map(::File).firstOrNull { it.isFile }
}
