package com.andharness.ru.data.jobs

import kotlinx.serialization.Serializable

/**
 * Persisted description of one `JobScheduler` job created by the `job_schedule` tool.
 *
 * `JobScheduler` itself only keeps the scheduling constraints (id, period, requirements); the
 * *payload* (the prompt an agent must run, or the notification text) is ours to persist. We store it
 * in `SharedPreferences` as one small JSON value per job id, so the [com.andharness.ru.bg.AndharnessJobService]
 * can still act after a process death / reboot (JobScheduler re-delivers persisted jobs).
 *
 * A `SharedPreferences` store (rather than Room) is deliberate: this is a tiny key/value map keyed by
 * job id with no queries — no schema, no migration, no DB version bump.
 */
@Serializable
data class JobSpec(
    val id: Int,
    val intervalMin: Int,
    val requiresCharging: Boolean = false,
    val requiresNetwork: Boolean = false,
    /** Text to run as an agent chat when the job fires (mutually exclusive with [notifyText]). */
    val prompt: String? = null,
    /** Text to post as a notification when the job fires. */
    val notifyText: String? = null,
    /** Chat the job was created from, so the completion notification deep-links back to it. */
    val chatId: Long? = null,
)
