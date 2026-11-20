package com.andharness.ru.data.tools.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactAggregationTest {

    @Test
    fun resolvesFirstRawContactOfEachContact() {
        val result = ContactAggregation.resolveRawContactPair(
            contactId1 = "427",
            contactId2 = "428",
            rawContactIds1 = listOf(1001L, 1002L),
            rawContactIds2 = listOf(2001L),
        )

        assertEquals(ContactAggregation.Result.Resolved(1001L, 2001L), result)
    }

    @Test
    fun rejectsContactWithoutRawContact() {
        val result = ContactAggregation.resolveRawContactPair(
            contactId1 = "427",
            contactId2 = "428",
            rawContactIds1 = emptyList(),
            rawContactIds2 = listOf(2001L),
        )

        assertTrue(result is ContactAggregation.Result.Invalid)
        assertTrue((result as ContactAggregation.Result.Invalid).message.contains("427"))
    }

    @Test
    fun rejectsSameRawContact() {
        val result = ContactAggregation.resolveRawContactPair(
            contactId1 = "427",
            contactId2 = "428",
            rawContactIds1 = listOf(7L),
            rawContactIds2 = listOf(7L, 8L),
        )

        assertTrue(result is ContactAggregation.Result.Invalid)
    }
}
