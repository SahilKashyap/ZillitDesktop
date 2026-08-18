package com.zillit.desktop.feature.home.domain

import com.zillit.desktop.core.localization.localised

/**
 * What a Home tab shows.
 *
 * Home is a tab strip over *units*, and the unit's identifier decides what the
 * tab renders — the web branches on `unit_name === 'calendar_label'` and
 * `identifier.includes('call_sheet')` inline, in a 566-line component. Naming
 * the three cases makes the branch testable and stops a fourth being added by
 * accident.
 */
enum class HomeUnitKind {
    /** A notice board: posts, newest last. The default. */
    Notices,

    /** The production calendar. */
    Calendar,

    /** A call sheet — a notice board that also has a history view. */
    CallSheet,
    ;

    companion object {
        fun of(identifier: String, unitName: String): HomeUnitKind = when {
            // Matched on `unit_name`, as the web does — the calendar unit's
            // identifier is not stable across productions.
            unitName.equals(CALENDAR_UNIT, ignoreCase = true) -> Calendar

            // `contains`, not equality: productions have `call_sheet_tool`,
            // `call_sheet_label` and per-unit variants.
            identifier.contains(CALL_SHEET, ignoreCase = true) -> CallSheet

            else -> Notices
        }

        const val CALENDAR_UNIT = "calendar_label"
        const val CALL_SHEET = "call_sheet"
    }
}

/**
 * One Home tab.
 *
 * `GET home/unit/user` (or `/admin` for admins). [canView] gates the tab
 * entirely; [canPost] gates the composer within it.
 */
data class HomeUnit(
    val id: String,
    val identifier: String,
    val unitName: String,
    val canView: Boolean = false,
    val canPost: Boolean = false,
    /**
     * `download_access` — gates the menu's Download on both phones (Android
     * `hasDownloadRights()`, iOS `hasDownloadAccess`). Opening a file to read
     * it is not gated: a crew member without this right can still *view* a
     * call sheet on either phone, only not save it.
     */
    val canDownload: Boolean = false,
    val enabled: Boolean = true,
) {
    val kind: HomeUnitKind get() = HomeUnitKind.of(identifier, unitName)

    /**
     * Tab label. The API sends translation keys, so this resolves them against
     * the loaded dictionary — falling back to a humanised key when the language
     * has not arrived yet.
     */
    val label: String get() = unitName.localised()
}

/**
 * The tabs to show, in server order.
 *
 * A unit the user cannot view is dropped rather than disabled: an unopenable
 * tab is an invitation to ask why it does nothing.
 */
fun List<HomeUnit>.visibleTabs(): List<HomeUnit> = filter { it.enabled && it.canView }
