package com.zillit.desktop.feature.costreport.domain

import com.zillit.desktop.core.common.ZillitResult

/** One week of the report, as a pane shows it. */
data class CrWeekReport(
    val sections: List<CrSection>,
    /** The currency the figures are denominated in, as the server says; null when it did not say. */
    val serverCurrency: String?,
    /** Set when the week was read from a posted snapshot rather than computed live. */
    val fromSnapshot: SnapshotHeader? = null,
)

/**
 * How a week of the report is fetched — the web's `useCostReportLive`.
 *
 * A week that already has a posted snapshot starting on its Monday, in the
 * currency being asked for, is read from that snapshot: the figures were
 * frozen when it was posted, and recomputing them live would disagree with
 * what was sent out. Any other week is computed live for the filters.
 */
class CrReportLoader(private val repository: CostReportRepository) {

    @Suppress("LongParameterList") // The week and every filter the server takes.
    suspend fun load(
        coa: List<CoaRow>,
        week: WeekWindow,
        budget: BudgetVersion?,
        companyId: String?,
        currency: String?,
        defaultCurrency: String?,
        snapshots: List<SnapshotHeader>,
    ): ZillitResult<CrWeekReport> {
        val posted = snapshots.filter { it.periodStartMs == week.startMs }.maxByOrNull { it.generatedAtMs ?: 0L }
        val postedCurrency = posted?.currency ?: defaultCurrency
        val wanted = currency ?: defaultCurrency
        if (posted != null && postedCurrency == wanted) {
            return when (val detail = repository.snapshot(posted.id)) {
                is ZillitResult.Failure -> detail
                is ZillitResult.Success -> ZillitResult.Success(
                    CrWeekReport(
                        sections = buildSections(coa, detail.data.lines),
                        serverCurrency = detail.data.header.currency,
                        fromSnapshot = detail.data.header,
                    ),
                )
            }
        }
        return when (
            val live = repository.live(
                periodStartMs = week.startMs,
                periodEndMs = week.endMs,
                budgetVersionId = budget?.id,
                companyId = companyId,
                currency = currency,
            )
        ) {
            is ZillitResult.Failure -> live
            is ZillitResult.Success -> ZillitResult.Success(
                CrWeekReport(buildSections(coa, live.data.lines), live.data.displayCurrency),
            )
        }
    }

    /**
     * The VTP baseline for [week]: the latest *weekly* post whose period ended
     * before the week began, read in full. Best effort — no prior post, or one
     * that will not load, means no movement to report.
     */
    suspend fun baseline(snapshots: List<SnapshotHeader>, week: WeekWindow): VtpBaseline {
        val prior = snapshots
            .filter { it.cadence == SnapshotCadence.Weekly && (it.periodEndMs ?: Long.MAX_VALUE) < week.startMs }
            .maxByOrNull { it.periodEndMs ?: 0L }
            ?: return VtpBaseline.NONE
        val detail = repository.snapshot(prior.id).getOrNull() ?: return VtpBaseline.NONE
        return VtpBaseline.from(detail.lines)
    }
}
