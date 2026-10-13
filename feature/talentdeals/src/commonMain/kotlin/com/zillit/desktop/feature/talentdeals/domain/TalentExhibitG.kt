package com.zillit.desktop.feature.talentdeals.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A row's SAG-AFTRA day facts (the API's `sag`) — minutes, flags and
 * half-hours late, never money: the panel prices them with the contract's day
 * rate (see [TalentExhibitG.dayLines]).
 */
data class SagFacts(
    val workedMinutes: Int? = null,
    val mealLateness: List<Int> = emptyList(),
    val mealPenalties: Int = 0,
    val shortMealBreak: Boolean = false,
    val overtime15: Int = 0,
    val overtime2: Int = 0,
    val goldenHours: Int = 0,
    val night10: Int = 0,
    val night20: Int = 0,
    val turnaroundMinutes: Int? = null,
    val turnaroundShort: Boolean = false,
    val forcedCall: Boolean = false,
    val holiday: Boolean = false,
    val consecutiveDay: Int = 0,
)

/** One person's row on one day's Exhibit G sheet. */
data class SigningRow(
    val cellId: String? = null,
    val rowId: String? = null,
    val rowIndex: Int = 0,
    /** Who this row is: the Cast cell's value (member / artiste id, or typed text). */
    val personId: String? = null,
    /** What that value is: `user`, `artiste` or `name`. */
    val personType: String? = null,
    val name: String = "",
    /** The row as the sheet shows it, one string per column of the day's `columns`. */
    val values: List<String> = emptyList(),
    val sag: SagFacts? = null,
    val signed: Boolean = false,
    val signedAt: Long? = null,
    val signatureFile: TalentAttachment? = null,
) {
    /** A row's identity within a day — two people in one Cast cell share its cell and row. */
    val key: String get() = "$cellId:$rowId:$personId"
}

data class SigningColumn(val label: String, val type: String)

/** One shoot day's report, narrowed to the rows that name this talent. */
data class SigningDay(
    val reportId: String?,
    val shootDate: Long?,
    val columns: List<SigningColumn>,
    val rows: List<SigningRow>,
) {
    val key: String get() = reportId ?: shootDate.toString()
}

/** One line of a priced day. */
data class DayLine(val key: String, val label: String, val detail: String, val amount: Double)

/**
 * Exhibit G signing state for one talent, across every shoot day — what the
 * contract's "View Exhibit G" panel shows.
 *
 * Source: the AD dashboard's Exhibit G reports (one per shoot day), asked for
 * the rows whose Cast cell names this person. The person is sent as the
 * contract's TYPED name — a talent contract carries no project-member link —
 * and the server matches it, case-insensitively, against project members'
 * full name, artistes' first + last name and names typed straight into a
 * report's Cast cell. A name found nowhere is a 404 whose message the panel
 * shows.
 */
object TalentExhibitG {

    // SAG-AFTRA 2026–2030 terms the panel quotes. Not settings: they are the
    // agreement's own figures.
    const val WORK_DAY_HOURS = 8
    const val TURNAROUND_HOURS = 12
    const val MEAL_INTERVAL_HOURS = 6

    /** SAG day-performer scale, the fallback when the fee cannot be worked back. */
    const val DAY_PERFORMER_SCALE = 1283.0

    /** A studio week the fee is divided over. */
    const val DAYS_PER_WEEK = 5

    /** The grid's leaf columns between Character and the signature — Code through MPVs. */
    const val TIME_COLUMNS = 20

    /**
     * The statutory sheet's leaf columns in the order the grid draws them —
     * Cast, Minor?, Character, the twenty above, the signature, Objection. A
     * report's `values` map onto the grid BY POSITION (the leaf labels repeat),
     * so they are used only when a row has exactly this many; a sheet laid out
     * any other way shows dashes rather than values under the wrong heading.
     */
    const val GRID_COLUMNS = 3 + TIME_COLUMNS + 2

    /** The grid position of the sheet's Forced Call column. */
    const val FORCED_CALL_COL = 21

    private val MEAL_PRINCIPAL = listOf(25.0, 35.0, 50.0, 50.0, 75.0)
    private val MEAL_BACKGROUND = listOf(7.5, 10.0, 12.5, 15.0)
    private const val FORCED_CALL_CAP = 900.0
    private const val MINUTES_PER_HOUR = 60
    private const val TENTH_OF_AN_HOUR = 6
    private const val CATEGORY_DAY = "day"
    private const val CATEGORY_BACKGROUND = "background"
    private const val BACKGROUND_DOUBLE_TIME_CAP = 960
    private const val DOUBLE_TIME_AFTER = 600
    private const val SIXTH_DAY = 6
    private const val NIGHT_10 = 0.1
    private const val TIME_AND_A_HALF = 1.5
    private const val HALF_RATE = 0.5
    private const val THOUSAND = 1000.0
    private const val NIGHT_20 = 0.2
    private const val SEVENTH_DAY = 7

    // -- reading the answer --------------------------------------------------

    private fun dateOf(obj: JsonObject?): Long? {
        for (key in listOf("shoot_date", "date", "report_date")) {
            val primitive = JsonRead.primitive(obj, key) ?: continue
            JsonRead.number(primitive)?.let { return it.toLong() }
            val text = primitive.content.take(ISO_LENGTH)
            TalentDates.toEpoch(text)?.let { return it }
        }
        return null
    }

    private const val ISO_LENGTH = 10

    private fun sagOf(sag: JsonObject?): SagFacts? = sag?.let {
        fun int(key: String) = JsonRead.number(it, key)?.toInt() ?: 0
        SagFacts(
            workedMinutes = JsonRead.number(it, "worked_minutes")?.toInt(),
            mealLateness = JsonRead.array(it["meal_lateness"]).mapNotNull {
                (it as? JsonPrimitive)?.let(JsonRead::number)?.toInt()
            },
            mealPenalties = int("meal_penalties"),
            shortMealBreak = JsonRead.bool(it, "short_meal_break"),
            overtime15 = int("overtime_15_minutes"),
            overtime2 = int("overtime_2_minutes"),
            goldenHours = int("golden_hours"),
            night10 = int("night_10_minutes"),
            night20 = int("night_20_minutes"),
            turnaroundMinutes = JsonRead.number(it, "turnaround_minutes")?.toInt(),
            turnaroundShort = JsonRead.bool(it, "turnaround_short"),
            forcedCall = JsonRead.bool(it, "forced_call"),
            holiday = JsonRead.bool(it, "holiday"),
            consecutiveDay = int("consecutive_day"),
        )
    }

    private fun rowOf(r: JsonObject): SigningRow = SigningRow(
        cellId = JsonRead.text(r, "cell_id"),
        rowId = JsonRead.text(r, "row_id"),
        rowIndex = JsonRead.number(r, "row_index")?.toInt() ?: 0,
        personId = JsonRead.text(r, "person_id"),
        personType = JsonRead.text(r, "person_type"),
        name = JsonRead.text(r, "name").orEmpty(),
        values = JsonRead.array(r["values"]).map { v ->
            (v as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content.orEmpty()
        },
        sag = sagOf(JsonRead.obj(r, "sag")),
        signed = JsonRead.bool(r, "signed"),
        signedAt = JsonRead.long(r, "signed_at"),
        signatureFile = attachmentOf(JsonRead.obj(r, "signature_file")),
    )

    private fun columnsOf(element: JsonElement?): List<SigningColumn> = JsonRead.array(element).map {
        val col = it as? JsonObject
        SigningColumn(label = JsonRead.text(col, "label").orEmpty(), type = JsonRead.text(col, "type") ?: "text")
    }

    private class Group(var reportId: String?, var shootDate: Long?) {
        var columns: List<SigningColumn> = emptyList()
        val rows = mutableListOf<SigningRow>()
    }

    /**
     * The API's data → days in date order, rows in sheet order. Reads the
     * all-days route (`{ reports: [...] }`), the per-report route
     * (`{ report_id, columns, rows }`) and flat rows tagged with their own
     * `report_id`. A day with no rows is dropped; an undated day sorts last.
     */
    @Suppress("CyclomaticComplexMethod") // Three answer shapes, one reader.
    fun normalizeSigningDays(data: JsonElement?): List<SigningDay> {
        val d = data as? JsonObject ?: return emptyList()
        val groups = LinkedHashMap<String, Group>()

        fun push(reportId: String?, shootDate: Long?, rows: List<JsonObject>, columns: JsonElement?) {
            val key = reportId ?: "__${shootDate ?: "undated"}"
            val group = groups.getOrPut(key) { Group(reportId, shootDate) }
            if (group.shootDate == null && shootDate != null) group.shootDate = shootDate
            if (group.columns.isEmpty()) group.columns = columnsOf(columns)
            group.rows += rows.map(::rowOf)
        }

        val reports = d["reports"]
        if (reports is JsonArray) {
            JsonRead.objects(reports).forEach { rep ->
                push(JsonRead.text(rep, "report_id"), dateOf(rep), JsonRead.objects(rep["rows"]), rep["columns"])
            }
        } else if (d["rows"] is JsonArray) {
            JsonRead.objects(d["rows"]).forEach { r ->
                push(
                    JsonRead.text(r, "report_id") ?: JsonRead.text(d, "report_id"),
                    dateOf(r) ?: dateOf(d),
                    listOf(r),
                    d["columns"],
                )
            }
        }

        return groups.values
            .filter { it.rows.isNotEmpty() }
            .map { SigningDay(it.reportId, it.shootDate, it.columns, it.rows.sortedBy(SigningRow::rowIndex)) }
            .sortedBy { it.shootDate ?: Long.MAX_VALUE }
    }

    internal fun attachmentOf(obj: JsonObject?): TalentAttachment? {
        if (obj == null) return null
        val media = JsonRead.text(obj, "media") ?: return null
        return TalentAttachment(
            media = media,
            bucket = JsonRead.text(obj, "bucket").orEmpty(),
            region = JsonRead.text(obj, "region").orEmpty(),
            name = JsonRead.text(obj, "name").orEmpty(),
            contentType = JsonRead.text(obj, "content_type").orEmpty(),
            contentSubtype = JsonRead.text(obj, "content_subtype").orEmpty(),
            caption = JsonRead.text(obj, "caption").orEmpty(),
            raw = obj,
        )
    }

    // -- pricing a day -------------------------------------------------------

    /** Minutes as the design writes them: "9h", "9h 30m". */
    fun fmtDuration(minutes: Int): String {
        val h = minutes / MINUTES_PER_HOUR
        val m = (minutes % MINUTES_PER_HOUR)
        return if (m != 0) "${h}h ${m.toString().padStart(2, '0')}m" else "${h}h"
    }

    private fun tenths(minutes: Int): Int = ceil(minutes / TENTH_OF_AN_HOUR.toDouble()).toInt() * TENTH_OF_AN_HOUR

    /**
     * The day rate the contract's fee works back to: the fee over the weeks the
     * schedule covers over a five-day week, else scale — rather than guessing
     * a split across days that have not happened. Second: how it was reached.
     */
    fun dayRate(fee: Double, weeks: Double): Pair<Double, String> {
        val derived = if (fee > 0 && weeks > 0) fee / weeks / DAYS_PER_WEEK else 0.0
        if (derived <= 0) return DAY_PERFORMER_SCALE to str(S.desktop_td_eg_basis_scale)
        val shown = TalentNumbers.plain(weeks).ifEmpty { "0" }
        val basis = if (weeks == 1.0) {
            str(S.desktop_td_eg_basis_week, shown, DAYS_PER_WEEK)
        } else {
            str(S.desktop_td_eg_basis_weeks, shown, DAYS_PER_WEEK)
        }
        return derived to basis
    }

    /**
     * One day's facts priced into lines, in the design's order. Nothing when
     * the day has no report / dismiss times. The flat penalties are US dollar
     * figures, shown unconverted; each late meal climbs its own ladder.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // One line per penalty the agreement names.
    fun dayLines(sag: SagFacts?, dayRate: Double, category: String = CATEGORY_DAY): List<DayLine> {
        val worked = sag?.workedMinutes ?: return emptyList()
        val hourly = dayRate / WORK_DAY_HOURS
        val background = category == CATEGORY_BACKGROUND
        val lines = mutableListOf(
            DayLine(
                "basic",
                str(S.desktop_td_eg_line_basic),
                fmtDuration(min(worked, WORK_DAY_HOURS * MINUTES_PER_HOUR)),
                dayRate,
            ),
        )
        if (sag.overtime15 > 0) {
            lines += DayLine(
                "ot15",
                str(S.desktop_td_eg_line_ot15),
                fmtDuration(sag.overtime15),
                sag.overtime15 / MINUTES_PER_HOUR.toDouble() * hourly * TIME_AND_A_HALF,
            )
        }
        // Background's double time stops at 16 hours, where golden time takes over.
        val ot2 = if (background) tenths(maxOf(min(worked, BACKGROUND_DOUBLE_TIME_CAP) - DOUBLE_TIME_AFTER,
            0)) else sag.overtime2
        if (ot2 > 0) {
            lines += DayLine("ot2", str(S.desktop_td_eg_line_ot2), fmtDuration(ot2),
                ot2 / MINUTES_PER_HOUR.toDouble() * hourly * 2)
        }
        if (background && sag.goldenHours > 0) {
            lines += DayLine(
                "golden",
                str(S.desktop_td_eg_line_golden),
                str(S.desktop_td_eg_detail_golden, sag.goldenHours),
                sag.goldenHours * dayRate,
            )
        }
        if (sag.mealLateness.isNotEmpty()) {
            val ladder = if (background) MEAL_BACKGROUND else MEAL_PRINCIPAL
            val amount = sag.mealLateness.sumOf { halfHours ->
                (0 until halfHours).sumOf { k -> ladder[min(k, ladder.size - 1)] }
            }
            lines += DayLine("meal", str(S.desktop_dm_tpl_meal_penalty), str(S.desktop_td_eg_detail_meal,
                sag.mealLateness.sum()), amount)
        }
        if (sag.forcedCall && sag.turnaroundMinutes != null) {
            lines += DayLine(
                "forced",
                str(S.desktop_td_eg_line_forced),
                str(S.desktop_td_eg_detail_forced, fmtDuration(sag.turnaroundMinutes)),
                min(dayRate, FORCED_CALL_CAP),
            )
        }
        if (sag.holiday) {
            lines += DayLine("holiday", str(S.desktop_td_eg_line_holiday), str(S.desktop_td_eg_detail_holiday), dayRate)
        }
        if (sag.consecutiveDay == SIXTH_DAY) {
            lines += DayLine(
                "day6",
                str(S.desktop_td_eg_line_day6),
                str(S.desktop_td_eg_detail_day6),
                dayRate * HALF_RATE,
            )
        }
        if (sag.consecutiveDay >= SEVENTH_DAY) {
            lines += DayLine("day7", str(S.desktop_td_eg_line_day7), str(S.desktop_td_eg_detail_day7), dayRate)
        }
        if (background && (sag.night10 > 0 || sag.night20 > 0)) {
            lines += DayLine(
                "night",
                str(S.desktop_td_eg_line_night),
                fmtDuration(sag.night10 + sag.night20),
                (sag.night10 * NIGHT_10 + sag.night20 * NIGHT_20) / MINUTES_PER_HOUR.toDouble() * hourly,
            )
        }
        return lines
    }

    /** The day-cost total of a priced day. */
    fun total(lines: List<DayLine>): Double = lines.sumOf { it.amount }

    /**
     * Which row stands for a day when several match the name: the picked one,
     * else the row whose Cast name is EXACTLY the contract's (case kept,
     * spacing tidied) — the server matched them all ignoring case — else the
     * first.
     */
    fun chooseRow(day: SigningDay, picked: String?, performer: String): SigningRow {
        val multi = day.rows.size > 1
        if (multi) {
            day.rows.firstOrNull { it.key == picked }?.let { return it }
            day.rows.firstOrNull { asWritten(it.name) == asWritten(performer) }?.let { return it }
        }
        return day.rows.first()
    }

    /** A name compared as written: case kept, spacing tidied. */
    fun asWritten(name: String?): String = name.orEmpty().trim().replace(Regex("\\s+"), " ")

    /** A name as the lookup compares it: no case, one space per gap. */
    fun nameKey(name: String?): String = asWritten(name).lowercase()

    /** A row's Character and Code as the sheet has them ("" when it can't be read). */
    fun sheetCell(row: SigningRow?, index: Int): String =
        row?.values?.takeIf { it.size == GRID_COLUMNS }?.getOrNull(index).orEmpty()

    /** Rounded for display tests: `Math.round(x * 1000) / 1000`. */
    fun round3(value: Double): Double = (value * THOUSAND).roundToInt() / THOUSAND
}
