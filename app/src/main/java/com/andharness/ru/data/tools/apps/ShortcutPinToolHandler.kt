package com.andharness.ru.data.tools.apps

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.net.Uri
import com.andharness.ru.domain.agent.ToolResult
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.service.ShortcutPinReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `shortcut_pin`: requests a pinned launcher shortcut and reports whether the user approved it.
 * `ShortcutManager.isRequestPinShortcutSupported()` + `requestPinShortcut(ShortcutInfo,
 * IntentSender)` + `createShortcutResultIntent(ShortcutInfo)`; the result intent is delivered to an
 * unexported manifest receiver only when the user approves pinning:
 * https://developer.android.com/reference/android/content/pm/ShortcutManager
 */
internal class ShortcutPinToolHandler(
    private val context: Context,
) : ToolHandler {

    override val name = "shortcut_pin"

    override suspend fun execute(arguments: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val id = (arguments["id"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (id.isEmpty()) return@withContext ToolResult(false, "Укажите id ярлыка.")
            val label = (arguments["label"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (label.isEmpty()) return@withContext ToolResult(false, "Укажите label ярлыка.")
            val intentUri = (arguments["intent_uri"] as? JsonPrimitive)?.content
                ?.trim()?.takeIf { it.isNotEmpty() }
            val shortcutManager = context.getSystemService(ShortcutManager::class.java)
                ?: return@withContext ToolResult(false, "ShortcutManager недоступен на устройстве.")
            if (!shortcutManager.isRequestPinShortcutSupported) {
                return@withContext ToolResult(
                    true,
                    "Лаунчер устройства не поддерживает закрепление ярлыков " +
                        "(isRequestPinShortcutSupported = false) — закрепить ярлык нельзя.",
                )
            }
            val target = if (intentUri != null) {
                Intent(Intent.ACTION_VIEW, Uri.parse(intentUri))
            } else {
                context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?: Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage(context.packageName)
            }
            val shortcut = ShortcutInfo.Builder(context, id)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIntent(target)
                .build()
            // The result intent is sent only on success; point it at our own unexported
            // receiver so the outcome of the pin dialog is observable.
            val callbackIntent = shortcutManager.createShortcutResultIntent(shortcut).apply {
                setClass(context, ShortcutPinReceiver::class.java)
                action = ShortcutPinReceiver.ACTION
            }
            val callback = PendingIntent.getBroadcast(
                context,
                ShortcutPinReceiver.REQUEST_CODE,
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            ShortcutPinReceiver.arm()
            val requested = try {
                shortcutManager.requestPinShortcut(shortcut, callback.intentSender)
            } catch (e: IllegalStateException) {
                ShortcutPinReceiver.disarm()
                return@withContext ToolResult(
                    false,
                    "Система не показала диалог закрепления: ${e.message ?: "нет foreground-активности или сервиса"}.",
                )
            }
            if (!requested) {
                ShortcutPinReceiver.disarm()
                return@withContext ToolResult(
                    false,
                    "Система отклонила запрос закрепления ярлыка (лаунчер не поддерживает или закрепление запрещено).",
                )
            }
            val pinned = ShortcutPinReceiver.awaitPinned()
            ToolResult(
                true,
                if (pinned) {
                    "Пользователь закрепил ярлык «$label» (id=$id) на домашнем экране."
                } else {
                    "Системный диалог закрепления ярлыка «$label» (id=$id) показан, но пользователь " +
                        "не подтвердил закрепление за ${ShortcutPinReceiver.TIMEOUT_MS / 1000} с " +
                        "(подтверждения от системы не пришло)."
                },
            )
        } catch (e: Exception) {
            ToolResult(false, "Ошибка: ${e.message}")
        }
    }
}
