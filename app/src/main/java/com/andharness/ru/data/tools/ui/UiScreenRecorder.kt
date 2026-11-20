package com.andharness.ru.data.tools.ui

import android.accessibilityservice.AccessibilityService
import com.andharness.ru.data.screenmap.ScreenMapStore
import com.andharness.ru.domain.screenmap.ScreenMapElements
import com.andharness.ru.domain.screenmap.ScreenMapTitles
import com.andharness.ru.domain.screenmap.ScreenSignature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Best-effort screen-map observation of the CURRENT screen (checklist §1 «Знание экранов»).
 * Resolves the foreground package and a stable signature from the active window root, maps the
 * notable interactive nodes, and hands the write to [scope] so file I/O never delays the tool
 * result. Never throws and never changes the tool outcome — the map is only a cache, and it is
 * deliberately not written on every gesture on the UI thread.
 *
 * The root node's `getPackageName()`/`getClassName()` identify the foreground window:
 * https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getPackageName()
 * https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getClassName()
 */
internal class UiScreenRecorder(
    private val screenMapStore: ScreenMapStore?,
    private val scope: CoroutineScope,
) {

    fun autoRecordScreen(service: AccessibilityService) {
        val store = screenMapStore ?: return
        runCatching {
            val root = service.rootInActiveWindow ?: return
            val appPackage = root.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
            val activityClass = root.className?.toString()
            val elements = AccessibilityTree.collect(root, SCREEN_MAP_NODES)
            val labels = elements.map { it.label }
            val signature = ScreenSignature.of(activityClass, labels)
            val title = ScreenMapTitles.guess(labels, activityClass)
            val notable = ScreenMapElements.notable(
                elements.map { element ->
                    ScreenMapElements.Raw(
                        text = element.text,
                        contentDescription = element.description,
                        resourceId = element.resourceId,
                        className = element.className,
                        clickable = element.clickable,
                        editable = element.editable,
                        scrollable = element.scrollable,
                    )
                },
            )
            scope.launch {
                runCatching {
                    store.record(appPackage, signature, notable, System.currentTimeMillis(), title)
                }
            }
        }
    }

    private companion object {
        /** Screen-map auto-record: bounded node read, so a huge hierarchy never slows the tool call. */
        const val SCREEN_MAP_NODES = 60
    }
}
