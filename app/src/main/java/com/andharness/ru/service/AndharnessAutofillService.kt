package com.andharness.ru.service

import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.util.Log
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.andharness.ru.R
import com.andharness.ru.data.autofill.AutofillHints
import com.andharness.ru.data.autofill.AutofillProfile
import com.andharness.ru.data.autofill.AutofillProfileStore

/**
 * Minimal, honest autofill service (backlog #15).
 *
 * It fills ONLY the user's own name / phone / e-mail from the locally stored owner profile
 * (see [AutofillProfileStore]). It deliberately does NOT offer other people's data, addresses or
 * cards, and it never saves anything typed into other apps: [onSaveRequest] is a documented no-op.
 * An empty/absent profile yields no datasets, so the service stays invisible until configured. The
 * user must enable it in system settings (ANDharness appears in the autofill picker once this service
 * is declared); the tools route that picker through `Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE`.
 *
 * The framework calls this class in the system process, so every path is wrapped so a bug can never
 * crash the OS: exactly one callback is invoked, with `null` on any failure.
 *
 * A single dataset containing all matched fields is returned, so one tap fills the whole form.
 *
 * References:
 * - AutofillService / onFillRequest / onSaveRequest:
 *   https://developer.android.com/reference/android/service/autofill/AutofillService
 * - FillResponse / Dataset / AutofillValue / AutofillId / ViewNode.getAutofillHints:
 *   https://developer.android.com/reference/android/service/autofill/FillResponse
 *   https://developer.android.com/reference/android/service/autofill/Dataset
 *   https://developer.android.com/reference/android/view/autofill/AutofillId
 *   https://developer.android.com/reference/android/app/assist/AssistStructure.ViewNode#getAutofillHints()
 * - Framework guide (traversal + presentation requirement):
 *   https://developer.android.com/identity/autofill/autofill-services
 */
class AndharnessAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        // Build inside try/catch and answer exactly once afterwards, so no exception can skip the
        // mandatory callback (an unanswered request is eventually discarded by the system) and no
        // exception can escape into the system process.
        val response = try {
            buildResponse(request)
        } catch (t: Throwable) {
            Log.w(TAG, "autofill fill request failed", t)
            null
        }
        callback.onSuccess(response)
    }

    /**
     * Honest no-op: the service only fills the owner's already-known profile and never captures,
     * stores or uploads values entered into third-party apps.
     */
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        callback.onSuccess()
    }

    // The 3-argument setValue is deprecated on API 33+ (in favour of Field/inline presentations)
    // but remains the only presentation-carrying overload that works from API 26, which is minSdk.
    @Suppress("DEPRECATION")
    private fun buildResponse(request: FillRequest): FillResponse? {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return null
        val profile = AutofillProfileStore(applicationContext).load()
        if (profile.isEmpty) return null
        val values = LinkedHashMap<AutofillId, String>()
        traverse(structure, profile, values)
        if (values.isEmpty()) return null
        val dataset = Dataset.Builder().apply {
            values.forEach { (id, value) ->
                setValue(id, AutofillValue.forText(value), presentation(value))
            }
        }.build()
        return FillResponse.Builder().addDataset(dataset).build()
    }

    @Suppress("DEPRECATION")
    private fun traverse(
        structure: AssistStructure,
        profile: AutofillProfile,
        out: MutableMap<AutofillId, String>,
    ) {
        // getWindowNodeCount()/getWindowNodeAt() are the API 26-compatible accessors (getWindowNodes
        // is API 29+); minSdk here is 26, hence the deprecation suppression.
        val windows = structure.windowNodeCount
        for (i in 0 until windows) {
            val root = structure.getWindowNodeAt(i)?.rootViewNode ?: continue
            visit(root, profile, out, NodeBudget())
        }
    }

    /** Node budget keeps a pathological view tree from making the system request time out. */
    private class NodeBudget {
        var remaining = MAX_NODES
    }

    private fun visit(
        node: AssistStructure.ViewNode?,
        profile: AutofillProfile,
        out: MutableMap<AutofillId, String>,
        budget: NodeBudget,
    ) {
        if (node == null || budget.remaining <= 0) return
        budget.remaining--
        val id = node.autofillId
        if (id != null) {
            val field = AutofillHints.match(node.autofillHints, node.hint ?: node.text?.toString())
            val value = field?.let { profile.valueFor(it) }
            if (value != null) out[id] = value
        }
        val children = node.childCount
        for (i in 0 until children) visit(node.getChildAt(i), profile, out, budget)
    }

    /**
     * One-line dataset presentation. A dataset shown to the user must carry a RemoteViews
     * presentation (on API 26-27 the presentation-less `Dataset.Builder.setValue(id, value)` throws
     * `IllegalStateException`), so every value is set with the 3-argument overload. The layout is a
     * single `TextView`, which RemoteViews supports.
     * https://developer.android.com/reference/android/widget/RemoteViews
     */
    private fun presentation(value: String): RemoteViews =
        RemoteViews(packageName, R.layout.autofill_dataset).apply {
            setTextViewText(R.id.autofill_dataset_text, value)
        }

    private companion object {
        private const val TAG = "Andharness"

        /** Upper bound on visited nodes per fill request. */
        private const val MAX_NODES = 500
    }
}
