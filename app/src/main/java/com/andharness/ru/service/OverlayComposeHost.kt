package com.andharness.ru.service

import android.content.Context
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Hosts a Compose composition inside a view added directly with
 * [android.view.WindowManager.addView] — i.e. in an overlay window that has no Activity.
 *
 * A [ComposeView] only supports hierarchies that propagate a `LifecycleOwner` and a
 * `SavedStateRegistryOwner` (plus a `ViewModelStoreOwner` for `viewModel()`), so this class provides
 * the three owners itself and attaches them to the view through the documented view-tree setters.
 * It mirrors the initializer the platform's own SystemUI uses for its
 * WindowManager-added Compose windows.
 *
 * References:
 * - `ComposeView` requires propagated `LifecycleOwner` / `SavedStateRegistryOwner` —
 *   https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/ComposeView
 * - `setViewTreeLifecycleOwner` —
 *   https://developer.android.com/reference/kotlin/androidx/lifecycle/package-summary#(android.view.View).setViewTreeLifecycleOwner(androidx.lifecycle.LifecycleOwner)
 * - `setViewTreeSavedStateRegistryOwner` —
 *   https://developer.android.com/reference/kotlin/androidx/savedstate/package-summary#(android.view.View).setViewTreeSavedStateRegistryOwner(androidx.savedstate.SavedStateRegistryOwner)
 * - `SavedStateRegistryController.create(owner).performRestore(bundle)` —
 *   https://developer.android.com/reference/kotlin/androidx/savedstate/SavedStateRegistryController
 * - Platform reference implementation (SystemUI `ComposeInitializer`, the same
 *   `WindowManager.addView` use case):
 *   https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/SystemUI/src/com/android/systemui/compose/ComposeInitializer.kt
 *
 * Must be created, attached and destroyed on the main thread (the service callbacks run there).
 */
internal class OverlayComposeHost(
    context: Context,
    content: @Composable () -> Unit,
) : SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    private var attached = false
    private var destroyed = false

    override val viewModelStore: ViewModelStore = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    val view: ComposeView = ComposeView(context).apply {
        // Owners first: the composition is only created once the view is attached to a window, so
        // setting them here (before addView) is enough.
        setViewTreeLifecycleOwner(this@OverlayComposeHost)
        setViewTreeViewModelStoreOwner(this@OverlayComposeHost)
        setViewTreeSavedStateRegistryOwner(this@OverlayComposeHost)
        setBackgroundColor(Color.TRANSPARENT)
        setContent { content() }
    }

    /**
     * Restores saved state (there is none — the overlay is stateless across process death) and
     * moves the lifecycle to RESUMED so the composition starts. Called right before `addView`.
     *
     * Idempotent: a host is attached exactly once and then reused — its view may be removed from
     * and re-added to the [android.view.WindowManager] many times while the owners stay valid.
     * [SavedStateRegistryController.performRestore] may only run once, so this guard is required for
     * that reuse (see `OverlayAssistantService.openPanel`).
     */
    fun attach() {
        if (attached || destroyed) return
        attached = true
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /**
     * Detaches the owners and moves the lifecycle to DESTROYED, disposing the composition.
     *
     * Callers MUST have removed [view] from its window first, and must have done so in a way that
     * guarantees no measure/layout traversal of this view can still run afterwards (the service
     * uses [android.view.WindowManager.removeViewImmediate] on teardown). A ComposeView whose owners
     * are cleared while a traversal can still measure it makes Compose look up a
     * `ViewTreeLifecycleOwner` that no longer exists and crash with
     * `IllegalStateException: ViewTreeLifecycleOwner not found` (BUG-029). The view-tree setters are
     * reversed exactly like the platform example does.
     *
     * Idempotent: safe to call more than once.
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        // Also covers the case where the view was never attached (addView failed): the composition
        // must be disposed explicitly then, per the ComposeView contract.
        view.disposeComposition()
        viewModelStore.clear()
        view.setViewTreeLifecycleOwner(null)
        view.setViewTreeViewModelStoreOwner(null)
        view.setViewTreeSavedStateRegistryOwner(null)
    }
}
