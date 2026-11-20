package com.andharness.ru.domain.tools

import com.andharness.ru.domain.tools.defs.*

/** Aggregates the per-group tool definitions in `defs/` in the original order. */
object DefaultTools {

    fun all(): List<Tool> = FilesTools.defs() +
        TrashTools.defs() +
        ArchiveTools.defs() +
        NetworkTools.defs() +
        SystemTools.defs() +
        HardwareTools.defs() +
        ContactsTools.defs() +
        PhotosTools.defs() +
        AudioMediaTools.defs() +
        SmsTools.defs() +
        CameraTools.defs() +
        CallsTools.defs() +
        AppsTools.defs() +
        IntentTools.defs() +
        UiAutomationTools.defs() +
        NotificationsTools.defs() +
        NotesMemoryTools.defs() +
        ScheduleTools.defs() +
        MiscTools.defs() +
        ClipboardTools.defs() +
        TextTranslateTools.defs() +
        ChatsTools.defs() +
        LocationTools.defs() +
        SafTools.defs() +
        WatchTools.defs() +
        DocumentsTools.defs() +
        SettingsTools.defs() +
        AutomationTools.defs() +
        SystemNetworkExtrasTools.defs() +
        AppsNotificationsNetworkTools.defs() +
        NotificationTriageTools.defs() +
        AutomationSensorsUiTools.defs() +
        UiMacroTools.defs() +
        ScreenMapTools.defs() +
        SkillTools.defs() +
        CrashDiagnosticsTools.defs() +
        InsightsTools.defs() +
        InterceptOsintTools.defs() +
        IngestionTools.defs() +
        PersonTools.defs() +
        ComposeMessageTools.defs() +
        ScheduleMessageTools.defs() +
        ScheduleMessageListTools.defs() +
        SshTools.defs() +
        GitBridgeTools.defs() +
        RagTools.defs() +
        ProfileTools.defs() +
        TrustedSourceTools.defs() +
        UndoTools.defs() +
        McpTools.defs() +
        ObjectiveTools.defs() +
        CommunicationsTools.defs() +
        DeviceSearchTools.defs() +
        MediaActionTools.defs() +
        AudioVideoEditTools.defs()
}
