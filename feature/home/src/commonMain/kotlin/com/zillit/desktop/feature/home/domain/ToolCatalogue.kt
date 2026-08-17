package com.zillit.desktop.feature.home.domain

import androidx.compose.ui.graphics.vector.ImageVector
import com.zillit.desktop.core.localization.LabelKind
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.permissions.ToolAccess
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * How a backend tool identifier is presented and where it opens.
 */
data class ToolPresentation(
    val identifier: String,
    val label: String,
    val icon: ImageVector,
    val route: WorkspaceRoute,
)

/**
 * Maps `GET project/tools` identifiers onto UI.
 *
 * ## Open, not closed
 *
 * The Android app names 96 tool identifiers, and the backend adds more. A
 * closed `when` over them would render nothing for any tool shipped after this
 * build — so anything unrecognised still gets a row, with a humanised label, a
 * generic icon and a route derived from its identifier.
 *
 * The consequence is deliberate: **the server decides what exists, this decides
 * only how the ones we have designed for look.** A new backend tool appears in
 * the grid on the day it is switched on, looking plain, rather than not
 * appearing at all.
 */
object ToolCatalogue {

    /**
     * Icons and routes for tools we recognise by name.
     *
     * The icons are the Android client's own artwork (see [ZillitToolIcons]),
     * identifier for identifier as its `getToolIcon` maps them — so a tile
     * means the same thing on a phone and on this desktop.
     *
     * Only production tools appear here — `project/tools` returns the Film Tools
     * list (accounting, catering, forms…) and never mentions Home, Chat, Email or
     * Settings, which are app sections and live in `DefaultRailItems` instead.
     * Anything unrecognised still renders, via the fallback in [present].
     */
    private val known: Map<String, Pair<ImageVector, WorkspaceRoute>> = mapOf(
        "account_hub_tool" to
            (ZillitToolIcons.PurchaseOrder to WorkspaceRoute.Tool("/film-tools/account-hub")),
        "accounting_tool" to
            (ZillitToolIcons.Account to WorkspaceRoute.Tool("/film-tools/accounting")),
        "ad_dashboard_tool" to
            (ZillitToolIcons.AdDash to WorkspaceRoute.Tool("/film-tools/ad-dashboard")),
        "asset_report_tool" to
            (ZillitToolIcons.IcAssets to WorkspaceRoute.Tool("/film-tools/asset-report")),
        "box_schedule_tool" to
            (ZillitToolIcons.PreProduction to WorkspaceRoute.Tool("/film-tools/box-schedule")),
        // The creation tool — a different tile from `home_unit_call_sheet`
        // below, which is the home unit's published-sheet feed.
        "callsheet_tool" to
            (ZillitToolIcons.IcContinuity to WorkspaceRoute.Tool("/film-tools/call-sheet")),
        "card_expenses_tool" to
            (ZillitToolIcons.CardExpense to WorkspaceRoute.Tool("/film-tools/card-expenses")),
        "cash_expenses_tool" to
            (ZillitToolIcons.CashExpense to WorkspaceRoute.Tool("/film-tools/cash-expenses")),
        "casting_background_tool" to
            (ZillitToolIcons.BgCasting to WorkspaceRoute.Tool("/film-tools/casting-background")),
        "casting_main_tool" to
            (ZillitToolIcons.Casting to WorkspaceRoute.Tool("/film-tools/casting-main")),
        "casting_tool" to
            (ZillitToolIcons.Casting to WorkspaceRoute.Tool("/film-tools/casting")),
        "catering_tool" to
            (ZillitToolIcons.Catering to WorkspaceRoute.Tool("/film-tools/catering")),
        "confidential_info_tool" to
            (ZillitToolIcons.Info to WorkspaceRoute.Tool("/film-tools/confidential-info")),
        // Budget *Builder* — the embedded budget application (zillit_budget),
        // not the older main/department budget chat tools below, which share
        // only the word.
        "budget_builder_tool" to
            (ZillitToolIcons.Budget to WorkspaceRoute.Tool("/film-tools/budget-builder")),
        "continuity_tool" to
            (ZillitToolIcons.Continuity to WorkspaceRoute.Tool("/film-tools/continuity")),
        "deal_memo_tool" to
            (ZillitToolIcons.DealMemo to WorkspaceRoute.Tool("/film-tools/deal-memo")),
        "department_budget_tool" to
            (ZillitToolIcons.Budget to WorkspaceRoute.Tool("/film-tools/department-budget")),
        "distribution_tool" to
            (ZillitToolIcons.IcDistribution to WorkspaceRoute.Tool("/film-tools/distribution")),
        // A *different* tool from `distribution_tool` above, with its own row in
        // the permission grid and its own backend. The two names are one letter
        // apart in a list of ninety-six; conflating them grants the wrong
        // people the wrong library.
        "document_distribution_tool" to
            (
                ZillitToolIcons.IcDistribution to
                    WorkspaceRoute.Tool("/film-tools/document-distribution")
                ),
        "dod_tool" to
            (ZillitToolIcons.Dod to WorkspaceRoute.Tool("/film-tools/dod")),
        "drive_tool" to
            (ZillitIcons.Drive to WorkspaceRoute.Tool("/film-tools/drive")),
        "e_signature_tool" to
            (ZillitToolIcons.IcSignedDocument to WorkspaceRoute.Tool("/film-tools/e-signature")),
        "email_tool" to
            (ZillitToolIcons.Email to WorkspaceRoute.Tool("/film-tools/email")),
        "external_users_tool" to
            (ZillitToolIcons.IcInviteUser to WorkspaceRoute.Tool("/film-tools/external-users")),
        // The web's path is `form-signature`, singular — a desktop route that
        // spelled it `forms-and-signature` opened a placeholder while the
        // real tool sat unreachable.
        "forms_and_signature_tool" to
            (ZillitToolIcons.FormSignature to WorkspaceRoute.Tool("/film-tools/form-signature")),
        "generate_crew_list_tool" to
            (ZillitToolIcons.CrewList to WorkspaceRoute.Tool("/film-tools/generate-crew-list")),
        "home_unit_calendar" to
            (ZillitToolIcons.IcCalendar to WorkspaceRoute.Tool("/film-tools/home-unit-calendar")),
        "home_unit_call_sheet" to
            (ZillitToolIcons.IcContinuity to WorkspaceRoute.Tool("/film-tools/home-unit-call-sheet")),
        "home_unit_notices" to
            (ZillitToolIcons.InfoBlack to WorkspaceRoute.Tool("/film-tools/home-unit-notices")),
        "info_tool" to
            (ZillitToolIcons.Info to WorkspaceRoute.Tool("/film-tools/info")),
        "location_tool" to
            (ZillitToolIcons.Location to WorkspaceRoute.Tool("/film-tools/location")),
        // The pin map — a different tool from `location_tool` (the scouting
        // photo library) despite sharing a group and an icon family.
        "map_tool" to
            (ZillitToolIcons.Location to WorkspaceRoute.Tool("/film-tools/map")),
        "main_budget_tool" to
            (ZillitToolIcons.Budget to WorkspaceRoute.Tool("/film-tools/main-budget")),
        "payroll_tool" to
            (ZillitToolIcons.Payroll to WorkspaceRoute.Tool("/film-tools/payroll")),
        "permission_grid_tool" to
            (ZillitToolIcons.PostingRights to WorkspaceRoute.Tool("/film-tools/permission-grid")),
        "pre_production_tool" to
            (ZillitToolIcons.PreProduction to WorkspaceRoute.Tool("/film-tools/pre-production")),
        "production_report_tool" to
            (ZillitToolIcons.ProductionReport to WorkspaceRoute.Tool("/film-tools/production-report")),
        "production_tool" to
            (ZillitToolIcons.Production to WorkspaceRoute.Tool("/film-tools/production")),
        "purchase_order_tool" to
            (ZillitToolIcons.PurchaseOrder to WorkspaceRoute.Tool("/film-tools/purchase-order")),
        "recce_tool" to
            (ZillitToolIcons.Location to WorkspaceRoute.Tool("/film-tools/recce")),
        // Camera & Sound Report — a notice board with one tab per report
        // unit; the web mounts it at `/film-tools/reports`. Android has no
        // artwork of its own for it, so it borrows the report family's.
        "reports_tool" to
            (ZillitToolIcons.ProductionReport to WorkspaceRoute.Tool("/film-tools/reports")),
        "sa_portal_tool" to
            (ZillitToolIcons.AdDashboard to WorkspaceRoute.Tool("/film-tools/sa-portal")),
        "schedule_distribution_tool" to
            (ZillitToolIcons.Chedule to WorkspaceRoute.Tool("/film-tools/schedule-distribution")),
        "script_distribution_tool" to
            (ZillitToolIcons.Script to WorkspaceRoute.Tool("/film-tools/script-distribution")),
        "sides_tool" to
            (ZillitToolIcons.ScriptNote to WorkspaceRoute.Tool("/film-tools/sides")),
        "script_notes_tool" to
            (ZillitToolIcons.ScriptNote to WorkspaceRoute.Tool("/film-tools/script-notes")),
        "supporting_artistes_extras_tool" to
            (ZillitToolIcons.AdDashboard to WorkspaceRoute.Tool("/film-tools/supporting-artistes")),
        "timecard_tool" to
            (ZillitToolIcons.Timecard to WorkspaceRoute.Tool("/film-tools/timecard")),
        "transportation_tool" to
            (ZillitToolIcons.Transportation to WorkspaceRoute.Tool("/film-tools/transportation")),
        "wardrobe_background_tool" to
            (ZillitToolIcons.Wardrobe to WorkspaceRoute.Tool("/film-tools/wardrobe-background")),
        "wardrobe_main_tool" to
            (ZillitToolIcons.Wardrobe to WorkspaceRoute.Tool("/film-tools/wardrobe-main")),
        "weather_tool" to
            (ZillitToolIcons.IcWeather to WorkspaceRoute.Tool("/film-tools/weather")),
    )

    fun present(access: ToolAccess): ToolPresentation {
        val match = known[access.identifier]
        return ToolPresentation(
            identifier = access.identifier,
            // Tool names live in the `identifiers` dictionary, so that one is
            // asked first — `transportation_tool` is titled there, and only
            // incidentally present in the other two.
            label = Labels.translate(access.identifier, LabelKind.Identifiers),
            icon = match?.first ?: ZillitIcons.Tools,
            // A route derived from the identifier, so an unknown tool still gets
            // its own window rather than colliding with another's.
            route = match?.second ?: WorkspaceRoute.Tool("/tool/${access.identifier}"),
        )
    }
}
