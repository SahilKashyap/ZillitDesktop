package com.zillit.desktop.feature.costumesetsync.ui.screens

import com.zillit.desktop.feature.costumesetsync.domain.DayKeys
import com.zillit.desktop.feature.costumesetsync.domain.PrintHtml.esc
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.ReportsModel
import com.zillit.desktop.feature.costumesetsync.domain.fmtMoney
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum

/*
 * What Print / PDF saves for the open Reports tab: the heading and the tab's report
 * as an HTML file (the web prints a hidden copy of the same bodies).
 */

private fun table(headers: List<String>, rows: List<List<String>>, rightFrom: Int = -1): String = buildString {
    append("<table><tr>${headers.joinToString("") { "<th>${esc(it)}</th>" }}</tr>")
    rows.forEach { r -> append("<tr>" + r.mapIndexed { i, c -> "<td${if (rightFrom in 0..i) " class=\"r\"" else ""}>${esc(c)}</td>" }.joinToString("") + "</tr>") }
    append("</table>")
}

private fun head(): String = "<h1>${esc(t("csync_reports_title"))}</h1><div class=\"muted\">${esc(t("csync_rpt_sub"))}</div>"

internal fun dailyReportHtml(data: Rec, day: String, finance: Boolean, currency: String): String {
    val s = data.rec("summary") ?: Rec.Empty
    val stats = buildList {
        add(t("csync_rpt_scenes") to s.str("scenes"))
        add(t("csync_rpt_returned") to s.str("returned"))
        add(t("csync_rpt_cleaning") to s.str("cleaning"))
        add(t("csync_rpt_alteration") to s.str("alteration"))
        add(t("csync_rpt_damaged") to s.str("damaged"))
        add(t("csync_rpt_missing") to s.str("missing"))
        if (finance) add(t("csync_rpt_spend_today") to fmtMoney(s.double("spend"), currency))
    }
    return head() + "<h2>${esc(t("csync_rpt_daily_title", "name" to data.rec("project")?.str("name").orEmpty()))}</h2>" +
        "<div class=\"muted\">${esc(DayKeys.medium(day))} · ${esc(t("csync_rpt_shooting_day", "n" to (data.rec("project")?.long("shooting_day") ?: 0L)))}</div>" +
        "<p>${esc(stats.joinToString(" · ") { "${it.first}: ${it.second.ifBlank { "0" }}" })}</p>" +
        "<h2>${esc(t("csync_rpt_scenes_card"))}</h2>" +
        table(
            listOf(t("csync_sc"), t("csync_field_name"), t("csync_field_location"), t("csync_field_status"), t("csync_rpt_col_characters")),
            data.recs("scenes").map { listOf(it.str("number"), it.str("name"), it.str("location"), tEnum(it.str("status")), ReportsModel.sceneCast(it)) },
        ) + "<h2>${esc(t("csync_rpt_cleaning_card"))}</h2>" +
        table(
            listOf(t("csync_rpt_col_asset"), t("csync_field_description"), t("csync_field_status")),
            data.recs("cleaning").map { listOf(it.rec("costume")?.str("asset_number").orEmpty(), "${it.rec("costume")?.str("name").orEmpty()} — ${it.str("problem")} · ${tEnum(it.str("cleaning_type"))}", tEnum(it.str("status"))) },
        ) + "<h2>${esc(t("csync_rpt_alt_missing_card"))}</h2>" +
        table(
            listOf(t("csync_rpt_col_asset"), t("csync_field_description"), t("csync_field_status")),
            data.recs("alterations").map { listOf(it.rec("costume")?.str("asset_number").orEmpty(), "${it.rec("costume")?.str("name").orEmpty()} — ${it.str("issue")} → ${it.str("required_work")}", tEnum(it.str("status"))) } +
                data.recs("missing").map { listOf(it.rec("costume")?.str("asset_number").orEmpty(), "${it.rec("costume")?.str("name").orEmpty()} — ${t("csync_rpt_last_seen", "x" to it.str("last_seen_location").ifBlank { "—" })}", tEnum("MISSING")) },
        )
}

internal fun inventoryReportHtml(rows: List<Rec>, finance: Boolean, currency: String): String {
    val headers = listOf(
        t("csync_rpt_col_asset"), t("csync_field_description"), t("csync_field_category"), t("csync_field_size"), t("csync_field_character"),
        t("csync_field_source"), t("csync_field_vendor"),
    ) + (if (finance) listOf(t("csync_rpt_col_cost")) else emptyList()) + listOf(t("csync_field_status"), t("csync_field_location"))
    return head() + table(
        headers,
        rows.map { r ->
            listOf(
                r.str("asset"), r.str("name") + ReportsModel.qtySuffix(r.long("quantity")),
                tEnum(r.str("category")) + r.str("type").let { if (it.isBlank()) "" else " / $it" }, r.str("size"), r.str("character"), tEnum(r.str("source")), r.str("vendor"),
            ) + (if (finance) listOf(if (r.has("purchase_cost")) fmtMoney(r.double("purchase_cost"), currency) else "") else emptyList()) +
                listOf(tEnum(r.str("status")), r.str("location"))
        },
    )
}

internal fun wrapReportHtml(wrap: Rec?): String = head() + ReportsModel.wrapGroups(wrap).joinToString("") { (source, items) ->
    "<h2>${esc(tEnum(source))} (${items.size})</h2>" + table(
        listOf(t("csync_rpt_col_asset"), t("csync_field_description"), t("csync_field_character"), t("csync_field_vendor"), t("csync_field_status"), t("csync_field_location")),
        items.map { c ->
            listOf(
                c.str("asset_number"), c.str("name") + ReportsModel.qtySuffix(c.long("quantity")), c.rec("character")?.str("name").orEmpty(),
                c.rec("vendor")?.str("name").orEmpty(), tEnum(c.str("status")), c.str("location"),
            )
        },
    )
}
