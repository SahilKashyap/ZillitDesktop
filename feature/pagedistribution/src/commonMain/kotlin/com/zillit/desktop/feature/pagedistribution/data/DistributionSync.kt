package com.zillit.desktop.feature.pagedistribution.data

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTool

/**
 * The wire events each distribution tool refreshes on. All names are what
 * the web's `listenerSocket.js` subscribes with `socket.on(...)` — its
 * pages listen to underscore re-emits of these, so the aliases in the page
 * sources are evidence of *which page* cares, never of the wire spelling.
 *
 *  - Script & Pages (`RenderScript.jsx:1415-1521`): `script:uploaded`,
 *    `script:page:uploaded`, `script:deleted`, `script:page:deleted`,
 *    `page:replaced`, `script:replaced` — wire names at
 *    `listenerSocket.js:1657-1675`.
 *  - Schedule Full & One Line (`ScheduleDistributionMain.jsx:1636-1717`):
 *    the `schedule:*` family (`listenerSocket.js:1680-1697`), the
 *    `oneline:*` family (`:1907-1915`), and `page:replaced` again — the
 *    schedule page redraws its page list on the shared replace event too.
 *  - Schedule D.O.D (`DoD.jsx:1114-1154`): the `dod:page:*` family
 *    (`listenerSocket.js:1894-1903`).
 *
 * `schedule:page:replaced` exists on the wire (`listenerSocket.js:1700`)
 * but no page listens to its re-emit, so it is deliberately not here.
 */
internal fun distributionSyncEvents(tool: DistributionTool): List<SocketEventName> =
    when (tool.toolIdentifier) {
        "script_distribution_tool" -> listOf(
            "script:uploaded",
            "script:page:uploaded",
            "script:deleted",
            "script:page:deleted",
            "page:replaced",
            "script:replaced",
        )
        "schedule_distribution_tool" -> listOf(
            "schedule:uploaded",
            "schedule:page:uploaded",
            "schedule:deleted",
            "schedule:page:deleted",
            "schedule:replaced",
            "page:replaced",
            "oneline:uploaded",
            "oneline:replaced",
            "oneline:deleted",
        )
        "dod_tool" -> listOf(
            "dod:page:uploaded",
            "dod:page:deleted",
            "dod:page:replaced",
            "dod:page:moved",
        )
        else -> emptyList()
    }.map(::SocketEventName)
