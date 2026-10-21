package com.zillit.desktop.feature.addashboard.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The day's reports: the Wrap Report, the AD Report and the SAG-AFTRA
 * Exhibit G performer time sheet.
 *
 * Each one is built from a reusable **template** — blocks of sections, tables
 * and notes — which the AD fills from the day's live data, saves as a draft and
 * publishes. Publishing freezes it into a PDF the server e-mails to the
 * distribution list.
 *
 * ## Three kinds, three endpoints, two pages
 *
 * Wrap and AD reports share one list page; Exhibit G keeps its own. Never
 * fetch one kind's list and filter it: list rows carry no dependable
 * `report_type`, so the endpoint that returned a row *is* its kind, and the
 * kind decides which endpoint every later call for that row uses.
 */
enum class ReportKind(
    val wire: String,
    /** The path segment, which is not the wire value. */
    val path: String,
    private val labelKey: String,
) {
    WrapReport("wrap_report", "wrap-reports", S.desktop_wrap_report),
    AdReport("ad_report", "ad-reports", S.desktop_ad_report),
    ExhibitG("exhibit_g", "exhibit-g-reports", S.desktop_ad_exhibit_g),
    ;

    val label: String get() = str(labelKey)

    /**
     * Whether reads on this kind also claim rows with no `report_type`.
     *
     * Legacy reports predate the wrap/AD split and belong to the AD Report;
     * Wrap and Exhibit G never claim them.
     */
    val includeUntyped: Boolean get() = this == AdReport

    /** Exhibit G's layout is statutory, so it may not be restructured. */
    val layoutLocked: Boolean get() = this == ExhibitG

    companion object {
        /** The two kinds the merged list page holds, in display order. */
        val merged: List<ReportKind> = listOf(WrapReport, AdReport)

        /**
         * An untagged kind reads as the AD Report — the kind that claims
         * untyped rows, which is the same reason the web's `reportRouteSlug`
         * defaults there.
         */
        fun from(wire: String?): ReportKind {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value } ?: AdReport
        }
    }
}

/** A report's lifecycle. `posted` is the wire value for published. */
enum class ReportStatus(val wire: String, private val labelKey: String) {
    Draft("draft", S.draft),
    Posted("posted", S.cs_published),
    ;

    val label: String get() = str(labelKey)

    val published: Boolean get() = this == Posted

    companion object {
        fun from(wire: String?): ReportStatus =
            if (wire?.trim()?.lowercase() == Posted.wire) Posted else Draft
    }
}

/** What one column of a report block holds. */
enum class ReportValueType(val wire: String, private val labelKey: String) {
    Text("text", S.docusign_field_text),
    Email("email", S.email),
    Url("url", S.docusign_field_url),
    Phone("phone", S.phone),
    Time("time", S.time),
    Date("date", S.date),
    Number("number", S.docusign_number_value_hint),
    YesNo("yesno", S.desktop_ad_vtype_yes_no),
    Users("users", S.members_),

    /** Exhibit G's performer-signature column; not offered in the picker. */
    Sign("sign", S.signature_txt),
    ;

    val label: String get() = str(labelKey)

    companion object {
        /** What the column-type picker offers — [Sign] is authored, never chosen. */
        val choosable: List<ReportValueType> = entries.filterNot { it == Sign }

        fun from(wire: String?): ReportValueType {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value } ?: Text
        }
    }
}

/** One column of a section, table or notes block. */
data class ReportColumn(
    val id: String,
    val label: String = "",
    val type: ReportValueType = ReportValueType.Text,
    val width: Int? = null,
) {
    /**
     * Whether this is the performer's signature column.
     *
     * `type: sign` is the authored marker, but a report frozen before that
     * marker existed carries the column as plain text — so the label is the
     * fallback, exactly as a day-bound row falls back to its label.
     */
    val isSignature: Boolean
        get() = type == ReportValueType.Sign || label.contains("signature", ignoreCase = true)
}

/** One row of a block: its cells, in column order. */
data class ReportRow(
    val id: String,
    val cells: List<String> = emptyList(),
    val valueType: ReportValueType = ReportValueType.Text,
    val source: String = "",
    /**
     * A named report header field — Picture Title, Company, Production Number.
     *
     * Its value lives on `meta.header[hkey]`, **never** in the fills: these are
     * constant for the whole shoot, and as free rows they were retyped daily.
     */
    val headerKey: String? = null,
    /** The stable day-field bind, which survives renaming the row. */
    val field: String? = null,
) {
    val label: String get() = cells.firstOrNull().orEmpty()
}

/** What kind of block a cell is. */
enum class ReportBlockType(val wire: String, private val labelKey: String) {
    Section("section", S.desktop_section),
    Table("table", S.desktop_table),
    Notes("notes", S.notes),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun from(wire: String?): ReportBlockType {
            val value = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.wire == value } ?: Section
        }
    }
}

/** One block on the page — a section of field/value rows, a table, or notes. */
data class ReportCell(
    val id: String,
    val type: ReportBlockType = ReportBlockType.Section,
    val title: String = "",
    val hideTitle: Boolean = false,
    /** `liveday` marks the production meals table, which reads the day directly. */
    val source: String = "",
    val columns: List<ReportColumn> = emptyList(),
    val rows: List<ReportRow> = emptyList(),
    /** A frozen block keeps its rows rather than being rebuilt from the day. */
    val frozen: Boolean = false,
    val mealGrid: Boolean = false,
    val wrap: Boolean = false,
    /** A manual row order the drawer's drag saved, re-applied after a rebuild. */
    val rowOrder: List<String> = emptyList(),
) {
    /**
     * Whether this table's rows are rebuilt from the day's data on every open.
     *
     * The titles that look like extras but are not — "Stunt Performers",
     * "Cast Not Shooting" — stay authored, so the AD's own rows survive.
     */
    val isLiveTable: Boolean
        get() {
            if (type != ReportBlockType.Table || frozen) return false
            val lower = title.lowercase()
            if (isMealTable || mealGrid || NOT_SHOOTING.containsMatchIn(lower)) return true
            return EXTRAS.containsMatchIn(lower) && !EXTRAS_SKIP.containsMatchIn(lower)
        }

    /** The production meals table: a live view of the day's meals, edited inline. */
    val isMealTable: Boolean
        get() = type == ReportBlockType.Table && source == "liveday" && !isGrid

    val isGrid: Boolean get() = mealGrid || GRID.containsMatchIn(title.lowercase())

    /** The signature column's index, when this block has one. */
    val signatureColumn: Int? get() = columns.indexOfFirst { it.isSignature }.takeIf { it >= 0 }

    /** Rows in the order the drawer last saved, with anything new after them. */
    val orderedRows: List<ReportRow>
        get() {
            if (rowOrder.isEmpty()) return rows
            val byId = rows.associateBy { it.id }
            val named = rowOrder.mapNotNull(byId::get)
            val rest = rows.filterNot { it.id in rowOrder.toSet() }
            return named + rest
        }

    private companion object {
        val GRID = Regex("""\bgrid\b""")
        val EXTRAS = Regex("""extra|cast|artist|supporting|talent|crowd|background|performer|\bsa\b""")
        val EXTRAS_SKIP = Regex("""not\s*shoot|not\s*in|stand-?by|on\s*hold|stunt|release""")
        val NOT_SHOOTING = Regex("""not\s*shoot|not\s*in\b""")
    }
}

/** One row of the page layout: the blocks sitting side by side, or a page break. */
data class ReportPageRow(
    val id: String,
    val isBreak: Boolean = false,
    val cells: List<ReportCell> = emptyList(),
)

/** One performer's signature on an Exhibit G sheet. */
data class ReportSignature(
    val personId: String = "",
    val cellId: String = "",
    val rowId: String = "",
    val name: String = "",
    val signed: Boolean = false,
    val signedAt: Long? = null,
    val signatureFile: String = "",
    /** Witnessed on the AD's own device, rather than authenticated as that person. */
    val inPerson: Boolean = false,
)

/** One Cast row of a sheet and whether its person has signed. */
data class SigningRow(
    val cellId: String = "",
    val rowId: String = "",
    val rowIndex: Int = 0,
    val personId: String = "",
    /** `user`, `artiste` or `name` for a name typed straight into the cell. */
    val personType: String = "",
    val name: String = "",
    val signed: Boolean = false,
    val signedAt: Long? = null,
)

/** A report row as the list page shows it, without its layout. */
data class ReportSummary(
    val id: String,
    val kind: ReportKind,
    val name: String = "",
    val status: ReportStatus = ReportStatus.Draft,
    val shootDate: Long? = null,
    val dayNumber: Int? = null,
    val templateName: String = "",
    val postedAt: Long? = null,
    val updatedAt: Long? = null,
)

/** A whole report: its layout, its values and its signatures. */
data class AdReport(
    val id: String,
    val kind: ReportKind,
    val name: String = "",
    val status: ReportStatus = ReportStatus.Draft,
    val shootDate: Long? = null,
    val dayNumber: Int? = null,
    val pageRows: List<ReportPageRow> = emptyList(),
    /** Keyed `cellId.rowId.col` — see [ReportFills]. */
    val fills: Map<String, String> = emptyMap(),
    /** `meta.header` — the named header fields, kept out of the fills. */
    val header: Map<String, String> = emptyMap(),
    val templateName: String = "",
    val signatures: List<ReportSignature> = emptyList(),
    val signState: String = "none",
    val signRound: Int = 0,
    val reportAttachment: String = "",
    val postedAt: Long? = null,
    /**
     * The server returned the day's existing report instead of creating one.
     *
     * Treated as a **refusal**, not a result: the open draft is left untouched
     * so the AD can change the date rather than have their typing destroyed.
     */
    val existed: Boolean = false,
) {
    val published: Boolean get() = status.published

    /** Every block on the page, page breaks aside. */
    val cells: List<ReportCell> get() = pageRows.filterNot { it.isBreak }.flatMap { it.cells }

    /**
     * Which cast rows still owe a signature.
     *
     * Publish is refused while any of them does — the client checks, and the
     * server refuses with `ad_report_signatures_pending` regardless.
     */
    val unsignedCast: List<SigningRow>
        get() = cells
            .filter { it.signatureColumn != null }
            .flatMap { cell ->
                cell.rows.mapIndexedNotNull { index, row ->
                    val person = row.cells.firstOrNull()?.trim().orEmpty()
                    if (person.isEmpty()) return@mapIndexedNotNull null
                    val signed = signatures.any {
                        it.cellId == cell.id && it.rowId == row.id && it.signed
                    }
                    if (signed) {
                        null
                    } else {
                        SigningRow(
                            cellId = cell.id,
                            rowId = row.id,
                            rowIndex = index,
                            personId = person,
                            name = person,
                        )
                    }
                }
            }
}

/** A reusable report layout. */
data class ReportTemplate(
    val id: String,
    val name: String = "",
    val kind: ReportKind = ReportKind.AdReport,
    /** `zillit`, `sample`, `system` or `custom`; anything but custom is read-only. */
    val origin: String = "custom",
    val subtitle: String = "",
    val blockCount: Int = 0,
    val rowCount: Int = 0,
    /** The slim list's block summary: type and title only. */
    val blocks: List<Pair<ReportBlockType, String>> = emptyList(),
    val pageRows: List<ReportPageRow> = emptyList(),
    val header: Map<String, String> = emptyMap(),
) {
    val isBuiltIn: Boolean get() = origin != "custom"

    val originLabel: String
        get() = when (origin) {
            "zillit" -> str(S.desktop_ad_origin_zillit)
            "sample" -> str(S.desktop_ad_origin_sample)
            else -> str(S.custom)
        }

    /** Exhibit G templates are not offered when generating from the schedule. */
    val schedulePickable: Boolean get() = kind != ReportKind.ExhibitG
}

/**
 * The fill map's key, and the array the wire carries it as.
 *
 * `cellId.rowId.col`, with the column index last: ids never contain a dot, so
 * the key splits from the right.
 */
object ReportFills {

    fun key(cellId: String, rowId: String, column: Int): String = "$cellId.$rowId.$column"

    /** One parsed key, or null for anything that is not one. */
    fun parse(key: String): Triple<String, String, Int>? {
        val parts = key.split('.')
        if (parts.size < KEY_PARTS) return null
        val column = parts.last().toIntOrNull() ?: return null
        val rowId = parts[parts.size - 2]
        val cellId = parts.dropLast(2).joinToString(".")
        return Triple(cellId, rowId, column)
    }

    /** The one column a value lives in — the second of a section's two. */
    const val VALUE_COLUMN = 1

    /** `cellId.rowId.col`: the fewest dots a key can have. */
    private const val KEY_PARTS = 3
}

/**
 * Which `AdShootDay` field a report row writes back to.
 *
 * Prefers the row's stable `field` tag so renaming or moving the row keeps the
 * binding, and falls back to a label match for legacy rows that have none. A
 * user-added row with neither is free-form and never touches the day.
 *
 * The one label that outranks the tag is the set-call guard: a **Set**,
 * **Shooting**, **Camera** or **First-shot Call** is not the day's crew call,
 * and the label fallback below matches any label holding "call". Because the
 * patch is last-wins, a Set Call row under a Crew Call row used to overwrite
 * the day's `call_time` — the field timecards show as Unit Call, so the wrong
 * time reached payroll.
 */
object DayFieldBinding {

    val keys: Set<String> = setOf(
        "call_time",
        "wrap_time",
        "day_type",
        "scene_schedule",
        "scene_completed",
        "scene_uncompleted",
    )

    private val SET_CALL = Regex("""\b(set|shoot(ing)?|camera|first[\s_-]*shot)[\s_-]*call\b""")
    private val SCENES_UNCOMPLETED = Regex("""scenes?\b.*(part|carried|cut|uncomplet)""")
    private val SCENES_COMPLETED = Regex("""scenes?\b.*complet""")
    private val SCENES_SCHEDULED = Regex("""scenes?\b.*schedul""")

    fun of(row: ReportRow): String? {
        val label = row.label.lowercase()
        // Checked before the tag, so a mis-tagged template row cannot bypass it.
        if (SET_CALL.containsMatchIn(label)) return null
        row.field?.takeIf { it in keys }?.let { return it }
        return when {
            SCENES_UNCOMPLETED.containsMatchIn(label) -> "scene_uncompleted"
            SCENES_COMPLETED.containsMatchIn(label) -> "scene_completed"
            SCENES_SCHEDULED.containsMatchIn(label) -> "scene_schedule"
            "day type" in label -> "day_type"
            "wrap" in label -> "wrap_time"
            "call" in label -> "call_time"
            else -> null
        }
    }

    /** A scene value is a list on the day header; the report holds it as one line. */
    fun splitScenes(value: String?): List<String> =
        value.orEmpty().split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * What a report's own rows say about the day, ready to write back.
 *
 * Only section rows are read, and only those bound to a day field; a row with
 * no binding is free-form and never touches the day. Empty values are left
 * out, so a report can never wipe a day field it happens not to hold.
 *
 * [dayNumberOnly] is the date-move case: the number crosses to the new day,
 * and the times and scenes stay with the old one.
 */
@Suppress("NestedBlockDepth", "CyclomaticComplexMethod") // Page → cell → row, then one branch per bound field.
fun buildDayPatch(
    pageRows: List<ReportPageRow>,
    fills: Map<String, String>,
    shootDate: Long?,
    dayNumberOverride: Int? = null,
    dayNumberOnly: Boolean = false,
): DayPatch {
    if (dayNumberOnly) return DayPatch.dayNumberOnly(dayNumberOverride)
    var patch = DayPatch(dayNumber = dayNumberOverride)
    pageRows
        .filterNot { it.isBreak }
        .flatMap { it.cells }
        .filter { it.type == ReportBlockType.Section }
        .forEach { cell ->
            cell.rows.forEach { row ->
                val field = DayFieldBinding.of(row) ?: return@forEach
                val stored = fills[ReportFills.key(cell.id, row.id, ReportFills.VALUE_COLUMN)]
                val value = (stored ?: row.cells.getOrNull(ReportFills.VALUE_COLUMN)).orEmpty().trim()
                if (value.isEmpty()) return@forEach
                patch = when (field) {
                    "scene_schedule" -> patch.copy(sceneSchedule = DayFieldBinding.splitScenes(value))
                    "scene_completed" -> patch.copy(sceneCompleted = DayFieldBinding.splitScenes(value))
                    "scene_uncompleted" -> patch.copy(sceneUncompleted = DayFieldBinding.splitScenes(value))
                    "day_type" -> patch.copy(dayType = value)
                    "call_time" -> AdDates.hhmmToUtcEpoch(shootDate, value)
                        ?.let { patch.copy(callTime = it) } ?: patch

                    "wrap_time" -> AdDates.hhmmToUtcEpoch(shootDate, value)
                        ?.let { patch.copy(wrapTime = it) } ?: patch

                    else -> patch
                }
            }
        }
    return patch
}

/** What a report save writes onto the day. */
data class DayPatch(
    val dayNumber: Int? = null,
    val callTime: Long? = null,
    val wrapTime: Long? = null,
    val dayType: String? = null,
    val sceneSchedule: List<String>? = null,
    val sceneCompleted: List<String>? = null,
    val sceneUncompleted: List<String>? = null,
) {
    val isEmpty: Boolean
        get() = dayNumber == null && callTime == null && wrapTime == null &&
            dayType == null && sceneSchedule == null && sceneCompleted == null &&
            sceneUncompleted == null

    companion object {
        /**
         * A date move carries only the day number.
         *
         * The row-derived times and scenes belong to the old day and must not
         * bleed across to the new one.
         */
        fun dayNumberOnly(dayNumber: Int?): DayPatch = DayPatch(dayNumber = dayNumber)
    }
}
