package com.zillit.desktop.feature.timecard.data

import com.zillit.desktop.feature.timecard.domain.Allowance
import com.zillit.desktop.feature.timecard.domain.DayType
import com.zillit.desktop.feature.timecard.domain.TimecardDay
import com.zillit.desktop.feature.timecard.domain.TimecardDraft
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.roundToInt

/**
 * The timecard save wire, in the shape the web's payload builders emit.
 *
 * The authority is `WeeklyTimecardModule.jsx`: `serializeDayToServer`
 * (lines 566-821) for a day, `buildSavePayload` (4670-4772) for the PATCH
 * body, and `ensureTimecardId` (4778-4816) for the CREATE body. The first
 * desktop wire invented its own field names (`worked_hours`, `break_minutes`,
 * string times, lowercase day types); the server dropped every one of them
 * silently, so a "saved" week stored nothing but its dates.
 *
 * ## What the desktop deliberately does not send
 *
 * The web computes pay client-side (the deal-memo OT engine) and persists it
 * in `rates_ots[]` plus the week totals `basic_pay` / `overtime_pay` /
 * `total_pay`. This port has no OT engine, so it emits `rates_ots: []` and
 * **omits** the money totals — the save is a PATCH, and an omitted key leaves
 * the stored value untouched (the web leans on exactly that for `company_id`,
 * `WeeklyTimecardModule.jsx:4762-4770`). The same omission covers
 * `weekly_allowances`, `weekly_additional_fees` and `holiday_pay_days`, where
 * sending `[]` would wipe buckets a web session had saved.
 */

private const val MINUTES_PER_HOUR = 60
private const val HOURS_PER_DAY = 24
private const val PERCENT = 100
private const val DAYS_PER_WEEK = 7
private const val ISO_MONDAY = 1
private const val ISO_SUNDAY = 7
private const val TWO_DIGITS = 2

/** `"UTC"` — every write stamps it, so times read back the same for every viewer. */
private const val UTC = "UTC"

private val EMPTY_ARRAY = JsonArray(emptyList())
private val EMPTY_OBJECT = JsonObject(emptyMap())

private fun Long?.toJson(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

/**
 * "HH:mm" + the day's epoch → the epoch that reads back as that same "HH:mm"
 * under UTC — `Date.UTC(<day's UTC calendar date>, h, m)`. An exact port of
 * `hhmmToUtcEpoch` (`tzDate.js:212-218`), which `serializeDayToServer` uses
 * for every worked time (`WeeklyTimecardModule.jsx:344-352`).
 */
internal fun hhmmToUtcEpoch(dateMs: Long?, hhmm: String?): Long? {
    if (dateMs == null || hhmm.isNullOrBlank()) return null
    val parts = hhmm.trim().split(':')
    val hour = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0 until HOURS_PER_DAY }
    val minute = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0 until MINUTES_PER_HOUR }
    if (hour == null || minute == null) return null
    val date = Instant.fromEpochMilliseconds(dateMs).toLocalDateTime(TimeZone.UTC).date
    return LocalDateTime(date, LocalTime(hour, minute)).toInstant(TimeZone.UTC).toEpochMilliseconds()
}

/**
 * A stored worked time back to "HH:mm", rendered in UTC — the web's
 * `epochToHHMM(ms, pickTz(day.timezone))` where every new day is stamped
 * `timezone: "UTC"` (`tzDate.js:139-193`). Legacy values that were saved as
 * "HH:mm" strings pass through unchanged.
 */
internal fun wireTimeOfDay(raw: String?): String? {
    val value = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    if (':' in value) return value
    val epoch = value.toDoubleOrNull()?.toLong()?.takeIf { it > 0 } ?: return null
    val time = Instant.fromEpochMilliseconds(epoch).toLocalDateTime(TimeZone.UTC).time
    val hh = time.hour.toString().padStart(TWO_DIGITS, '0')
    val mm = time.minute.toString().padStart(TWO_DIGITS, '0')
    return "$hh:$mm"
}

/**
 * Midnight of the most recent pay-period start day, in [zone] — the value the
 * payroll listings put in their URL path. A port of
 * `startOfPeriodTz(Date.now(), tz, startDayOfWeek)` (`tzDate.js:106-129`),
 * called with the viewer's own zone exactly as the accountant surfaces call
 * it (`AccountantPayrollModule.jsx:949-969`, `PayrollRunModule.jsx:4794`).
 * [startDayOfWeek] is ISO 1=Mon … 7=Sun; out-of-range values fall back to
 * Monday, the web's `payPeriodStartDay || 1`.
 */
internal fun payrollPeriodStart(
    nowMillis: Long,
    startDayOfWeek: Int,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Long {
    val start = if (startDayOfWeek in ISO_MONDAY..ISO_SUNDAY) startDayOfWeek else ISO_MONDAY
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
    val daysBack = (today.dayOfWeek.isoDayNumber - start + DAYS_PER_WEEK) % DAYS_PER_WEEK
    return today.minus(daysBack, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
}

/**
 * The `POST /weekly` body: identity plus a skeleton week, the deal of
 * `ensureTimecardId` (`WeeklyTimecardModule.jsx:4785-4800`) — `week_starting`,
 * the creator's IANA `timezone`, `department_id` (null is the web's own
 * missing-department case), `phase` (the web's untouched default), and one
 * identity row per day. `period` is left to the server's weekSeed, as the web
 * leaves it.
 */
internal fun TimecardDraft.createBody(timezone: String): JsonObject = buildJsonObject {
    put("week_starting", weekStarting.toJson())
    put("timezone", JsonPrimitive(timezone))
    put("department_id", departmentId?.let(::JsonPrimitive) ?: JsonNull)
    put("phase", JsonPrimitive("Production"))
    put("days", buildJsonArray { days.forEachIndexed { index, day -> add(day.skeletonRow(index)) } })
}

/** One create-skeleton day: `{date, day_number, day_type, timezone, call_time, wrap_time}`. */
private fun TimecardDay.skeletonRow(index: Int): JsonObject = buildJsonObject {
    putDayIdentity(this@skeletonRow, index)
    put("timezone", JsonPrimitive(UTC))
    put("call_time", hhmmToUtcEpoch(date, callTime).toJson())
    put("wrap_time", hhmmToUtcEpoch(date, wrapTime).toJson())
}

/**
 * The `PATCH /weekly/:id` body — `buildSavePayload`'s shape
 * (`WeeklyTimecardModule.jsx:4749-4771`) restricted to what this port can
 * honestly compute: the days, the day count, the hours and the allowance
 * total. See the file KDoc for the deliberately omitted keys.
 */
internal fun TimecardDraft.updateBody(): JsonObject = buildJsonObject {
    put("days", buildJsonArray { days.forEachIndexed { index, day -> add(day.dayBody(index)) } })
    put("total_days", JsonPrimitive(days.count { it.dayType.isPaidWork }))
    put("total_hours", JsonPrimitive(round2(workedHours)))
    put("total_allowances", JsonPrimitive(round2(allowanceTotal)))
}

/**
 * One saved day, branch by category exactly as `serializeDayToServer` does:
 * non-paid days carry identity only (`WeeklyTimecardModule.jsx:637-665`),
 * flat-pay days add allowances but no times (675-717), and everything else —
 * including a day with no type, the web's defensive default — takes the full
 * shoot shape (719-821).
 */
internal fun TimecardDay.dayBody(index: Int): JsonObject = when (dayType) {
    DayType.Rest, DayType.Holiday, DayType.Sick -> nonPaidBody(index)
    DayType.Travel -> flatPayBody(index)
    else -> shootBody(index)
}

/** REST / Holiday / Sick: the day exists for reporting, nothing is paid. */
private fun TimecardDay.nonPaidBody(index: Int): JsonObject = buildJsonObject {
    putDayIdentity(this@nonPaidBody, index)
    put("call_time", JsonNull)
    put("wrap_time", JsonNull)
    put("login_details", EMPTY_OBJECT)
    put("logout_details", EMPTY_OBJECT)
    putDayTail(basicHours = 0.0, minutesWorked = 0, allowances = EMPTY_ARRAY)
}

/** Travel and friends: a flat basic day, but per-diems and rentals are still owed. */
private fun TimecardDay.flatPayBody(index: Int): JsonObject = buildJsonObject {
    putDayIdentity(this@flatPayBody, index)
    put("call_time", JsonNull)
    put("wrap_time", JsonNull)
    put("login_details", EMPTY_OBJECT)
    put("logout_details", EMPTY_OBJECT)
    // The web sends the deal's contracted hours here and minutes_worked: 0
    // (WeeklyTimecardModule.jsx:705-706); the typed hours are this port's
    // nearest truth to "contracted hours".
    putDayTail(basicHours = workedHours, minutesWorked = 0, allowances = allowancesArray())
}

/** The full shoot shape: times as UTC-wall-clock epochs, worked minutes, claims. */
private fun TimecardDay.shootBody(index: Int): JsonObject = buildJsonObject {
    putDayIdentity(this@shootBody, index)
    val call = hhmmToUtcEpoch(date, callTime)
    val wrap = hhmmToUtcEpoch(date, wrapTime)
    put("call_time", call.toJson())
    put("wrap_time", wrap.toJson())
    // The desktop grid keeps one pair of times per day, so the personal
    // login/logout and the unit call/wrap coincide — the web keeps them
    // separate (:797-800) but reads hours off minutes_worked either way.
    put("login_details", timeDetails(call))
    put("logout_details", timeDetails(wrap))
    putDayTail(
        basicHours = workedHours,
        minutesWorked = (workedHours * MINUTES_PER_HOUR).roundToInt(),
        allowances = allowancesArray(),
    )
}

/** `{time, timezone: "UTC"}` — the login/logout envelope (`WeeklyTimecardModule.jsx:799-800`). */
private fun timeDetails(epoch: Long?): JsonObject = buildJsonObject {
    put("time", epoch.toJson())
    put("timezone", JsonPrimitive(UTC))
}

/** `{date, day_number, day_type}` — a NotWorked/Unknown day sends `day_type: null`. */
private fun JsonObjectBuilder.putDayIdentity(day: TimecardDay, index: Int) {
    put("date", day.date.toJson())
    put("day_number", JsonPrimitive(index + 1))
    put("day_type", day.dayType.wire?.let(::JsonPrimitive) ?: JsonNull)
}

/** The keys every branch ends with, in the serializer's order (`:653-663`). */
private fun JsonObjectBuilder.putDayTail(basicHours: Double, minutesWorked: Int, allowances: JsonArray) {
    put("timezone", JsonPrimitive(UTC))
    put("night_shoot", JsonPrimitive(false))
    put("ndm", JsonPrimitive(false))
    put("ndm_start_time", JsonNull)
    put("ndm_end_time", JsonNull)
    put("basic_hours", JsonPrimitive(basicHours))
    put("minutes_worked", JsonPrimitive(minutesWorked))
    put("rates_ots", EMPTY_ARRAY)
    put("allowances", allowances)
    put("additional_fees", EMPTY_ARRAY)
    put("meals", EMPTY_ARRAY)
    put("annotations", EMPTY_ARRAY)
}

private fun TimecardDay.allowancesArray(): JsonArray =
    buildJsonArray { allowances.forEach { add(it.entry()) } }

/**
 * One day-level allowance, per `buildAllowances`
 * (`WeeklyTimecardModule.jsx:586-619`): `rate_amount` carries the **resolved**
 * amount (a per-mile claim is multiplied out before the wire — the server has
 * no qty column and would drop it), `qty` is pinned to 1 so a future qty
 * column cannot double-charge, and `basis: "mile"` marks the per-unit rows.
 */
private fun Allowance.entry(): JsonObject = buildJsonObject {
    put("identifier", JsonPrimitive(code))
    put("label", JsonPrimitive(label))
    put("raw_label", JsonPrimitive(label))
    put("rate_type", JsonPrimitive("flat"))
    put("rate_amount", JsonPrimitive(round2(total)))
    put("qty", JsonPrimitive(1))
    put("basis", if (quantity != 1.0) JsonPrimitive("mile") else JsonNull)
    put("currency", JsonNull)
    put("work_duration", JsonPrimitive(0))
    put("is_rental", JsonPrimitive(false))
}

/** The web's `+x.toFixed(2)` on every total it sends. */
private fun round2(value: Double): Double = (value * PERCENT).roundToInt() / PERCENT.toDouble()
