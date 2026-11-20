package com.andharness.ru.data.tools.sim

import android.content.Context
import com.andharness.ru.domain.tools.ToolHandler

/**
 * Builds one [ToolHandler] per SIM/eSIM tool: [sim_slots_list], [sim_numbers], [sim_set_default_sms],
 * [esim_status].
 *
 * All use public APIs only and are honest about what an unprivileged app cannot do. See each handler
 * for the exact platform limits.
 */
class SimToolsHandler(
    private val context: Context,
) {

    fun handlers(): List<ToolHandler> = listOf(
        SimSlotsListToolHandler(context),
        SimNumbersToolHandler(context),
        SimSetDefaultSmsToolHandler(context),
        EsimStatusToolHandler(context),
    )
}
