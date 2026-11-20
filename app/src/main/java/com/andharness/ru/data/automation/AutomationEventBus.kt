package com.andharness.ru.data.automation

/**
 * In-process bridge from framework services (which are not Hilt-injectable in the same way as app
 * classes) to the automation engine.
 *
 * [com.andharness.ru.service.AccessibilityAutomationService] and
 * [com.andharness.ru.service.NotificationRelayService] publish events here; the
 * [AutomationTriggerManager] — a Hilt singleton — installs the [Listener] that forwards them to the
 * [com.andharness.ru.domain.automation.RuleEngine].
 *
 * Calls are non-blocking and thread-safe: the listener implementation must offload to its own
 * coroutine scope.
 */
object AutomationEventBus {

    interface Listener {
        fun onWindowStateChanged(packageName: String)
        fun onNotificationPosted(packageName: String, title: String, text: String)
    }

    @Volatile
    private var listener: Listener? = null

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun windowStateChanged(packageName: String) {
        listener?.onWindowStateChanged(packageName)
    }

    fun notificationPosted(packageName: String, title: String, text: String) {
        listener?.onNotificationPosted(packageName, title, text)
    }
}
