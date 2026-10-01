package com.zillit.desktop.feature.costumesetsync.ui.screens

import com.zillit.desktop.feature.costumesetsync.domain.BudgetGroup
import com.zillit.desktop.feature.costumesetsync.domain.BudgetModel
import com.zillit.desktop.feature.costumesetsync.domain.PrintHtml.esc
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.ui.t

/*
 * The whole budget laid out the way a production budget prints: a cover, a top sheet of departments with
 * section totals and a grand total, then the detail pages account by account — as the HTML file Print / PDF
 * saves (the web renders it into a hidden print portal).
 */

private fun coverDate(ms: Long): String =
    if (ms == 0L) "" else com.zillit.desktop.feature.costumesetsync.domain.fmtDate(ms)

private fun row(code: String, title: String, total: String, cls: String = "") =
    "<tr class=\"$cls\"><td>${esc(code)}</td><td>${esc(title)}</td><td class=\"r\">${esc(total)}</td></tr>"

internal fun budgetHtml(project: Rec?, expenses: List<Rec>, groups: List<BudgetGroup>, currency: String): String {
    fun total(lines: List<Rec>) = BudgetModel.sumByCurrency(lines, currency)
    val rows = BudgetModel.topSheetRows(expenses)
    val atlSections = BudgetModel.SECTIONS.filter { it.atl }
    val atl = rows.filter { r -> atlSections.any { BudgetModel.inSection(r, it) } }.flatMap { it.lines }
    val btl = expenses.filter { it !in atl }
    val name = project?.str("project_name")?.ifBlank { null } ?: t("csync_production")
    val start = project?.long("start_date") ?: 0L
    val end = project?.long("end_date") ?: 0L
    val days = if (start != 0L && end != 0L) ((end - start) / MS_PER_DAY).toInt() + 1 else null
    return buildString {
        append("<h1>${esc(name)}</h1><div class=\"sub\">${esc(t("csync_budget_word").uppercase())}</div>")
        project?.str("type")?.takeIf { it.isNotEmpty() }?.let { append("<div>${esc(t(if (it == "EPISODIC") "csync_setup_series" else "csync_setup_feature"))}</div>") }
        project?.str("studio")?.takeIf { it.isNotEmpty() }?.let { append("<div>${esc(it)}</div>") }
        listOf("prep_start_date" to "csync_budget_prep_from", "start_date" to "csync_budget_shoot_from", "wrap_date" to "csync_budget_wrap").forEach { (key, label) ->
            val ms = project?.long(key) ?: 0L
            if (ms != 0L) {
                append("<div>${esc(t(label))} ${esc(coverDate(ms))}")
                if (key == "start_date" && days != null) append(" · ${esc(t("csync_budget_n_days", "n" to days))}")
                append("</div>")
            }
        }
        if (currency.isNotEmpty()) append("<div>${esc(t("csync_budget_all_figures_in", "c" to currency))}</div>")
        append("<table><tr><th>${esc(t("csync_budget_account"))}</th><th>${esc(t("csync_field_description"))}</th><th class=\"r\">${esc(t("csync_budget_total"))}</th></tr>")
        BudgetModel.SECTIONS.forEach { s ->
            val inS = rows.filter { BudgetModel.inSection(it, s) }
            if (inS.isEmpty()) return@forEach
            inS.forEach { append(row(it.code, it.title, total(it.lines))) }
            append(row("", t("csync_budget_section_${s.key}"), total(inS.flatMap { it.lines }), "total"))
        }
        rows.filter { r -> BudgetModel.SECTIONS.none { BudgetModel.inSection(r, it) } }.forEach { append(row(it.code, it.title, total(it.lines))) }
        append(row("", t("csync_budget_total_atl"), total(atl), "total"))
        append(row("", t("csync_budget_total_btl"), total(btl), "total"))
        append(row("", t("csync_budget_grand_total"), total(expenses), "grand"))
        append("</table><h2>${esc(t("csync_budget_details"))}</h2>")
        append(detailHtml(groups, currency))
    }
}

private const val MS_PER_DAY = 86_400_000L

private fun detailHtml(groups: List<BudgetGroup>, currency: String): String = buildString {
    fun total(lines: List<Rec>) = BudgetModel.sumByCurrency(lines, currency)
    append("<table><tr>${listOf(t("csync_budget_account"), t("csync_field_description"), t("csync_budget_amt"), t("csync_budget_unit"), "X", t("csync_budget_rate"), t("csync_budget_subtotal")).joinToString("") { "<th>${esc(it)}</th>" }}</tr>")
    groups.forEach { g ->
        append("<tr class=\"group\"><td colspan=\"7\">${esc(g.title)}</td></tr>")
        BudgetModel.accountsOf(g.lines).forEach { a ->
            append("<tr class=\"account\"><td>${esc(a.code)}</td><td colspan=\"6\">${esc(a.name.ifBlank { t("csync_budget_no_account_name") })}</td></tr>")
            BudgetModel.payeesOf(a.lines).forEach { (payee, lines) ->
                if (payee.isNotEmpty()) append("<tr><td></td><td colspan=\"6\">${esc("${t("csync_budget_name_prefix")} $payee")}</td></tr>")
                lines.forEach { l ->
                    val q = if (l.has("quantity")) l.double("quantity") else null
                    append(
                        "<tr><td></td><td>${esc(l.str("description").ifBlank { l.first("account_name", "payee").ifBlank { "—" } })}</td>" +
                            "<td class=\"r\">${esc(BudgetModel.fmtNum(q))}</td><td>${esc(l.str("unit"))}</td>" +
                            "<td class=\"r\">${esc(if (q != null) BudgetModel.fmtNum(if (l.has("multiplier")) l.double("multiplier") else 1.0) else "")}</td>" +
                            "<td class=\"r\">${esc(BudgetModel.fmtNum(if (l.has("rate")) l.double("rate") else null))}</td>" +
                            "<td class=\"r\">${esc(BudgetModel.fmtAmount(l.double("amount"), l.str("currency").ifEmpty { currency }))}</td></tr>",
                    )
                }
            }
            append("<tr class=\"total\"><td></td><td colspan=\"5\">${esc(t("csync_budget_total"))}</td><td class=\"r\">${esc(total(a.lines))}</td></tr>")
        }
        append("<tr class=\"total\"><td></td><td colspan=\"5\">${esc("${t("csync_budget_total")} · ${g.title}")}</td><td class=\"r\">${esc(total(g.lines))}</td></tr>")
    }
    if (groups.size > 1) append("<tr class=\"grand\"><td></td><td colspan=\"5\">${esc(t("csync_budget_grand_total"))}</td><td class=\"r\">${esc(total(groups.flatMap { it.lines }))}</td></tr>")
    append("</table>")
}
