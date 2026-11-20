package com.andharness.ru.data.tools.misc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.andharness.ru.data.clipboard.ClipboardHistoryStore
import com.andharness.ru.data.notifications.NotificationHelper
import com.andharness.ru.domain.chat.CurrentChatProvider
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.andharness.ru.data.tools.formatBytes

/**
 * Builds [ToolHandler]s for the misc tools: [clipboard_get], [clipboard_set], [device_info],
 * [notification_post].
 *
 * References:
 * - `ClipboardManager` via `context.getSystemService(ClipboardManager::class.java)`
 *   (`@SystemService(Context.CLIPBOARD_SERVICE)`):
 *   https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/content/ClipboardManager.java
 * - `ClipboardManager.getPrimaryClip()` returns `ClipData?` (null if the app is not the default IME
 *   or has no input focus):
 *   https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/content/ClipboardManager.java
 * - `ClipboardManager.setPrimaryClip(@NonNull ClipData clip)`:
 *   https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/content/ClipboardManager.java
 * - `ClipData.newPlainText(CharSequence label, CharSequence text)` (creates a plain-text clip):
 *   https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/content/ClipData.java
 * - `ClipData.getItemAt(int)` / `Item.getText()`:
 *   https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/content/ClipData.java
 * - `Build.MODEL`, `Build.MANUFACTURER`, `Build.VERSION.RELEASE`, `Build.VERSION.SDK_INT`:
 *   https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/os/Build.java
 * - `BatteryManager.getIntProperty(int)` + `BatteryManager.BATTERY_PROPERTY_CAPACITY` (remaining
 *   capacity as integer percent):
 *   https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/os/BatteryManager.java
 * - `StatFs(String)` + `getTotalBytes()` / `getAvailableBytes()` (blockCount*blockSize etc.):
 *   https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/os/StatFs.java
 * - `Environment.getDataDirectory()`:
 *   https://developer.android.com/reference/android/os/Environment#getDataDirectory()
 */
class MiscToolsHandler(
    private val context: Context,
    private val notifications: NotificationHelper = NotificationHelper(context),
    private val currentChat: CurrentChatProvider = CurrentChatProvider(),
    private val clipboardHistory: ClipboardHistoryStore? = null,
) {

    fun handlers(): List<ToolHandler> = listOf(
        object : ToolHandler {
            override val name = "clipboard_get"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    ToolResult(true, cm.primaryClip?.getItemAt(0)?.text?.toString() ?: "")
                } catch (e: Exception) {
                    Log.e("MiscToolsHandler", "clipboard_get failed", e)
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "clipboard_set"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val text = (arguments["text"] as? JsonPrimitive)?.content ?: ""
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("andharness", text))
                    // Capture point 1/2: everything the app itself copies is recorded, so the
                    // history is useful even though Android 10+ blocks background clipboard reads
                    // (https://developer.android.com/about/versions/10/privacy/changes#clipboard-data).
                    clipboardHistory?.record(text, sourcePackage = context.packageName)
                    ToolResult(true, "Скопировано: $text")
                } catch (e: Exception) {
                    Log.e("MiscToolsHandler", "clipboard_set failed", e)
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "device_info"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val bm = context.getSystemService(BatteryManager::class.java)
                    val batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

                    val sb = StringBuilder()
                    sb.appendLine("Модель: ${Build.MODEL}")
                    sb.appendLine("Производитель: ${Build.MANUFACTURER}")
                    sb.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    sb.appendLine("Заряд батареи: ${batteryLevel}%")

                    val statFs = StatFs(Environment.getDataDirectory().absolutePath)
                    val total = statFs.getTotalBytes()
                    val available = statFs.getAvailableBytes()
                    sb.appendLine("Память всего: ${formatBytes(total)}")
                    sb.appendLine("Память свободно: ${formatBytes(available)}")

                    ToolResult(true, sb.toString())
                } catch (e: Exception) {
                    Log.e("MiscToolsHandler", "device_info failed", e)
                    ToolResult(false, "Ошибка: ${e.message}")
                }
            }
        },
        object : ToolHandler {
            override val name = "notification_post"
            override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
                try {
                    val title = (arguments["title"] as? JsonPrimitive)?.content ?: ""
                    val text = (arguments["text"] as? JsonPrimitive)?.content ?: ""
                    // §20: the notification opens the chat it belongs to. Prefer an explicit
                    // `chat_id`, else fall back to the chat the agent is currently running in.
                    val chatId = (arguments["chat_id"] as? JsonPrimitive)?.content?.toLongOrNull()
                        ?: currentChat.chatId()
                    val messageId = (arguments["message_id"] as? JsonPrimitive)?.content?.toLongOrNull()
                    // One notification id (and request code) per post so the extras of different
                    // notifications never overwrite each other.
                    val notifId = System.currentTimeMillis().toInt()
                    // Goes through the shared gate (update.md bug #5): suppressed while the app is in
                    // the foreground on this very chat, and the honest outcome is reported back.
                    val posted = notifications.postDoneNotification(
                        text = text,
                        chatId = chatId,
                        messageId = messageId,
                        notificationId = notifId,
                        title = title,
                    )
                    if (posted) {
                        ToolResult(true, "Уведомление отправлено")
                    } else {
                        ToolResult(
                            true,
                            "Уведомление не показано: чат $chatId сейчас открыт на экране " +
                                "(уведомления по открытому чату подавляются). " +
                                "Можно проверить после сворачивания приложения или в другом чате.",
                        )
                    }
                } catch (e: SecurityException) {
                    Log.e("MiscToolsHandler", "notification_post failed", e)
                    ToolResult(false, "Нет разрешения на уведомления.")
                }
            }
        },
    )
}
