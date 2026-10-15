package com.zillit.desktop.feature.notifications.domain

import com.zillit.desktop.core.badges.BadgeSections
import com.zillit.desktop.core.badges.wireToolToIdentifier

/**
 * Where a clicked notification row goes.
 *
 * Two shapes because the wire names two different things. An app section is
 * one of the seven the ledger groups by ([BadgeSections]) and lives at a fixed
 * workspace path; a film tool is named by the server, so only its grid
 * identifier can be decided here and the host turns that into a route (the
 * tools' paths are the tools' own business — see `ToolCatalogue`).
 */
sealed interface NotificationDestination {

    /** An app section — Home, Chat, Email, Settings, SOS — by its workspace path. */
    data class Section(val path: String) : NotificationDestination

    /** A film tool, as `project/tools` names it: `callsheet_tool`, `location_tool`. */
    data class FilmTool(val identifier: String) : NotificationDestination
}

/**
 * The route a notification row points at, or null for a row that points
 * nowhere this client can open.
 *
 * The web's `notificationRouteMatcher` reads the same three fields off the row
 * and switches on the section first, which is what this does. Android's row tap
 * is a no-op, so there is no phone behaviour to copy here.
 *
 * ## What `path` actually is
 *
 * Not a route. The wire's `path` is a braced chain of **label keys** —
 * `{tools_label/call_sheet_label}` — which is why [ProjectNotification.pathLabel]
 * translates it word by word for the row's own subtitle. So it cannot be handed
 * to the workspace as a path; it is read here only as the fallback for a row
 * that left `section` or `tool` empty, where its first section-shaped segment
 * names the section and the next segment the tool. The segment *after* that is
 * a unit or a level, and is deliberately ignored: nothing deeper than a tool is
 * addressable from a route yet.
 *
 * ## Why no unit
 *
 * A row carries `unit`, and [NotificationDestination] drops it. No tool reads a
 * unit off its route today ([com.zillit.desktop.core.workspace.WorkspaceRoute.Tool]'s
 * `unitId` is unread by every provider), and opening `Tool(path, unitId)` would
 * make a second window the workspace's dedupe cannot collapse into the tool's
 * own — two identical windows for one tool. The unit is on the wire when a
 * screen can use it.
 *
 * Unknown sections, and `global_label` — the bell's own segment, which is where
 * this row is already — return null. A click that cannot be placed must stay
 * quiet rather than open the nearest thing.
 */
fun matchNotificationRoute(target: NotificationTarget): NotificationDestination? {
    val segments = target.path.pathKeys()
    val section = target.section.ifBlank { segments.firstOrNull { it in BadgeSections.all }.orEmpty() }
    return when (section) {
        BadgeSections.CNC -> NotificationDestination.Section(NotificationSectionPaths.CHAT)
        BadgeSections.EMAIL -> NotificationDestination.Section(NotificationSectionPaths.EMAIL)
        BadgeSections.HOME -> NotificationDestination.Section(NotificationSectionPaths.HOME)
        BadgeSections.SETTINGS -> NotificationDestination.Section(NotificationSectionPaths.SETTINGS)
        BadgeSections.SOS -> NotificationDestination.Section(NotificationSectionPaths.SOS)
        BadgeSections.TOOLS -> target.toolKey(segments)
            ?.let { NotificationDestination.FilmTool(wireToolToIdentifier(it)) }
        else -> null
    }
}

/**
 * The tool this row belongs to, as the wire names it, or null when the row
 * names no tool at all — a `tools_label` row with nothing to open.
 */
private fun NotificationTarget.toolKey(segments: List<String>): String? =
    tool.takeIf { it.isNotBlank() }
        ?: segments.dropWhile { it in BadgeSections.all }.firstOrNull()

/** `{tools_label/call_sheet_label}` as the keys it is made of. */
private fun String.pathKeys(): List<String> =
    replace("{", "").replace("}", "").split('/').map(String::trim).filter(String::isNotEmpty)

/**
 * The sections' own paths, as their providers register them.
 *
 * Carried as strings because this module depends on none of those features —
 * the same way `feature:settings` carries the Production Setup page's path. So
 * a provider's path could be renamed and these would quietly point nowhere:
 * `NotificationRouteContractTest`, in the one module that sees both sides,
 * holds them together.
 */
object NotificationSectionPaths {
    /** `ChatToolProvider` — Chat & Calls. */
    const val CHAT = "/cnc"

    /** `EmailToolProvider` — the mailbox. */
    const val EMAIL = "/email"

    /** `HomeToolProvider` — the production's board. */
    const val HOME = "/home"

    /** `SettingsToolProvider`, where the approval queues live. */
    const val SETTINGS = "/settings"

    /** `SosToolProvider` — the alarms raised on set. */
    const val SOS = "/sos"
}
