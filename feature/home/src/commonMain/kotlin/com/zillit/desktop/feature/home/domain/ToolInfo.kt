package com.zillit.desktop.feature.home.domain

import com.zillit.desktop.core.strings.S

/**
 * What a tool is for — the text behind every tile's ⓘ.
 *
 * The web's own wording and rules (`pages/FilmTools/useAvailableFilmTools.js`,
 * each entry's `tooltip.message`), so the desktop explains a tool exactly as
 * the browser does. Its translations came across with it into the desktop
 * catalogue; where its English matched an Android string word for word, that
 * string (and its translations) is reused.
 *
 * Every tool gets an answer. Where the web has nothing to say — an empty
 * message, a key it never defined, a tool it does not list — Android's
 * description stands in, then a line written for the desktop, and for a tool
 * shipped after this build, a plain generic one.
 */
fun toolDescription(identifier: String, label: String, viewer: ToolInfoViewer): ToolDescription {
    // The web's matcher: the FIRST entry, in list order, whose name the
    // identifier contains (`identifier.includes(name)`). Order matters —
    // `account_hub` must win over `account`, `confidential_info` over `info`,
    // `pre_production_tool` over `production_tool`.
    val web = WEB.firstOrNull { identifier.contains(it.name) }?.pick?.invoke(viewer)
    if (web != null) {
        return if (web in NAMES_THE_TOOL) ToolDescription(web, label) else ToolDescription(web)
    }
    FALLBACK[identifier]?.invoke(viewer)?.let { return ToolDescription(it) }
    return ToolDescription(S.desktop_tool_info_generic, label)
}

/** The facts the web's tooltips branch on. */
data class ToolInfoViewer(
    val isAdmin: Boolean = false,
    /** A non-film production (`project_type_id == "other"`): staff, not crew. */
    val isOtherProject: Boolean = false,
    val departmentIdentifier: String? = null,
    /** This user's posting right on a tool, as the server sent it. */
    val canPost: (identifier: String) -> Boolean = { false },
)

/** A description: its string key, and the tool's name when the text has a slot for it. */
data class ToolDescription(val key: String, val toolName: String? = null)

private class WebEntry(val name: String, val pick: (ToolInfoViewer) -> String?)

private fun admin(admin: String, crew: String): (ToolInfoViewer) -> String? = { if (it.isAdmin) admin else crew }

private fun always(key: String?): (ToolInfoViewer) -> String? = { key }

/**
 * `sectionList`, in its order, with each entry's message. A null pick is the
 * web saying nothing (an empty message, an undefined key) — the fallbacks
 * answer for it.
 */
private val WEB: List<WebEntry> = listOf(
    WebEntry("account_hub", always(S.desktop_tool_info_account_hub_tool_tooltip)),
    WebEntry("account", admin(S.desktop_tool_info_accounts_info_admin, S.desktop_tool_info_accounts_info_user)),
    WebEntry("asset_report", always(S.desktop_tool_info_asset_register_tool_tooltip)),
    WebEntry("catering", always(S.desktop_tool_info_catering_info_admin)),
    WebEntry("confidential_info") { viewer ->
        when {
            viewer.isOtherProject -> S.desktop_tool_info_confidential_info_other
            viewer.isAdmin -> S.confidential_info_new
            else -> S.confidential_info_user
        }
    },
    WebEntry(
        "continuity",
        admin(S.desktop_tool_info_continuity_info_admin_user, S.desktop_tool_info_continuity_info_user),
    ),
    WebEntry("forms_and_signature", admin(S.form_signature_info_admin, S.desktop_tool_info_formsignature_user)),
    WebEntry(
        "generate_crew_list",
        admin(S.desktop_tool_info_generatecrewlist_admin, S.desktop_tool_info_generatecrewlist_user),
    ),
    WebEntry("info") { viewer ->
        when {
            viewer.isAdmin && viewer.isOtherProject -> S.desktop_tool_info_info_admin_other_project
            viewer.isAdmin -> S.info_admin
            viewer.isOtherProject -> S.desktop_tool_info_info_user_other_project
            else -> S.desktop_tool_info_info_user
        }
    },
    WebEntry(
        "permission_grid",
        admin(S.view_permission_grid_module_title, S.desktop_tool_info_permission_grid_info_user),
    ),
    WebEntry("pre_production_tool", admin(S.desktop_tool_info_pre_production_info_admin, S.production_info_user)),
    WebEntry("production_tool", admin(S.desktop_tool_info_production_info_admin, S.production_info_user)),
    WebEntry("purchase_order", always(S.desktop_tool_info_purchase_order_tool_tooltip)),
    WebEntry("card_expenses", always(S.desktop_tool_info_card_expenses_tool_tooltip)),
    WebEntry("cash_expenses", always(S.desktop_tool_info_cash_expenses_tool_tooltip)),
    // `resolveDealMemoRights(...).canPost`: a posting right, or the accounts
    // department, who always deal.
    WebEntry("deal_memo") { viewer ->
        val deals = viewer.isAdmin || viewer.canPost(DEAL_MEMO) ||
            viewer.departmentIdentifier?.contains("accounts") == true
        if (deals) S.desktop_tool_info_deal_memo_info_admin else S.desktop_tool_info_deal_memo_info_user
    },
    WebEntry("timecard", always(S.desktop_tool_info_timecard_tool_tooltip)),
    WebEntry("payroll", always(S.desktop_tool_info_payroll_tool_tooltip)),
    WebEntry("cost_report", always(S.desktop_tool_info_cost_report_tool_tooltip)),
    WebEntry("location", admin(S.location_info_new, S.location_info_user)),
    WebEntry("recce", always(S.recce_module_title)),
    WebEntry("weather", always(S.weather_info_admin_user)),
    // The web's message is an empty template string.
    WebEntry("wardrobe_tool", always(null)),
    WebEntry(
        "script_distribution",
        admin(S.desktop_tool_info_script_distribution_info_admin, S.script_distribution_info_user),
    ),
    // The web gives crew `confidential_info_user` here — the Main Cast list's
    // text, a slip; its own `schedule_distribution_info_user` is the schedule's.
    WebEntry("schedule_distribution", admin(S.schedule_distribution_info_admin, S.schedule_distribution_info_user)),
    WebEntry("casting_main_tool", admin(S.desktop_tool_info_casting_main_admin, S.main_cast_info_user)),
    WebEntry("casting_background_tool", admin(S.background_cast_info_admin, S.background_cast_info_user)),
    WebEntry("script_notes_tool", always(S.continuity_notes_info_admin_user)),
    WebEntry("wardrobe_main_tool", admin(S.wardrobe_main_info_admin, S.wardrobe_main_info_user)),
    WebEntry("wardrobe_background_tool", admin(S.wardrobe_background_info_admin, S.wardrobe_background_info_user)),
    // The web reuses the department budget's words for the main budget;
    // Android's describe the main budget itself.
    WebEntry("main_budget_tool", admin(S.main_budget_info_admin, S.main_budget_info_user)),
    // The web's message is the bare word "budget".
    WebEntry("department_budget_tool", always(null)),
    WebEntry("budget_builder_tool", always(S.desktop_tool_info_budget_builder_tool_tooltip)),
    WebEntry("transportation_tool", always(S.txt_tool_transportation_info)),
    WebEntry("production_report_tool") { viewer ->
        when {
            viewer.isAdmin -> S.production_report_info_admin
            viewer.canPost(PRODUCTION_REPORT) -> S.production_report_info_for_second_ad
            else -> S.production_report_info_admin
        }
    },
    WebEntry("dod_tool", always(S.desktop_tool_info_dod_tooltip)),
    WebEntry("external_users_tool", always(S.external_user_info)),
    WebEntry("box_schedule_tool", always(S.box_production_module_title)),
    WebEntry("document_distribution_tool", always(S.document_distribution_module_title)),
    WebEntry("sides_tool", always(S.desktop_tool_info_sides_tool_tooltip)),
    WebEntry("costume_set_sync_tool", always(S.desktop_tool_info_costume_set_sync_tool_tooltip)),
    WebEntry("tasks_tool", always(S.desktop_tool_info_tasks_tool_tooltip)),
    WebEntry("ad_dashboard_tool", always(S.desktop_tool_info_ad_dashboard_tool_tooltip)),
    WebEntry("supporting_artistes_extras_tool", always(S.desktop_tool_info_supporting_artistes_tool_tooltip)),
    // An empty message on the web.
    WebEntry("distribution_tool", always(null)),
    WebEntry("map_tool", always(S.map_module_title)),
    WebEntry("drive_tool", always(S.drive_module_title)),
    // `reports_info_user` is defined in none of the web's languages.
    WebEntry("reports_tool", always(null)),
    // `canAuthorTool`: the posting right, for the call sheet.
    WebEntry("callsheet_tool") { viewer ->
        if (viewer.canPost(CALL_SHEET)) {
            S.desktop_tool_info_call_sheet_info_admin
        } else {
            S.desktop_tool_info_call_sheet_info_user
        }
    },
    WebEntry("e_signature", admin(S.e_signatue_module_title, S.desktop_tool_info_e_signature_info_user)),
)

/** The crew list's text names the list itself — "Crew List", or "Staff List" on a non-film production. */
private val NAMES_THE_TOOL = setOf(
    S.desktop_tool_info_generatecrewlist_admin,
    S.desktop_tool_info_generatecrewlist_user,
)

/** Where the web is silent: Android's words first, then the desktop's own. */
private val FALLBACK: Map<String, (ToolInfoViewer) -> String> = mapOf(
    "distribution_tool" to { _ -> S.distribution_list_info },
    "department_budget_tool" to admin(S.department_budget_info_admin, S.department_budget_info_user).nonNull(),
    "email_tool" to { _ -> S.email_info_admin_user },
    "casting_tool" to admin(S.main_cast_info_admin, S.main_cast_info_user).nonNull(),
    // The same artiste portal as `supporting_artistes_extras_tool`, as Android names it.
    "sa_portal_tool" to { _ -> S.desktop_tool_info_supporting_artistes_tool_tooltip },
    "invoices_tool" to { _ -> S.desktop_tool_info_invoices },
    "reports_tool" to { _ -> S.desktop_tool_info_camera_sound_report },
    "ad_report_tool" to { _ -> S.desktop_tool_info_ad_report },
    "wrap_report_tool" to { _ -> S.desktop_tool_info_wrap_report },
    "wardrobe_tool" to { _ -> S.desktop_tool_info_wardrobe },
    "zillit_draft" to { _ -> S.desktop_tool_info_zillit_draft },
)

private fun ((ToolInfoViewer) -> String?).nonNull(): (ToolInfoViewer) -> String = { invoke(it).orEmpty() }

private const val DEAL_MEMO = "deal_memo_tool"
private const val PRODUCTION_REPORT = "production_report_tool"
private const val CALL_SHEET = "callsheet_tool"
