package com.zillit.desktop.feature.payroll.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * One row of the week's slim crew list — `/payroll/weekly/{ws}/crew`.
 *
 * The producer surfaces list a whole unit's week, so the service answers a
 * projection rather than the documents: an id, whose week it is, what state it
 * is in and two figures. The full timecard is fetched only for the row the
 * producer opens (`api/payroll/payroll-weekly.js`).
 */
data class PayrollCrewRow(
    val id: String,
    val userId: String,
    val status: TimecardStatus,
    val totalPay: Double = 0.0,
    val totalDays: Int = 0,
)

/**
 * A crew member's active deal, as the production's pay engine reads it.
 *
 * The engine is a JavaScript bundle the payroll service publishes per
 * agreement (`lib/otEngine.js`), so the deal travels to it as the document the
 * service sent — [document] — rather than as a model this module has parsed.
 * Only the few fields the screen itself needs are lifted out.
 */
data class ActiveDeal(
    val userId: String,
    /** Which engine bundle prices this deal; null loads the default. */
    val agreementIdentifier: String?,
    /** The deal's pay currency, which the estimate's money is in. */
    val currency: String?,
    /** The deal's first day type — the fallback when the report names none. */
    val primaryDayType: String,
    /** The deal document, verbatim. */
    val document: String,
) {
    companion object {
        /** What the web falls back to when the deal names no day types. */
        const val DEFAULT_DAY_TYPE = "SWD"
    }
}

/**
 * A deal priced by the engine: `deriveRatesFromDeal`'s bundle, kept as the
 * engine returned it, plus the one figure this module reads out of it.
 */
data class DealRates(
    val deal: ActiveDeal,
    /** The derived rates, verbatim, handed back to the engine for each day. */
    val rates: String,
    /**
     * Which loaded bundle priced them.
     *
     * Carried on the rates rather than held as "the active engine", because a
     * fill is seven calls and anything that loads another agreement's bundle
     * in between — a holiday-pay lookup for the crew member the producer just
     * clicked — would finish those calls on the wrong engine. Naming the
     * engine on the rates makes that impossible rather than merely unlikely.
     */
    val engineKey: String = "",
    /**
     * The deal's holiday-pay rate. Holiday pay is never stored on a timecard —
     * the only read endpoint is scoped to the requesting user — so every
     * surface that shows another crew member's accrual derives it as
     * `basic × hp_rate` (the web's `useCrewHolidayPay`).
     */
    val holidayPayRate: Double = 0.0,
)

/** One day as the estimate states it, before the engine prices it. */
data class EstimateInput(
    val dateMillis: Long,
    val dayType: String,
    /** `HH:mm`, or null on a flat day, which has no times. */
    val call: String?,
    val timeOut: String?,
    val unitCall: String?,
    val unitWrap: String?,
) {
    /** A flat day pays the daily rate and has no worked window. */
    val isFlat: Boolean get() = dayType == FLAT

    companion object {
        const val FLAT = "Flat"
    }
}

/** What the engine makes of one day. */
data class DayCalc(
    val basicPay: Double,
    val otPay: Double,
    val dayGross: Double,
    val workedMinutes: Int,
    /** The day's pay lines, in the shape a timecard stores them. */
    val lines: List<PayLine> = emptyList(),
)

/** A day filled from the production report and priced locally. Never saved. */
data class EstimatedDay(val input: EstimateInput, val calc: DayCalc)

/**
 * One published day of the unit's production report, merged with the AD's
 * wrap report — the web's `getMergedDayTimes`, where the wrap report wins
 * field by field and the production side must be published.
 *
 * Times are already `HH:mm` in whichever zone their own source demands: the
 * wrap report's are UTC wall-clock, the production report's are read in the
 * report's own zone. Doing that at the seam is what keeps a 07:30 wrap call
 * from rendering as 13:00 once a production report exists for the same day.
 */
data class ReportDay(
    /** `YYYY-MM-DD`, the key the week is joined on. */
    val date: String,
    val published: Boolean,
    val dayType: String?,
    val crewCall: String?,
    val unitWrap: String?,
    /** `O/C` or `Per HOD` on a flat day; the estimate reads them as sentinels. */
    val timeIn: String?,
    val timeOut: String?,
) {
    /**
     * A flat daily-rate day: the AD recorded the crew as on call or working to
     * their HOD, so there are no fixed times to price an OT band against.
     */
    val isFlatDay: Boolean get() = timeIn.isFlatSentinel() || timeOut.isFlatSentinel()

    private companion object {
        val FLAT_SENTINELS = setOf("o/c", "per hod")

        fun String?.isFlatSentinel(): Boolean =
            this?.trim()?.lowercase()?.replace(WHITESPACE, " ") in FLAT_SENTINELS

        val WHITESPACE = Regex("\\s+")
    }
}

/**
 * The production's pay engine.
 *
 * The web never computes pay itself: `calcDay` and `deriveRatesFromDeal` live
 * in a JavaScript bundle the payroll service publishes per agreement, fetched
 * and executed at runtime (`lib/otEngine.js`). The estimate on Production
 * Report Payroll — and the holiday-pay accrual every read-only week shows —
 * are that bundle's arithmetic, so this port runs the same bundle rather than
 * reimplementing it, which is the only way the money agrees.
 *
 * A host with no engine passes null. Every caller then behaves as the web does
 * when its bundle fails to load: `isEngineLoaded()` is false, `calcDay`
 * answers nothing, and the surfaces that price locally say they cannot.
 */
interface PayrollEstimator {

    /**
     * Loads the bundle for the deal's agreement — the default engine when the
     * agreement has none of its own — and prices the deal with it
     * (`deriveRatesFromDeal`).
     *
     * One call rather than two, so that loading and pricing cannot be
     * interleaved by another crew member's lookup: the rates come back naming
     * the engine that made them, and every later day is priced by that one.
     */
    suspend fun load(deal: ActiveDeal): ZillitResult<DealRates>

    /**
     * `calcDay` — one day's pay, on the engine [rates] names. [previous] is
     * the day before it, already filled, which the turnaround rules read; null
     * breaks the chain, exactly as an unfilled previous day does on the web.
     */
    suspend fun calcDay(
        input: EstimateInput,
        index: Int,
        rates: DealRates,
        previous: EstimateInput?,
    ): ZillitResult<DayCalc?>
}

/**
 * Runs the payroll service's JavaScript pay engine.
 *
 * The engine ships as a browser bundle — a classic script that binds
 * `OTEngine` on the global object — so executing it needs a JavaScript
 * runtime, which is a host concern rather than a payroll one. Implemented
 * where the platform's runtime is; absent, nothing is priced locally.
 */
interface PayrollScriptHost {

    /** Evaluates a bundle under [key]. False when it would not run. */
    suspend fun load(key: String, source: String): Boolean

    /**
     * Calls `OTEngine.<[function]>` on the bundle loaded under [key], each
     * argument a JSON document, and answers the result as JSON — or null when
     * the engine answered nothing, which is what it answers for a day it
     * cannot price.
     */
    suspend fun call(key: String, function: String, arguments: List<String>): String?
}

/** The unit's production report, as the producer surfaces read it. */
interface ProductionReportSource {

    /** One day, merged from the wrap and production reports. */
    suspend fun day(dateIso: String, dayMillis: Long, userId: String): ZillitResult<ReportDay?>

    /** A whole week in one round trip, keyed by date. */
    suspend fun week(weekStarting: Long, userId: String): ZillitResult<Map<String, ReportDay>>
}

/**
 * The three seams the producer boards need beyond the payroll service.
 *
 * Held together because they are only ever useful together: a report day with
 * no deal cannot be priced, and a deal with no engine cannot be read. A host
 * that has none of them leaves the boards reading rather than estimating.
 */
data class PayrollProducerSeams(
    val reports: ProductionReportSource? = null,
    val deals: ActiveDealSource? = null,
    val estimator: PayrollEstimator? = null,
)

/** A crew member's active deal — `GET /deal-memo/deals/active/{userId}`. */
interface ActiveDealSource {
    suspend fun activeDeal(userId: String): ZillitResult<ActiveDeal?>
}
