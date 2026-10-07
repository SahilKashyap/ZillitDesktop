package com.zillit.desktop.feature.settings.admin.domain

/**
 * One row of the File Cabinet: a module whose records can go into the ZIP.
 *
 * @property identifier what the request carries — the web's `commonUnits[].identifier`.
 * @property name the label, already resolved for the reader.
 */
data class CabinetModule(val identifier: String, val name: String)

/**
 * The modules the File Cabinet can archive, in the web's order
 * (`FileCabinateModal.jsx` `commonUnits`).
 *
 * A module with a [tool] is offered only when the production has that tool
 * (`isToolVisible`); Home and Group Chat belong to no tool and are always
 * offered. The request names the module's [identifier], never the tool's.
 */
internal enum class CabinetRow(val identifier: String, val tool: String?) {
    Accounts("accounting", "accounting_tool"),
    AccountHub("account_hub", "account_hub_tool"),
    DocumentDistribution("document_distribution", "document_distribution_tool"),
    MainBudget("main_budget", "main_budget_tool"),
    DepartmentBudget("department_budget", "department_budget_tool"),
    CastingBackground("casting_background", "casting_background_tool"),
    CastingMain("casting_main", "casting_main_tool"),
    Catering("catering", "catering_tool"),
    ConfidentialInfo("confidential_info", "confidential_info_tool"),
    Continuity("continuity", "continuity_tool"),
    ScriptNotes("script_notes", "script_notes_tool"),
    GroupChat("group_chat", null),
    Home("home", null),
    Info("info", "info_tool"),
    Location("location", "location_tool"),
    ProductionReport("production_report", "production_report_tool"),
    Reports("reports", "reports_tool"),
    PurchaseOrder("purchase_order", "purchase_order_tool"),
    Dod("dod", "dod_tool"),
    ScheduleDistribution("schedule_distribution", "schedule_distribution_tool"),
    ScriptDistribution("script_distribution", "script_distribution_tool"),
    WardrobeBackground("wardrobe_background", "wardrobe_background_tool"),
    WardrobeMain("wardrobe_main", "wardrobe_main_tool"),
    Transportation("transportation", "transportation_tool"),
    Recce("recce", "recce_tool"),
}

/**
 * The rows this production offers.
 *
 * Tool rows are named by the tool's own label, which is what the web's
 * `unit_name` keys resolve to; [groupChat] and [home] are the two that are not
 * tools. A tool that is switched off, or absent, takes its row with it.
 */
internal fun cabinetModules(
    tools: List<ProductionTool>,
    toolName: (ProductionTool) -> String,
    groupChat: String,
    home: String,
): List<CabinetModule> {
    val enabled = tools.filter { it.enabled }.associateBy { it.identifier }
    return CabinetRow.entries.mapNotNull { row ->
        when {
            row == CabinetRow.GroupChat -> CabinetModule(row.identifier, groupChat)
            row == CabinetRow.Home -> CabinetModule(row.identifier, home)
            else -> enabled[row.tool]?.let { CabinetModule(row.identifier, toolName(it)) }
        }
    }
}
