package com.andharness.ru.bg

import android.net.Uri
import android.service.notification.ConditionProviderService
import android.util.Log

/**
 * Condition provider that owns the ANDharness "Do Not Disturb" [android.app.AutomaticZenRule].
 *
 * Why it exists: an app may not change the global DND filter with
 * `NotificationManager.setInterruptionFilter(int)` on Android 15 (targetSdk 35) anymore. The
 * supported path is an [android.app.AutomaticZenRule] whose condition is owned by this service.
 * The condition state itself is pushed by `dnd_set` through
 * `NotificationManager.setAutomaticZenRuleState(String, Condition)`, which is why this service only
 * has to exist, be enabled and accept the subscription — it does not have to publish conditions.
 *
 * The rule is validated by the system against this component: `addAutomaticZenRule` /
 * `updateAutomaticZenRule` throw `IllegalArgumentException` when the owner is not a valid and
 * enabled `ConditionProviderService`, so this class must stay declared in the manifest with the
 * `android.service.notification.ConditionProviderService` intent filter and the
 * `BIND_CONDITION_PROVIDER_SERVICE` permission.
 *
 * References:
 * - https://developer.android.com/reference/android/app/NotificationManager#addAutomaticZenRule(android.app.AutomaticZenRule)
 * - https://developer.android.com/reference/android/app/AutomaticZenRule
 * - https://developer.android.com/reference/android/service/notification/ConditionProviderService
 */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class DndConditionProviderService : ConditionProviderService() {

    override fun onConnected() {
        Log.d(TAG, "onConnected")
    }

    override fun onSubscribe(conditionId: Uri) {
        // The system subscribed to our condition (the rule became active/visible to us). No
        // publishing is required: the condition state is driven by setAutomaticZenRuleState().
        Log.d(TAG, "onSubscribe: $conditionId")
    }

    override fun onUnsubscribe(conditionId: Uri) {
        Log.d(TAG, "onUnsubscribe: $conditionId")
    }

    override fun onRequestConditions(relevance: Int) {
        Log.d(TAG, "onRequestConditions: $relevance")
    }

    companion object {
        private const val TAG = "DndConditionProvider"

        /**
         * The fixed condition id owned by this provider. `dnd_set` uses the same Uri both as the
         * rule's `conditionId` and as the `Condition` id passed to `setAutomaticZenRuleState`, as
         * the system requires the two to match.
         */
        val CONDITION_ID: Uri = Uri.parse("condition://com.andharness.ru/dnd")

        /** Rule name shown in the system "Do Not Disturb" settings screen. */
        const val RULE_NAME = "Меха: режим «Не беспокоить»"

        /** Meta-data key the system reads to limit how many rules one owner may create. */
        const val META_DATA_RULE_INSTANCE_LIMIT = "android.service.notification.RULE_INSTANCE_LIMIT"
    }
}
