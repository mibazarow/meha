package com.andharness.ru.data.embedding

/** One padded, int64-ready token batch: all four arrays have length `batchSize * seqLen`. */
data class TokenizedBatch(
    val seqLen: Int,
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val tokenTypeIds: LongArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TokenizedBatch) return false
        return seqLen == other.seqLen &&
            inputIds.contentEquals(other.inputIds) &&
            attentionMask.contentEquals(other.attentionMask) &&
            tokenTypeIds.contentEquals(other.tokenTypeIds)
    }

    override fun hashCode(): Int {
        var result = seqLen
        result = 31 * result + inputIds.contentHashCode()
        result = 31 * result + attentionMask.contentHashCode()
        result = 31 * result + tokenTypeIds.contentHashCode()
        return result
    }
}
