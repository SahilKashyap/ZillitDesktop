package com.zillit.desktop.core.badges

/**
 * The pairs whose wire name and grid identifier do not follow the suffix
 * rule — the notification service's vocabulary predates the tools'.
 * `accounts_label` verified in `ToolsMappingTest` (the tile is *named* by
 * it on develop); `call_sheet_label` verified live 2026-08-19 — the ledger
 * says `call_sheet_label` while the grid says `callsheet_tool`, so the suffix
 * rule keyed the tile's six unread under a name no tile wears.
 */
private val IRREGULAR_WIRE_TOOLS = mapOf(
    "accounts_label" to "accounting_tool",
    "call_sheet_label" to "callsheet_tool",
)

/**
 * Every tool name the notification service speaks — iOS `ToolType` verbatim
 * (`FirebaseRealmTimeDB.swift:1766-1811`). The reverse map is built from
 * this because the wire has two shapes and a suffix rule cannot pick:
 * `location_tool_label` keeps its `_tool`, `forms_and_signature_label` never
 * had one. `document_distribution_label` is listed after the `_tool` variant
 * so the reverse prefers the form the dev rows actually carry.
 */
private val WIRE_TOOL_LABELS = listOf(
    "accounts_label", "catering_label", "info_label", "confidential_info_label",
    "production_label", "box_schedule_label", "purchase_order_label",
    "forms_and_signature_label", "deal_memo_label", "timecard_label",
    "continuity_label", "script_notes_label", "reports_label",
    "script_distribution_label", "script_distribution_pages_tool_label",
    "schedule_distribution_label", "schedule_distribution_pages_tool_label",
    "location_tool_label", "main_budget_label", "department_budget_label",
    "casting_main_tool_label", "casting_background_tool_label",
    "wardrobe_main_tool_label", "wardrobe_background_tool_label",
    "transportation_label", "schedule_oneline_label", "dod_label",
    "production_report_label", "map_label", "call_sheet_label", "drive_label",
    "account_hub_label", "cash_expenses_label", "card_expenses_label",
    "ad_dashboard_label", "supporting_artistes_extras_label",
    "e_signature_label", "sides_label", "recce_label", "cost_report_label",
    "pre_production_label",
    "document_distribution_tool", "document_distribution_label",
)

private val IDENTIFIER_TO_WIRE: Map<String, String> =
    WIRE_TOOL_LABELS.associateBy { wireToolToIdentifier(it) }

/**
 * The notification service's tool key, as the grid names it.
 *
 * The wire says `location_tool_label` or `forms_and_signature_label` (the
 * web passes exactly these as its `tool=` params); `project/tools` says
 * `location_tool` and `forms_and_signature_tool`. Strip the label suffix and
 * guarantee the `_tool` one — already-bare identifiers pass through — after
 * the irregular pairs, which no suffix rule reaches.
 */
fun wireToolToIdentifier(key: String): String {
    IRREGULAR_WIRE_TOOLS[key]?.let { return it }
    val bare = key.removeSuffix("_label")
    return if (bare.endsWith("_tool")) bare else bare + "_tool"
}

/**
 * The reverse — the wire name a read must be scoped by. A `notification:level:read`
 * naming `forms_and_signature_tool_label` (the old blanket `+"_label"` rule)
 * named nothing on the server, so fronting a tool never cleared its badge.
 * Unknown identifiers fall back to the majority shape, `X_tool` → `X_label`.
 */
fun identifierToWireTool(identifier: String): String =
    IDENTIFIER_TO_WIRE[identifier]
        ?: if (identifier.endsWith("_label")) {
            identifier
        } else {
            identifier.removeSuffix("_tool") + "_label"
        }
