package com.andharness.ru.data.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * JVM tests for the pure per-notification request-code derivation ([NotificationHelper.requestCodeFor]).
 *
 * A unique `PendingIntent` request code per notification is what makes each "done" notification open
 * ITS OWN chat (BUG-051): with a shared code, `PendingIntent.FLAG_UPDATE_CURRENT` would reuse the
 * first intent's extras and every tap would land in the same chat. The absent-chat case falls back
 * to the fixed id, i.e. "open the app" with no specific chat.
 */
class NotificationHelperRequestCodeTest {

    @Test
    fun `an absent chat uses the fixed fallback code`() {
        assertEquals(
            NotificationHelper.NOTIFICATION_ID_DONE,
            NotificationHelper.requestCodeFor(null, null, NotificationHelper.NOTIFICATION_ID_DONE),
        )
    }

    @Test
    fun `each chat gets a unique request code`() {
        assertNotEquals(
            NotificationHelper.requestCodeFor(7L, null, NotificationHelper.NOTIFICATION_ID_DONE),
            NotificationHelper.requestCodeFor(8L, null, NotificationHelper.NOTIFICATION_ID_DONE),
        )
    }

    @Test
    fun `each message gets a distinct request code for the same chat`() {
        assertNotEquals(
            NotificationHelper.requestCodeFor(7L, null, NotificationHelper.NOTIFICATION_ID_DONE),
            NotificationHelper.requestCodeFor(7L, 42L, NotificationHelper.NOTIFICATION_ID_DONE),
        )
    }

    @Test
    fun `the same chat and message always yield the same code`() {
        assertEquals(
            NotificationHelper.requestCodeFor(7L, 42L, NotificationHelper.NOTIFICATION_ID_DONE),
            NotificationHelper.requestCodeFor(7L, 42L, NotificationHelper.NOTIFICATION_ID_DONE),
        )
    }
}
