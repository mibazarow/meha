package com.andharness.ru.bg

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteConstraintException
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.andharness.ru.data.diagnostics.PowerGuardEntryPoint
import com.andharness.ru.data.person.PersonIndexer
import com.andharness.ru.data.person.PersonRef
import com.andharness.ru.data.person.personIngestEntryPoint
import com.andharness.ru.data.util.columnIndex
import com.andharness.ru.data.util.getIntOrNull
import com.andharness.ru.data.util.getLongOrNull
import com.andharness.ru.data.util.getStringOrNull
import com.andharness.ru.data.util.useRowsWhile
import com.andharness.ru.domain.messages.StoredMessage
import com.andharness.ru.domain.messages.StoredMessageSource
import com.andharness.ru.domain.messages.StoredMessagesRepository
import com.andharness.ru.domain.person.IdentityType
import com.andharness.ru.domain.person.InteractionChannel
import com.andharness.ru.domain.person.InteractionDirection
import com.andharness.ru.domain.person.SmsBackfillPaging
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.TimeUnit

/**
 * One-shot, **bounded** backfill of the personal base (update.md §4.4): contacts, SMS and the call
 * log.
 *
 * Rules:
 * - the worker is honest about missing permissions — a source without its runtime permission is
 *   **skipped and reported**, never guessed;
 * - each run is bounded ([MAX_SMS_PER_RUN], [MAX_CALLS_PER_RUN], [MAX_CONTACTS_PER_RUN]) and the
 *   provider cursors are persisted ([com.andharness.ru.data.person.PersonBackfillStore]), so a run
 *   resumes where the previous one stopped;
 * - the SMS scan walks the **entire** history with no date window and no fixed run-count cap: each
 *   run consumes at most [MAX_SMS_PER_RUN] rows and the chain continues while a pass came back full,
 *   stopping as soon as a pass consumes less than a page (the provider cursor reached the end);
 * - only metadata plus a **bounded gist** is written to `interactions` (summary ≤ 80 chars); the SMS
 *   body, however, is now also written in **full** to `stored_messages` (encrypted, device-only) for
 *   later search/summaries, keyed by `sms:<_ID>` so a resumed/cursor-replayed run is idempotent; a
 *   UNIQUE-constraint conflict on `externalRef` is treated as "already indexed" (skip), never an error;
 * - nothing runs while the user has not opted in (`messaging_index_config`).
 *
 * It performs no UI work and is scheduled with `WorkManager`, so it never blocks the app.
 *
 * References:
 * - `ContactsContract.Contacts` / `Phone` / `Email`:
 *   https://developer.android.com/reference/android/provider/ContactsContract.Contacts
 * - `Telephony.Sms`: https://developer.android.com/reference/android/provider/Telephony.Sms
 * - `CallLog.Calls`: https://developer.android.com/reference/android/provider/CallLog.Calls
 * - Room insert conflict handling: https://developer.android.com/reference/androidx/room/OnConflictStrategy
 * - `CoroutineWorker`: https://developer.android.com/reference/androidx/work/CoroutineWorker
 */
class PersonBackfillWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entry = try {
            personIngestEntryPoint(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Hilt entry point unavailable; backfill skipped", e)
            return Result.success()
        }
        val indexer = entry.personIndexer()
        val store = entry.personBackfillStore()
        val messageRepository = entry.storedMessagesRepository()

        // Honest no-op: the personal base is only filled when the user opted in to indexing.
        if (!indexer.isEnabled()) {
            Log.i(TAG, "person backfill skipped: indexing is off (no opt-in)")
            return Result.success()
        }

        // Power guard (update.md idea #19): the backfill is non-essential background maintenance.
        // Under critical power the run does NOT happen, does NOT count against the run cap, and the
        // chain is re-armed with a delay so the indexing resumes once the device recovers.
        val deferReason = EntryPointAccessors
            .fromApplication(applicationContext, PowerGuardEntryPoint::class.java)
            .powerGuard()
            .deferReason()
        if (deferReason != null) {
            Log.i(TAG, "person backfill deferred by the power guard: $deferReason")
            continueChainAfterDelay(applicationContext, POWER_DEFER_DELAY_MS)
            return Result.success()
        }

        val run = store.bumpRuns()

        val report = ArrayList<String>()
        backfillContacts(indexer, store, report)
        val moreSms = backfillSms(indexer, store, messageRepository, report)
        val moreCalls = backfillCalls(indexer, store, report)
        Log.i(TAG, "person backfill run $run: ${report.joinToString("; ")}")

        if (moreSms || moreCalls) continueChain(applicationContext)
        return Result.success()
    }

    /** Creates persons from contact names and links their PHONE/EMAIL identities. */
    private suspend fun backfillContacts(
        indexer: PersonIndexer,
        store: com.andharness.ru.data.person.PersonBackfillStore,
        report: MutableList<String>
    ) {
        if (store.contactsDone()) return
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            report += "контакты: пропущено (нет разрешения READ_CONTACTS)"
            store.setContactsDone(true)
            return
        }

        try {
            val phones = readContactHandles(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
            val emails = readContactHandles(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                ContactsContract.CommonDataKinds.Email.ADDRESS,
            )
            val names = readContactNames()

            var created = 0
            var linked = 0
            for ((contactId, name) in names) {
                if (created >= MAX_CONTACTS_PER_RUN) break
                val contactPhones = phones[contactId].orEmpty()
                val contactEmails = emails[contactId].orEmpty()
                // A name without any handle is still a person of the base; it is created below.
                val existing = contactPhones.firstNotNullOfOrNull { indexer.personIdFor(IdentityType.PHONE, it) }
                    ?: contactEmails.firstNotNullOfOrNull { indexer.personIdFor(IdentityType.EMAIL, it) }
                val personId = existing ?: indexer.ensurePerson(name).also { created++ }
                contactPhones.forEach { if (indexer.linkIdentity(personId, IdentityType.PHONE, it) != null) linked++ }
                contactEmails.forEach { if (indexer.linkIdentity(personId, IdentityType.EMAIL, it) != null) linked++ }
            }
            store.setContactsDone(true)
            report += "контакты: ${names.size} обработано, $created создано, $linked идентичностей"
        } catch (e: SecurityException) {
            report += "контакты: пропущено (нет доступа)"
            store.setContactsDone(true)
        } catch (e: Exception) {
            Log.e(TAG, "contacts backfill failed", e)
            report += "контакты: ошибка чтения (${e.javaClass.simpleName})"
        }
    }

    /** Indexes SMS metadata + gist from the persisted cursor; returns `true` when more remain. */
    private suspend fun backfillSms(
        indexer: PersonIndexer,
        store: com.andharness.ru.data.person.PersonBackfillStore,
        messageRepository: StoredMessagesRepository,
        report: MutableList<String>
    ): Boolean {
        if (!hasPermission(Manifest.permission.READ_SMS)) {
            report += "SMS: пропущено (нет разрешения READ_SMS)"
            return false
        }
        val from = store.smsCursor()
        var lastId = from
        var processed = 0
        var more = false
        try {
            applicationContext.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE,
                    Telephony.Sms.BODY,
                ),
                "${Telephony.Sms._ID} > ?",
                arrayOf(from.toString()),
                "${Telephony.Sms._ID} ASC",
            )?.useRowsWhile { cursor ->
                if (processed >= MAX_SMS_PER_RUN) return@useRowsWhile false
                val id = cursor.getLongOrNull(cursor.columnIndex(Telephony.Sms._ID))
                    ?: return@useRowsWhile true
                lastId = id
                val address = cursor.getStringOrNull(cursor.columnIndex(Telephony.Sms.ADDRESS))
                if (address.isNullOrBlank()) return@useRowsWhile true
                val date = cursor.getLongOrNull(cursor.columnIndex(Telephony.Sms.DATE)) ?: 0L
                val type = cursor.getIntOrNull(cursor.columnIndex(Telephony.Sms.TYPE)) ?: -1
                // The body is read to derive the bounded gist AND to persist the full text in
                // `stored_messages`; the gist interaction below stays metadata-only.
                val body = cursor.getStringOrNull(cursor.columnIndex(Telephony.Sms.BODY))
                indexer.recordInteraction(
                    channel = InteractionChannel.SMS,
                    direction = smsDirection(type),
                    at = date,
                    personIdOrIdentity = PersonRef.Identity(IdentityType.PHONE, address, label = null),
                    summary = body,
                    externalRef = "sms:$id",
                )
                // Full body, idempotent by the SMS row id: a re-run of the same cursor range or a
                // delivered-broadcast duplicate never inserts a second row.
                val externalRef = "sms:$id"
                if (messageRepository.findByExternalRef(externalRef) == null) {
                    try {
                        messageRepository.insert(
                            StoredMessage(
                                id = 0,
                                sourceType = StoredMessageSource.SMS,
                                packageName = null,
                                conversationKey = address,
                                personId = indexer.personIdFor(IdentityType.PHONE, address),
                                direction = smsDirection(type).name,
                                sender = address,
                                isGroup = false,
                                at = date,
                                text = body ?: "",
                                externalRef = externalRef,
                                createdAt = System.currentTimeMillis(),
                            )
                        )
                    } catch (e: SQLiteConstraintException) {
                        // A concurrent producer indexed the same sms:<id> between the lookup and
                        // the insert; the UNIQUE externalRef index rejected it. That means
                        // "already indexed", not a failure — skip and carry on.
                        Log.i(TAG, "SMS $externalRef уже в базе (уникальный индекс); пропуск")
                    }
                }
                processed++
                true
            }
            store.setSmsCursor(lastId)
            more = SmsBackfillPaging.shouldContinue(processed, MAX_SMS_PER_RUN)
            report += "SMS: $processed (курсор $lastId${if (more) ", будут ещё" else ""})"
        } catch (e: SecurityException) {
            report += "SMS: пропущено (нет доступа)"
            return false
        } catch (e: Exception) {
            Log.e(TAG, "sms backfill failed", e)
            report += "SMS: ошибка чтения (${e.javaClass.simpleName})"
            return false
        }
        return more
    }

    /** Indexes call-log entries from the persisted cursor; returns `true` when more remain. */
    private suspend fun backfillCalls(
        indexer: PersonIndexer,
        store: com.andharness.ru.data.person.PersonBackfillStore,
        report: MutableList<String>
    ): Boolean {
        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) {
            report += "журнал вызовов: пропущено (нет разрешения READ_CALL_LOG)"
            return false
        }
        val from = store.callsCursor()
        var lastId = from
        var processed = 0
        var more = false
        try {
            applicationContext.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.TYPE,
                ),
                "${CallLog.Calls._ID} > ?",
                arrayOf(from.toString()),
                "${CallLog.Calls._ID} ASC",
            )?.useRowsWhile { cursor ->
                if (processed >= MAX_CALLS_PER_RUN) {
                    more = true
                    return@useRowsWhile false
                }
                val id = cursor.getLongOrNull(cursor.columnIndex(CallLog.Calls._ID))
                    ?: return@useRowsWhile true
                lastId = id
                val number = cursor.getStringOrNull(cursor.columnIndex(CallLog.Calls.NUMBER))
                if (number.isNullOrBlank()) return@useRowsWhile true
                val date = cursor.getLongOrNull(cursor.columnIndex(CallLog.Calls.DATE)) ?: 0L
                val duration = cursor.getLongOrNull(cursor.columnIndex(CallLog.Calls.DURATION)) ?: 0L
                val type = cursor.getIntOrNull(cursor.columnIndex(CallLog.Calls.TYPE)) ?: -1
                indexer.recordInteraction(
                    channel = InteractionChannel.CALL,
                    direction = callDirection(type),
                    at = date,
                    personIdOrIdentity = PersonRef.Identity(IdentityType.PHONE, number),
                    summary = "звонок, длительность ${duration}с",
                    externalRef = "call:$id",
                )
                processed++
                true
            }
            store.setCallsCursor(lastId)
            report += "журнал вызовов: $processed (курсор $lastId${if (more) ", будут ещё" else ""})"
        } catch (e: SecurityException) {
            report += "журнал вызовов: пропущено (нет доступа)"
            return false
        } catch (e: Exception) {
            Log.e(TAG, "call-log backfill failed", e)
            report += "журнал вызовов: ошибка чтения (${e.javaClass.simpleName})"
            return false
        }
        return more
    }

    /** `contactId → [handles]`, bounded to [MAX_CONTACTS_PER_RUN] rows per source. */
    private fun readContactHandles(
        uri: android.net.Uri,
        idColumn: String,
        valueColumn: String
    ): Map<Long, List<String>> {
        val out = HashMap<Long, MutableList<String>>()
        var rows = 0
        try {
            applicationContext.contentResolver.query(uri, arrayOf(idColumn, valueColumn), null, null, null)
                ?.useRowsWhile { cursor ->
                    if (rows >= MAX_CONTACTS_PER_RUN) return@useRowsWhile false
                    val id = cursor.getLongOrNull(cursor.columnIndex(idColumn))
                        ?: return@useRowsWhile true
                    val value = cursor.getStringOrNull(cursor.columnIndex(valueColumn))
                    if (value.isNullOrBlank()) return@useRowsWhile true
                    out.getOrPut(id) { ArrayList() }.add(value)
                    rows++
                    true
                }
        } catch (e: Exception) {
            Log.w(TAG, "contact handle read failed", e)
        }
        return out
    }

    /** `contactId → display name`, bounded to [MAX_CONTACTS_PER_RUN]. */
    private fun readContactNames(): Map<Long, String> {
        val out = LinkedHashMap<Long, String>()
        try {
            applicationContext.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
                null,
                null,
                "${ContactsContract.Contacts._ID} ASC",
            )?.useRowsWhile { cursor ->
                if (out.size >= MAX_CONTACTS_PER_RUN) return@useRowsWhile false
                val id = cursor.getLongOrNull(cursor.columnIndex(ContactsContract.Contacts._ID))
                    ?: return@useRowsWhile true
                val name = cursor.getStringOrNull(cursor.columnIndex(ContactsContract.Contacts.DISPLAY_NAME))
                if (name.isNullOrBlank()) return@useRowsWhile true
                out[id] = name.trim()
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "contact name read failed", e)
        }
        return out
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(applicationContext, permission) ==
            PackageManager.PERMISSION_GRANTED

    private fun smsDirection(type: Int): InteractionDirection = when (type) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> InteractionDirection.INCOMING
        Telephony.Sms.MESSAGE_TYPE_SENT,
        Telephony.Sms.MESSAGE_TYPE_OUTBOX,
        Telephony.Sms.MESSAGE_TYPE_QUEUED -> InteractionDirection.OUTGOING
        else -> InteractionDirection.UNKNOWN
    }

    private fun callDirection(type: Int): InteractionDirection = when (type) {
        CallLog.Calls.INCOMING_TYPE -> InteractionDirection.INCOMING
        CallLog.Calls.OUTGOING_TYPE -> InteractionDirection.OUTGOING
        CallLog.Calls.MISSED_TYPE -> InteractionDirection.MISSED
        else -> InteractionDirection.UNKNOWN
    }

    companion object {
        private const val TAG = "Andharness"

        /** Unique work name used by the config tool to start/cancel the backfill. */
        const val WORK_NAME = "person_backfill"

        /** How long the power guard postpones the next backfill run under critical power. */
        const val POWER_DEFER_DELAY_MS = 30L * 60 * 1000

        private const val MAX_SMS_PER_RUN = 2000
        private const val MAX_CALLS_PER_RUN = 2000
        private const val MAX_CONTACTS_PER_RUN = 2000

        /** (Re)starts the backfill from the current cursors; used when the opt-in is enabled. */
        fun start(context: Context) {
            try {
                val request = OneTimeWorkRequestBuilder<PersonBackfillWorker>().build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
            } catch (e: Exception) {
                Log.e(TAG, "starting the person backfill failed", e)
            }
        }

        /**
         * Appends the next bounded run to the same chain; used by the worker itself. `APPEND_OR_REPLACE`
         * is safe to call while the current run is still executing (it is then a continuation).
         */
        fun continueChain(context: Context) {
            try {
                val request = OneTimeWorkRequestBuilder<PersonBackfillWorker>().build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request,
                )
            } catch (e: Exception) {
                Log.e(TAG, "continuing the person backfill failed", e)
            }
        }

        /**
         * Same continuation, but delayed — used by the power guard so indexing resumes only after the
         * device recovers instead of hammering a throttled phone (update.md idea #19).
         */
        fun continueChainAfterDelay(context: Context, delayMs: Long) {
            try {
                val request = OneTimeWorkRequestBuilder<PersonBackfillWorker>()
                    .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request,
                )
            } catch (e: Exception) {
                Log.e(TAG, "delayed continuation of the person backfill failed", e)
            }
        }

        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            } catch (e: Exception) {
                Log.e(TAG, "cancelling the person backfill failed", e)
            }
        }
    }
}
