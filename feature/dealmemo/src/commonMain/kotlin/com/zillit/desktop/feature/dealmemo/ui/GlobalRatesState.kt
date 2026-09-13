package com.zillit.desktop.feature.dealmemo.ui

import com.zillit.desktop.feature.dealmemo.domain.rates.AgreementDocument
import com.zillit.desktop.feature.dealmemo.domain.rates.AgreementSummary
import com.zillit.desktop.feature.dealmemo.domain.rates.Branch
import com.zillit.desktop.feature.dealmemo.domain.rates.DesignationRate
import com.zillit.desktop.feature.dealmemo.domain.rates.EmpStatus
import com.zillit.desktop.feature.dealmemo.domain.rates.GlobalRatesRules
import com.zillit.desktop.feature.dealmemo.domain.rates.UnionSection
import com.zillit.desktop.feature.dealmemo.domain.rates.UnionSummary

/** The drill-down of Global Production Rates (`DMConfigPage.jsx` nav views). */
enum class RatesView { Welcome, Territory, AgreementsList, Branch, Agreement }

/** Where an agreement was opened from — what its back link returns to. */
enum class AgreementOrigin { Branch, AgreementsList }

/**
 * Global Production Rates: a read-only browser of the union catalogue —
 * territory → branch or agreements → agreement.
 */
data class GlobalRatesState(
    /** The page opens on its skeleton until coverage answers. */
    val coveredLoading: Boolean = true,
    val covered: Set<String> = emptySet(),
    /** Kept across views and refreshes, as the web keeps it. */
    val sidebarSearch: String = "",
    val view: RatesView = RatesView.Welcome,
    val territoryId: String? = null,
    val unions: List<UnionSummary> = emptyList(),
    val branches: List<Branch> = emptyList(),
    val unionsLoading: Boolean = false,
    /** The territory page's filter — cleared whenever that view is left. */
    val territoryFilter: String = "",
    /** The branch open in the "union" view. */
    val branch: Branch? = null,
    val agreements: List<AgreementSummary> = emptyList(),
    val agreementsLoading: Boolean = false,
    val agreementsFilter: String = "",
    val agreementOrigin: AgreementOrigin? = null,
    val agreement: AgreementDocument? = null,
    val agreementLoading: Boolean = false,
    val agreementFailed: Boolean = false,
    /** Employment statuses per territory — refetched after a Refresh. */
    val empStatuses: Map<String, List<EmpStatus>> = emptyMap(),
    val rates: List<DesignationRate> = emptyList(),
    val ratesLoading: Boolean = false,
    val refreshing: Boolean = false,
) {
    val sections: List<UnionSection>
        get() = GlobalRatesRules.unionSections(unions, branches, territoryFilter)

    val filteredAgreements: List<AgreementSummary>
        get() = GlobalRatesRules.filterAgreements(agreements, agreementsFilter)
}
