package com.zillit.desktop.feature.payroll.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.currencyCode
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.feature.payroll.domain.ActiveDeal
import com.zillit.desktop.feature.payroll.domain.ActiveDealSource
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.ProductionReportSource
import com.zillit.desktop.feature.payroll.domain.ReportDay
import kotlinx.serialization.json.JsonObject

/**
 * The unit's day times — the web's `getMergedDayTimes` and its range twin.
 *
 * ## Two reports, and the wrap report wins
 *
 * The AD dashboard's wrap report is the primary source and the standalone
 * production-report service is the fallback, merged FIELD BY FIELD: crew call,
 * unit wrap and day type each take the wrap value when it has one. Time in and
 * time out are the production report's alone — the wrap report records no
 * actuals — and they are what tell a flat day (`O/C`, `Per HOD`) apart.
 *
 * ## Each side keeps its own zone
 *
 * The wrap report writes UTC wall clock, like every timecard-owned time; the
 * production report writes real instants to be read in the production's own
 * zone. Formatting each at its own source is what stops a 07:30 wrap call
 * rendering as 13:00 the moment a production report exists for the same day.
 *
 * ## `user_id` is required, not optional
 *
 * The request is authed as the payroll accountant, not the crew member, so
 * without it the service resolves the ACCOUNTANT's unit's report for everyone
 * — one unit's call times priced as another's pay.
 */
internal class ProductionReportSourceImpl(
    private val http: PayrollHttp,
    config: AppConfig,
) : ProductionReportSource {

    // `/api/v2`, NOT the `/v2` the web spells: the web's `/api` hides inside
    // its own base-URL env var. Bare `/v2` answers 404 on this service — a
    // fetch this source would have swallowed, leaving every fill saying "no
    // report data" with nothing to say why. Probed 2026-09-27.
    private val production = "${config.apiV2(ZillitService.ProductionReport)}production-reports/day-times"
    private val wrap = "${config.baseUrl(ZillitService.AdDashboard)}/api/v2/ad-shoot-days/day-details"

    override suspend fun day(dateIso: String, dayMillis: Long, userId: String): ZillitResult<ReportDay?> {
        val wrapRow = http.get(wrap, mapOf("day" to dayMillis, "user_id" to userId))
            .getOrNull().rows().firstOrNull()?.toWrapDay(dateIso)
        val productionRow = http.get(production, mapOf("date" to dateIso, "user_id" to userId))
            .getOrNull().obj()?.obj("data")?.obj("day_times")?.toProductionDay()
            ?.takeIf { it.published }
        val merged = merge(wrapRow, productionRow, dateIso)
        return ZillitResult.Success(merged?.takeIf { it.hasData })
    }

    override suspend fun week(weekStarting: Long, userId: String): ZillitResult<Map<String, ReportDay>> {
        val start = PayPeriod.isoDate(weekStarting)
        val end = PayPeriod.isoDate(weekStarting + (PayPeriod.DAYS_IN_WEEK - 1) * PayPeriod.DAY_MILLIS)
        val wrapRows = http.get(wrap, mapOf("week_start" to weekStarting, "user_id" to userId))
            .getOrNull().rows()
            .mapNotNull { row -> row.toWrapDay(row.millis("shoot_date")?.let(PayPeriod::isoDate)) }
            .associateBy { it.date }
        val productionRows = http
            .get(production + RANGE, mapOf("start" to start, "end" to end, "user_id" to userId))
            .getOrNull().rows()
            .mapNotNull { it.toProductionDay() }
            .filter { it.published }
            .associateBy { it.date }
        val dates = wrapRows.keys + productionRows.keys
        return ZillitResult.Success(
            dates.mapNotNull { date ->
                merge(wrapRows[date], productionRows[date], date)?.takeIf { it.hasData }?.let { date to it }
            }.toMap(),
        )
    }

    /**
     * One merged row. A wrap `day=` query always answers a stable row — every
     * field null for a day with no shoot day — so "a row came back" is not
     * "there is anything on it"; the caller drops an empty merge.
     */
    private fun merge(wrapRow: ReportDay?, productionRow: ReportDay?, date: String?): ReportDay? {
        if (wrapRow == null && productionRow == null) return null
        return ReportDay(
            date = wrapRow?.date ?: productionRow?.date ?: date.orEmpty(),
            published = true,
            dayType = wrapRow?.dayType ?: productionRow?.dayType,
            crewCall = wrapRow?.crewCall ?: productionRow?.crewCall,
            unitWrap = wrapRow?.unitWrap ?: productionRow?.unitWrap,
            timeIn = productionRow?.timeIn,
            timeOut = productionRow?.timeOut,
        )
    }

    private companion object {
        const val RANGE = "/range"
    }
}

/** Whether anything on the row is fillable — the web's `hasDayData`. */
private val ReportDay.hasData: Boolean
    get() = listOf(crewCall, unitWrap, dayType, timeIn, timeOut).any { !it.isNullOrBlank() }

/**
 * A wrap-report row in the shape the merge reads. Its times are UTC wall
 * clock, always: the row's own `timezone` is stamped from the REQUEST's zone
 * header, so reading it renders the AD's browser zone rather than the time
 * they typed.
 */
private fun JsonObject.toWrapDay(date: String?): ReportDay? {
    val key = date ?: millis("shoot_date")?.let(PayPeriod::isoDate) ?: return null
    return ReportDay(
        date = key,
        published = true,
        dayType = text("day_type"),
        crewCall = timeAt("crew_call", WRAP_ZONE),
        unitWrap = timeAt("unit_wrap", WRAP_ZONE),
        timeIn = null,
        timeOut = null,
    )
}

/** A production-report row, read in the report's own zone. */
private fun JsonObject.toProductionDay(): ReportDay? {
    val date = text("date") ?: return null
    val zone = text("timezone")
    return ReportDay(
        date = date,
        // The per-day endpoint leaves the gate to the caller and sends no
        // status; only an explicit non-PUBLISHED closes it.
        published = text("status")?.equals(PUBLISHED, ignoreCase = true) != false,
        dayType = text("day_type"),
        crewCall = timeAt("crew_call", zone),
        unitWrap = timeAt("unit_wrap", zone),
        // Passed through raw: these carry the flat-day sentinels, which are
        // words rather than times.
        timeIn = text("time_in"),
        timeOut = text("time_out"),
    )
}

/** A time field, whether it arrived as an epoch, an ISO stamp or a clock string. */
private fun JsonObject.timeAt(key: String, zone: String?): String? =
    millis(key)?.let { ReportTime.epoch(it, zone) } ?: ReportTime.hhmm(text(key), zone)

private const val WRAP_ZONE = "UTC"
private const val PUBLISHED = "PUBLISHED"

/**
 * A crew member's active deal, whole — `GET /deal-memo/deals/active/{userId}`.
 *
 * The document travels on verbatim, because the engine that prices it is the
 * payroll service's own bundle and reads fields this module has no model for.
 */
internal class ActiveDealSourceImpl(
    private val http: PayrollHttp,
    config: AppConfig,
) : ActiveDealSource {

    private val base = "${config.baseUrl(ZillitService.DealMemo)}/api/v2/deal-memo"

    override suspend fun activeDeal(userId: String): ZillitResult<ActiveDeal?> =
        http.get("$base/deals/active/$userId").map { data ->
            val document = data.obj()?.let { it.obj("data") ?: it } ?: return@map null
            ActiveDeal(
                userId = userId,
                agreementIdentifier = document.text("agreement_identifier"),
                currency = document.obj("rates")?.let { it["pay_currency"] }.currencyCode()
                    ?: document["currency"].currencyCode(),
                primaryDayType = document.primaryDayType(),
                document = document.toString(),
            )
        }
}

/**
 * The deal's first day type — what a report day with no type of its own is
 * priced as. `SWD` when the deal names none, as the web falls back.
 */
private fun JsonObject.primaryDayType(): String {
    val types = obj("rates")?.objects("day_types").orEmpty().ifEmpty { objects("day_types") }
    return types.firstOrNull()?.text("day_type", "type") ?: ActiveDeal.DEFAULT_DAY_TYPE
}
