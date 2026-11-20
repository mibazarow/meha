package com.andharness.ru.di.toolhandlers

import android.content.Context
import com.andharness.ru.data.tools.archive.ArchiveToolsHandler
import com.andharness.ru.data.tools.connectivity.ConnectivityToolsHandler
import com.andharness.ru.data.tools.files.FilesToolsHandler
import com.andharness.ru.data.tools.ir.IrToolsHandler
import com.andharness.ru.data.tools.network.NetworkToolsHandler
import com.andharness.ru.data.tools.sim.SimToolsHandler
import com.andharness.ru.data.tools.system.DeviceExtraToolsHandler
import com.andharness.ru.data.tools.system.PowerToolsHandler
import com.andharness.ru.data.tools.system.SystemControlToolsHandler
import com.andharness.ru.data.tools.system.SystemToolsHandler
import com.andharness.ru.data.tools.usb.UsbToolsHandler
import com.andharness.ru.data.tools.vpn.VpnToolsHandler
import com.andharness.ru.domain.tools.ToolHandler
import com.andharness.ru.domain.ui.UiInteractionBridge
import okhttp3.OkHttpClient

/** Files, archive, network and device/system tool handlers (contiguous block of the frozen order). */
internal fun filesSystemToolHandlers(
    context: Context,
    okHttpClient: OkHttpClient,
    uiInteractionBridge: UiInteractionBridge,
): List<ToolHandler> =
    (FilesToolsHandler(context, uiInteractionBridge).handlers()
        + ArchiveToolsHandler(context).handlers()
        + NetworkToolsHandler(context, okHttpClient).handlers()
        + SystemToolsHandler(context).handlers()
        + SystemControlToolsHandler(context).handlers()
        + DeviceExtraToolsHandler(context).handlers()
        + PowerToolsHandler(context).handlers()
        + ConnectivityToolsHandler(context).handlers()
        + IrToolsHandler(context).handlers()
        + UsbToolsHandler(context).handlers()
        + VpnToolsHandler(context).handlers()
        + SimToolsHandler(context).handlers()
)
