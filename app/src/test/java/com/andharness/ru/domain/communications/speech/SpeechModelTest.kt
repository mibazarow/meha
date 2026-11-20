package com.andharness.ru.domain.communications.speech

import com.andharness.ru.domain.localserver.FreeSpaceDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the speech-model catalog and its free-space policy. No Android or network involved.
 */
class SpeechModelTest {

    // --- catalog integrity -------------------------------------------------------------------

    @Test
    fun everyModelHasNonBlankUrlAndFileName() {
        SpeechModel.entries.forEach { model ->
            assertTrue(model.displayName, model.downloadUrl.isNotBlank())
            assertTrue(model.displayName, model.fileName.isNotBlank())
            assertTrue(model.displayName, model.downloadUrl.startsWith("https://huggingface.co/"))
            assertTrue(model.displayName, model.downloadUrl.endsWith(model.fileName))
        }
    }

    @Test
    fun everyModelHasPositiveApproxBytes() {
        SpeechModel.entries.forEach { model ->
            assertTrue(model.displayName, model.approxBytes > 0L)
        }
    }

    @Test
    fun everyModelHasReal64HexSha256() {
        SpeechModel.entries.forEach { model ->
            assertEquals(model.displayName, 64, model.sha256.length)
            assertTrue(model.displayName, model.sha256.all { it in "0123456789abcdef" })
        }
    }

    @Test
    fun idsAreUnique() {
        val ids = SpeechModel.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun fileNamesAreUnique() {
        val names = SpeechModel.entries.map { it.fileName }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun fromIdRoundTrips() {
        SpeechModel.entries.forEach { model ->
            assertEquals(model, SpeechModel.fromId(model.id))
        }
    }

    @Test
    fun fromIdReturnsNullForUnknownId() {
        assertNull(SpeechModel.fromId("does_not_exist"))
    }

    // --- default -----------------------------------------------------------------------------

    @Test
    fun exactlyOneDefaultModelAndItIsNonVad() {
        val defaults = SpeechModel.entries.filter { it == SpeechModel.DEFAULT }
        assertEquals(1, defaults.size)
        assertEquals(SpeechModelKind.WHISPER, SpeechModel.DEFAULT.kind)
    }

    @Test
    fun defaultModelIsWhisperBase() {
        assertEquals(SpeechModel.WHISPER_BASE, SpeechModel.DEFAULT)
    }

    @Test
    fun vadModelIsNotDefault() {
        assertTrue(SpeechModel.entries.any { it.kind == SpeechModelKind.VAD })
        assertTrue(SpeechModel.DEFAULT.kind != SpeechModelKind.VAD)
    }

    // --- free-space policy (mirrors freeSpaceDecision usage) ---------------------------------

    @Test
    fun belowRequestedSpaceIsInsufficient() {
        assertEquals(FreeSpaceDecision.INSUFFICIENT, SpeechModel.freeSpaceDecision(999L, 1_000L))
    }

    @Test
    fun exactlyRequestedSpaceIsLowNotInsufficient() {
        assertEquals(FreeSpaceDecision.LOW, SpeechModel.freeSpaceDecision(1_000L, 1_000L))
    }

    @Test
    fun justBelowOneAndAQuarterIsLow() {
        // requiredWithMargin(1000) == 1250, so 1249 is still LOW.
        assertEquals(FreeSpaceDecision.LOW, SpeechModel.freeSpaceDecision(1_249L, 1_000L))
    }

    @Test
    fun exactlyOneAndAQuarterIsOk() {
        assertEquals(FreeSpaceDecision.OK, SpeechModel.freeSpaceDecision(1_250L, 1_000L))
    }

    @Test
    fun zeroRequiredIsAlwaysOk() {
        assertEquals(FreeSpaceDecision.OK, SpeechModel.freeSpaceDecision(0L, 0L))
    }

    @Test
    fun decisionUsesTheRealDefaultModelSize() {
        val required = SpeechModel.DEFAULT.approxBytes
        assertEquals(
            FreeSpaceDecision.INSUFFICIENT,
            SpeechModel.freeSpaceDecision(required - 1, required),
        )
        assertEquals(FreeSpaceDecision.LOW, SpeechModel.freeSpaceDecision(required, required))
        assertEquals(
            FreeSpaceDecision.OK,
            SpeechModel.freeSpaceDecision(SpeechModel.requiredWithMargin(required), required),
        )
    }

    @Test
    fun insufficientMessageStatesHowMuchIsMissing() {
        val message = SpeechModel.freeSpaceMessage(
            FreeSpaceDecision.INSUFFICIENT,
            freeBytes = 0L,
            requiredBytes = 1024L * 1024L * 200L,
        )
        assertTrue(message.contains("Недостаточно"))
        assertTrue(message.contains("200 МБ"))
    }
}
