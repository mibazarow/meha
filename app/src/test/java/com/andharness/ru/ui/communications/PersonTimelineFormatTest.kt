package com.andharness.ru.ui.communications

import com.andharness.ru.data.db.entity.NoteEntity
import com.andharness.ru.domain.person.Interaction
import com.andharness.ru.domain.person.InteractionChannel
import com.andharness.ru.domain.person.InteractionDirection
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonTimelineFormatTest {

    private val utc = ZoneId.of("UTC")

    // 2023-11-14T22:13:20Z
    private val day1 = 1_700_000_000_000L

    private fun event(id: String, at: Long): PersonTimelineEntry =
        PersonTimelineEntry(id = id, kind = PersonTimelineKind.CALL, at = at)

    @Test
    fun emptyEntriesProduceNoSections() {
        assertTrue(PersonTimelineComposer.compose(emptyList(), utc).isEmpty())
    }

    @Test
    fun entriesGroupByDayNewestDayFirst() {
        val day2 = day1 + 24L * 60L * 60L * 1000L
        val sections = PersonTimelineComposer.compose(
            listOf(event("a", day1), event("b", day2)),
            utc,
        )
        assertEquals(2, sections.size)
        assertEquals("15 ноября 2023", sections[0].dayLabel)
        assertEquals("14 ноября 2023", sections[1].dayLabel)
    }

    @Test
    fun entriesWithinADayAreNewestFirst() {
        val later = day1 + 3_600_000L
        val sections = PersonTimelineComposer.compose(
            listOf(event("a", day1), event("b", later)),
            utc,
        )
        assertEquals(listOf("b", "a"), sections.single().entries.map { it.id })
    }

    @Test
    fun equalTimestampsAreOrderedByIdForDeterminism() {
        val sections = PersonTimelineComposer.compose(
            listOf(event("b", day1), event("a", day1)),
            utc,
        )
        assertEquals(listOf("a", "b"), sections.single().entries.map { it.id })
    }

    @Test
    fun everyChannelMapsToItsOwnKindAndLabel() {
        assertEquals(PersonTimelineKind.SMS, timelineKind(InteractionChannel.SMS))
        assertEquals(PersonTimelineKind.CALL, timelineKind(InteractionChannel.CALL))
        assertEquals(PersonTimelineKind.MESSENGER, timelineKind(InteractionChannel.MESSENGER))
        assertEquals(PersonTimelineKind.EMAIL, timelineKind(InteractionChannel.EMAIL))
        assertEquals(PersonTimelineKind.CHAT, timelineKind(InteractionChannel.CHAT))

        assertEquals("Звонок", timelineKindLabel(PersonTimelineKind.CALL))
        assertEquals("СМС", timelineKindLabel(PersonTimelineKind.SMS))
        assertEquals("Мессенджер", timelineKindLabel(PersonTimelineKind.MESSENGER))
        assertEquals("Email", timelineKindLabel(PersonTimelineKind.EMAIL))
        assertEquals("Чат", timelineKindLabel(PersonTimelineKind.CHAT))
        assertEquals("Речь", timelineKindLabel(PersonTimelineKind.TRANSCRIPT))
        assertEquals("Память", timelineKindLabel(PersonTimelineKind.MEMORY))
        assertEquals("Мнение", timelineKindLabel(PersonTimelineKind.OPINION))
    }

    @Test
    fun everyDirectionHasAnHonestLabelOrNull() {
        assertEquals("входящий", directionLabel(InteractionDirection.INCOMING))
        assertEquals("исходящий", directionLabel(InteractionDirection.OUTGOING))
        assertEquals("пропущенный", directionLabel(InteractionDirection.MISSED))
        assertNull(directionLabel(InteractionDirection.UNKNOWN))
    }

    @Test
    fun interactionEntryCarriesChannelDirectionAndGist() {
        val entry = interactionEntry(
            Interaction(
                id = 7,
                personId = 1,
                channel = InteractionChannel.CALL,
                direction = InteractionDirection.MISSED,
                at = day1,
                title = "Позвонок",
                summary = "перезвонить",
                externalRef = null,
                sourceApp = "phone",
            )
        )
        assertEquals("i7", entry.id)
        assertEquals(PersonTimelineKind.CALL, entry.kind)
        assertEquals(day1, entry.at)
        assertEquals("Позвонок", entry.title)
        assertEquals("перезвонить", entry.body)
        assertEquals("пропущенный", entry.meta)
    }

    @Test
    fun blankInteractionFieldsBecomeNullInsteadOfEmptyStrings() {
        val entry = interactionEntry(
            Interaction(
                id = 1,
                personId = 1,
                channel = InteractionChannel.SMS,
                direction = InteractionDirection.UNKNOWN,
                at = 5L,
                title = "  ",
                summary = "",
                externalRef = null,
                sourceApp = null,
            )
        )
        assertNull(entry.title)
        assertNull(entry.body)
        assertNull(entry.meta)
    }

    @Test
    fun transcriptEntryUsesRecordingLabelTextAndTimecode() {
        val entry = transcriptEntry(
            segmentId = 3,
            at = day1,
            recordingLabel = "Запись #4",
            startMs = 65_000L,
            endMs = 70_000L,
            text = "привет",
        )
        assertEquals("t3", entry.id)
        assertEquals(PersonTimelineKind.TRANSCRIPT, entry.kind)
        assertEquals("Запись #4", entry.title)
        assertEquals("привет", entry.body)
        assertEquals("[1:05–1:10]", entry.meta)
    }

    @Test
    fun noteEntryUsesTextAndSourcePrefixedId() {
        val note = NoteEntity(id = 9, kind = "memory", text = "любит кофе", createdAt = 42L)
        val memory = noteEntry(PersonTimelineKind.MEMORY, note)
        assertEquals("m9", memory.id)
        assertEquals(PersonTimelineKind.MEMORY, memory.kind)
        assertEquals("любит кофе", memory.body)
        assertEquals(42L, memory.at)

        val opinion = noteEntry(PersonTimelineKind.OPINION, note)
        assertEquals("o9", opinion.id)
        assertEquals(PersonTimelineKind.OPINION, opinion.kind)
    }

    @Test
    fun clockAndDayLabelsRespectTheGivenZone() {
        assertEquals("22:13", clockLabel(day1, utc))
        assertEquals("14 ноября 2023", dayLabel(day1, utc))
    }
}
