package com.zillit.desktop.feature.costumesetsync.ui.screens

import com.zillit.desktop.feature.costumesetsync.domain.BudgetGroup
import com.zillit.desktop.feature.costumesetsync.domain.BudgetModel
import com.zillit.desktop.feature.costumesetsync.domain.PrintHtml.esc
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.datetime.toLocalDateTime

/*
 * The whole budget laid out the way a production budget prints: a cover, a top sheet of departments with
 * section totals and a grand total, then the detail pages account by account — as the HTML file Print / PDF
 * saves (the web renders it into a hidden print portal).
 */

/** The cover's dates as a printed budget writes them: "01 Oct 2026"; [long] gives "30 September 2026". 0 is unset. */
private fun coverDate(ms: Long, long: Boolean = false): String {
    if (ms == 0L) return ""
    val d = kotlinx.datetime.Instant
        .fromEpochMilliseconds(ms)
        .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date
    val month = d.month.name.lowercase().replaceFirstChar { it.uppercase() }.let {
        if (long) it else it.take(SHORT_MONTH)
    }
    return "${d.day.toString().padStart(2, '0')} $month ${d.year}"
}

private const val SHORT_MONTH = 3

/** The web's `.csync-bd` print rules (11px page, 26px cover title, ruled top sheet, details on a fresh page). */
private const val BD_STYLE = "<style>.bd{font-size:11px}" +
    ".bd .cover{padding-bottom:16px;margin-bottom:16px;border-bottom:2px solid #000}" +
    ".bd .cover h1{margin:0;font-size:26px}.bd .sub{margin-top:4px;font-weight:600;letter-spacing:.06em}" +
    ".bd .meta{display:flex;justify-content:space-between;margin-top:12px}.bd .r{text-align:right}" +
    ".bd table.top{width:100%;border-collapse:collapse;margin-bottom:24px}" +
    ".bd table.top th,.bd table.top td{padding:4px 6px;border-bottom:1px solid #e5e7eb;text-align:left}" +
    ".bd table.top th.r,.bd table.top td.r{text-align:right}" +
    ".bd table.top tr.total td{font-weight:700;border-top:1px solid #000}" +
    ".bd table.top tr.grand td{font-size:13px;font-weight:800;border-top:2px solid #000}" +
    ".bd .details{break-before:page;page-break-before:always}" +
    ".bd .label{margin-bottom:8px;font-size:14px;font-weight:700}" +
    ".bd .foot{margin-top:16px;font-size:10px;color:#6b7280;text-align:center}</style>"

private val COVER_DATES = listOf(
    "prep_start_date" to "csync_budget_prep_from",
    "start_date" to "csync_budget_shoot_from",
    "wrap_date" to "csync_budget_wrap",
)

private fun row(code: String, title: String, total: String, cls: String = "") =
    "<tr class=\"$cls\"><td>${esc(code)}</td><td>${esc(title)}</td><td class=\"r\">${esc(total)}</td></tr>"

@Suppress("CyclomaticComplexMethod")
private fun StringBuilder.appendCover(project: Rec?, name: String, currency: String) {
    val start = project?.long("start_date") ?: 0L
    val end = project?.long("end_date") ?: 0L
    val days = if (start != 0L && end != 0L) ((end - start) / MS_PER_DAY).toInt() + 1 else null
    append("<div class=\"bd\"><div class=\"cover\"><h1>${esc(name)}</h1>")
    append("<div class=\"sub\">${esc(t("csync_budget_word").uppercase())} · ")
    append("${esc(coverDate(System.currentTimeMillis(), long = true))}</div><div class=\"meta\"><div>")
    project?.str("type")?.takeIf { it.isNotEmpty() }?.let {
        append("<div>${esc(t(if (it == "EPISODIC") "csync_setup_series" else "csync_setup_feature"))}</div>")
    }
    project?.str("studio")?.takeIf { it.isNotEmpty() }?.let { append("<div>${esc(it)}</div>") }
    listOfNotNull(
        project?.str("city")?.ifEmpty { null },
        project?.str("country")?.ifEmpty { null }
    ).takeIf { it.isNotEmpty() }?.let {
        append("<div>${esc(it.joinToString(", "))}</div>")
    }
    append("</div><div class=\"r\">")
    COVER_DATES.forEach { (key, label) ->
        val ms = project?.long(key) ?: 0L
        if (ms != 0L) {
            append("<div>${esc(t(label))} ${esc(coverDate(ms))}")
            if (key == "start_date" && days != null) append(" · ${esc(t("csync_budget_n_days", "n" to days))}")
            append("</div>")
        }
    }
    if (currency.isNotEmpty()) append("<div>${esc(t("csync_budget_all_figures_in", "c" to currency))}</div>")
    append("</div></div></div>")
}

private fun StringBuilder.appendTopSheet(expenses: List<Rec>, currency: String) {
    fun total(lines: List<Rec>) = BudgetModel.sumByCurrency(lines, currency)
    val rows = BudgetModel.topSheetRows(expenses)
    val atlSections = BudgetModel.SECTIONS.filter { it.atl }
    val atl = rows.filter { r -> atlSections.any { BudgetModel.inSection(r, it) } }.flatMap { it.lines }
    val btl = expenses.filter { it !in atl }
    append("<table class=\"top\"><tr><th style=\"width:90px\">${esc(t("csync_budget_account"))}</th>")
    append("<th>${esc(t("csync_field_description"))}</th><th class=\"r\">${esc(t("csync_budget_total"))}</th></tr>")
    BudgetModel.SECTIONS.forEach { s ->
        val inS = rows.filter { BudgetModel.inSection(it, s) }
        if (inS.isEmpty()) return@forEach
        inS.forEach { append(row(it.code, it.title, total(it.lines))) }
        append(row("", t("csync_budget_section_${s.key}"), total(inS.flatMap { it.lines }), "total"))
    }
    rows.filter { r -> BudgetModel.SECTIONS.none { BudgetModel.inSection(r, it) } }.forEach {
        append(row(it.code, it.title, total(it.lines)))
    }
    append(row("", t("csync_budget_total_atl"), total(atl), "total"))
    append(row("", t("csync_budget_total_btl"), total(btl), "total"))
    append(row("", t("csync_budget_grand_total"), total(expenses), "grand"))
}

internal fun budgetHtml(project: Rec?, expenses: List<Rec>, groups: List<BudgetGroup>, currency: String): String {
    val name = project?.str("project_name")?.ifBlank { null } ?: t("csync_production")
    return buildString {
        append(BD_STYLE)
        appendCover(project, name, currency)
        appendTopSheet(expenses, currency)
        append("</table><div class=\"details\"><div class=\"label\">${esc(t("csync_budget_details"))}</div>")
        append(detailHtml(groups, currency))
        append("</div><div class=\"foot\">${esc(name)}</div></div>")
    }
}

private const val MS_PER_DAY = 86_400_000L

private fun StringBuilder.appendBudgetLine(l: Rec, currency: String) {
    val q = if (l.has("quantity")) l.double("quantity") else null
    val desc = l.str("description").ifBlank { l.first("account_name", "payee").ifBlank { "—" } }
    val mult = if (q != null) BudgetModel.fmtNum(if (l.has("multiplier")) l.double("multiplier") else 1.0) else ""
    append(
        "<tr><td></td><td>${esc(desc)}</td>" +
            "<td class=\"r\">${esc(BudgetModel.fmtNum(q))}</td><td>${esc(l.str("unit"))}</td>" +
            "<td class=\"r\">${esc(mult)}</td>" +
            "<td class=\"r\">${esc(BudgetModel.fmtNum(if (l.has("rate")) l.double("rate") else null))}</td>" +
            "<td class=\"r\">" +
            "${esc(BudgetModel.fmtAmount(l.double("amount"), l.str("currency").ifEmpty { currency }))}</td></tr>",
    )
}

private fun detailHtml(groups: List<BudgetGroup>, currency: String): String = buildString {
    fun total(lines: List<Rec>) = BudgetModel.sumByCurrency(lines, currency)
    val heads = listOf(
        t("csync_budget_account"),
        t("csync_field_description"),
        t("csync_budget_amt"),
        t("csync_budget_unit"),
        "X",
        t("csync_budget_rate"),
        t("csync_budget_subtotal"),
    )
    append("<table><tr>")
    append(heads.joinToString("") { "<th>${esc(it)}</th>" })
    append("</tr>")
    groups.forEach { g ->
        append("<tr class=\"group\"><td colspan=\"7\">${esc(g.title)}</td></tr>")
        BudgetModel.accountsOf(g.lines).forEach { a ->
            append("<tr class=\"account\"><td>${esc(a.code)}</td>")
            append("<td colspan=\"6\">${esc(a.name.ifBlank { t("csync_budget_no_account_name") })}</td></tr>")
            BudgetModel.payeesOf(a.lines).forEach { (payee, lines) ->
                if (payee.isNotEmpty()) {
                    append("<tr><td></td><td colspan=\"6\">${esc("${t("csync_budget_name_prefix")} $payee")}</td></tr>")
                }
                lines.forEach { appendBudgetLine(it, currency) }
            }
            append("<tr class=\"total\"><td></td><td colspan=\"5\">${esc(t("csync_budget_total"))}</td>")
            append("<td class=\"r\">${esc(total(a.lines))}</td></tr>")
        }
        append("<tr class=\"total\"><td></td><td colspan=\"5\">${esc("${t("csync_budget_total")} · ${g.title}")}</td>")
        append("<td class=\"r\">${esc(total(g.lines))}</td></tr>")
    }
    if (groups.size > 1) {
        append("<tr class=\"grand\"><td></td><td colspan=\"5\">${esc(t("csync_budget_grand_total"))}</td>")
        append("<td class=\"r\">${esc(total(groups.flatMap { it.lines }))}</td></tr>")
    }
    append("</table>")
}
