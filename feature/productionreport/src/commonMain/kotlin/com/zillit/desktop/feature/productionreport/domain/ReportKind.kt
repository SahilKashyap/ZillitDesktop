package com.zillit.desktop.feature.productionreport.domain

/**
 * The three report tools that share this engine.
 *
 * The web ships AD Report and Wrap Report as the production-report editor
 * with a different template and `shared.reportType` set — and no backend of
 * their own ("frontend scaffolding ready, backend not yet implemented"). The
 * desktop keeps them on the production-report service, discriminated by
 * [wire] inside the payload, so they persist, review and render like any
 * other report. Lists are filtered client-side by kind.
 */
enum class ReportKind(
    /** `shared.reportType`; empty for a production report (the field is absent on the wire). */
    val wire: String,
    val title: String,
    val path: String,
    val toolIdentifier: String,
    /** Wrap reports go straight from Drafts to Published on the web — no approvals tab. */
    val hasApprovals: Boolean,
    /** Only the production report regenerates crew-times sections from the crew list. */
    val generatesCrewSections: Boolean,
) {
    Production("", "Production Report", "/film-tools/production-report", "production_report_tool", true, true),
    Ad("ad", "AD Report", "/film-tools/ad-report", "ad_report_tool", true, false),
    Wrap("wrap", "Wrap Report", "/film-tools/wrap-report", "wrap_report_tool", false, false),
    ;

    /** Whether a stored report belongs to this tool. Legacy rows carry no type and are production reports. */
    fun owns(reportType: String): Boolean = reportType.trim().lowercase() == wire

    /** The eyebrow / default name stem: "AD report — day 5". */
    val nameStem: String get() = when (this) {
        Production -> "Production report"
        Ad -> "AD report"
        Wrap -> "Wrap report"
    }

    companion object {
        fun fromWire(value: String?): ReportKind =
            entries.firstOrNull { it.wire.isNotEmpty() && it.wire.equals(value?.trim(), ignoreCase = true) }
                ?: Production
    }
}

/**
 * The AD and Wrap templates, transcribed from the web's
 * `adreport/adReportTemplate.js` and `adreport/wrapReportTemplate.js`. The
 * production report's template comes from the server instead.
 */
object ReportTemplates {

    fun forKind(kind: ReportKind): SheetPayload? = when (kind) {
        ReportKind.Production -> null
        ReportKind.Ad -> ad()
        ReportKind.Wrap -> wrap()
    }

    fun ad(): SheetPayload = SheetPayload(
        shared = SharedHeader(reportType = ReportKind.Ad.wire),
        rows = rows(
            section("Report Info", "Date", "Location", "Crew Call", "1st Turnover", "Main Meal",
                "1st Turn After Lunch", "Unit Wrap"),
            section("Day Progress", "Call Time", "First Shot From Crew Call", "Was Meal Served At Crew Call",
                "First Meal Called At", "Back From First Meal", "Grace Called For First Meal", "Grace Called At",
                "Violated Grace", "Did Meal Penalty Occur", "Moving To Scene", "How Many Set Ups", "Scheduled Lunch",
                "First Shot After First Meal Break", "Moving To Scene No After First Meal Break",
                "How Many Set Ups (After Meal)", "Wrap Called For", "Wrap Time"),
            section("Scenes Summary", "Scenes Scheduled", "Completed", "Part Complete", "Carried", "Cut", "Sets",
                "Set Ups"),
            castTable("Cast", "Cast"),
            table("Cast Not Shooting", listOf("#", "Cast", "Character", "Status", "Notes"), CAST_ROWS),
            notes("Cast Notes"),
            castTable("Stunt Performers", "Stunt Performer"),
            notes("Stunt Performers Notes"),
            section("Requirements", "Cast Support", "Transport", "Crew Catering", "Crowd / Pic Doubles"),
            notes("Notes For Today"),
        ),
    )

    fun wrap(): SheetPayload = SheetPayload(
        shared = SharedHeader(reportType = ReportKind.Wrap.wire),
        rows = rows(
            section("Day Info", "Day Type", "Call Time", "1st Turn Over", "Lunch", "2nd Turn Over", "Est. Wrap",
                "Wrap"),
            section("Scenes", "Scheduled", "Completed", "Part Complete", "Carried", "Cut"),
            section("Locations", "Sets", "Set Ups"),
            notes("Notes"),
        ),
    )

    /** One page row per cell, in order. */
    private fun rows(vararg cells: PageCell): List<PageRow> =
        cells.mapIndexed { index, cell -> PageRow(order = index, cells = listOf(cell)) }

    /** A Field/Value section with one blank line per label. */
    private fun section(title: String, vararg fields: String) = PageCell(
        order = 0,
        systemDefault = true,
        kind = CellKind.Section,
        title = title,
        columns = listOf(ColumnSpec(type = "text", label = "Field"), ColumnSpec(type = "text", label = "Value")),
        rows = fields.mapIndexed { index, field -> CellRow(index, listOf(CellValue(field), CellValue())) },
    )

    private fun table(title: String, columns: List<String>, blankRows: Int) = PageCell(
        order = 0,
        kind = CellKind.Table,
        title = title,
        rawKind = CellKind.Table.wire,
        columns = columns.map { ColumnSpec(type = "text", label = it) },
        rows = List(blankRows) { index -> CellRow(index, List(columns.size) { CellValue() }) },
    )

    /** The 15-column cast table (info · work time · meals · travel time). */
    private fun castTable(title: String, subject: String) = table(
        title,
        listOf(
            "#", subject, "Character", "W", "H",
            "NDM", "Hair/Make up/Costume", "Report on set", "Wrap on set",
            "In", "Out",
            "Leave for Location", "Arrive on Location", "Leave Location", "Arrive at HQ",
        ),
        CAST_ROWS,
    )

    private fun notes(title: String) = PageCell(
        order = 0,
        kind = CellKind.Notes,
        title = title,
        rawKind = CellKind.Notes.wire,
        columns = listOf(ColumnSpec(type = "text", label = "Notes")),
        rows = listOf(CellRow(0, listOf(CellValue()))),
    )

    private const val CAST_ROWS = 2
}
