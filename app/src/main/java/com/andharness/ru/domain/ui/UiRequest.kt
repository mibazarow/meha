package com.andharness.ru.domain.ui


/** A user-interaction the agent needs the UI to perform on its behalf. */
sealed interface UiRequest {
    data class OpenDocumentTree(val title: String? = null) : UiRequest
    data class OpenDocument(val mimeTypes: List<String> = listOf("*/*")) : UiRequest
    data class CreateDocument(
        val suggestedName: String,
        val mimeType: String = "application/octet-stream",
    ) : UiRequest

    /**
     * MediaStore write/delete/trash consent: launch [intentSender] from an Activity.
     *
     * See `IntentSender` — https://developer.android.com/reference/android/content/IntentSender
     */
    data class MediaConsent(val intentSender: android.content.IntentSender, val action: String) : UiRequest

    /**
     * Screen-capture consent: launch [intent] (built by
     * `MediaProjectionManager.createScreenCaptureIntent()`) from an Activity via
     * `StartActivityForResult`, then report the raw outcome back as [UiResult.ActivityResult].
     *
     * Consent is required for EVERY recording session: on Android 14+ the obtained
     * `MediaProjection` token may not be reused, so a fresh system dialog must be shown each time.
     * Reference: https://developer.android.com/media/grow/media-projection
     */
    data class ScreenCaptureConsent(val intent: android.content.Intent) : UiRequest

    /**
     * System-role request: launch [intent] (built by
     * `RoleManager.createRequestRoleIntent(role)`) from an Activity via `StartActivityForResult`,
     * then report the raw outcome back as [UiResult.ActivityResult]. [role] is the fully-qualified
     * role name (`android.app.role.<NAME>`) and is used only for diagnostics.
     *
     * Reuses the same ActivityResult mechanism as [ScreenCaptureConsent].
     * Reference: https://developer.android.com/reference/android/app/role/RoleManager
     */
    data class RoleRequest(val role: String, val intent: android.content.Intent) : UiRequest

    /**
     * Device-admin activation: launch [intent] (`DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN`)
     * from an Activity via `StartActivityForResult`, then report the raw outcome back as
     * [UiResult.ActivityResult] so the tool can verify the real state with
     * `DevicePolicyManager.isAdminActive(ComponentName)` and never claim a false success.
     *
     * The intent must be launched from an Activity WITHOUT `FLAG_ACTIVITY_NEW_TASK`: the official
     * device-admin sample uses `startActivityForResult`, and the intent is an explicit Activity
     * action. Samsung's `DeviceAdminAdd` rejects `FLAG_ACTIVITY_NEW_TASK`
     * ("Cannot start ADD_DEVICE_ADMIN as a new task").
     * References:
     * - `DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN` / `EXTRA_DEVICE_ADMIN` / `isAdminActive`:
     *   https://developer.android.com/reference/android/app/admin/DevicePolicyManager
     * - Device administration guide (launch from an Activity, confirm with `isAdminActive`):
     *   https://developer.android.com/guide/topics/admin/device-admin
     * - Activity Result APIs: https://developer.android.com/training/basics/intents/result
     */
    data class DeviceAdminRequest(val intent: android.content.Intent) : UiRequest

    /**
     * System print dialog: the UI must call
     * `PrintManager.print(jobName, adapter, attributes)` from an Activity context.
     *
     * `PrintManager.print()` may be called ONLY from an `Activity` — the platform checks
     * `mContext instanceof Activity` and otherwise throws
     * `IllegalStateException("Can print only from an activity")`. A tool only holds the application
     * context, so the call has to be made by the chat Activity (BUG-023).
     * References:
     * - `PrintManager.print()` (must be called from an Activity; throws `IllegalStateException`):
     *   https://developer.android.com/reference/android/print/PrintManager#print(java.lang.String,%20android.print.PrintDocumentAdapter,%20android.print.PrintAttributes)
     * - Printing custom documents (obtains `PrintManager` from the Activity context):
     *   https://developer.android.com/training/printing/custom-docs
     */
    data class PrintRequest(
        val jobName: String,
        val adapter: android.print.PrintDocumentAdapter,
        val attributes: android.print.PrintAttributes,
    ) : UiRequest
}
