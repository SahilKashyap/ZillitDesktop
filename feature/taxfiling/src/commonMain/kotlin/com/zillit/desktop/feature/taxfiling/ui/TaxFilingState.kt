package com.zillit.desktop.feature.taxfiling.ui

import androidx.compose.foundation.ScrollState
import com.zillit.desktop.feature.taxfiling.domain.BoxMapping
import com.zillit.desktop.feature.taxfiling.domain.CoaCode
import com.zillit.desktop.feature.taxfiling.domain.DraftDiagnostics
import com.zillit.desktop.feature.taxfiling.domain.FiledReturn
import com.zillit.desktop.feature.taxfiling.domain.FilingObligation
import com.zillit.desktop.feature.taxfiling.domain.LayerSet
import com.zillit.desktop.feature.taxfiling.domain.TaxCompany
import com.zillit.desktop.feature.taxfiling.domain.TaxFiling
import com.zillit.desktop.feature.taxfiling.domain.TaxFilingRoute
import com.zillit.desktop.feature.taxfiling.domain.TaxFrequency
import com.zillit.desktop.feature.taxfiling.domain.TaxDates
import com.zillit.desktop.feature.taxfiling.domain.TaxRegistration
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.domain.VatReturn

/**
 * The page's scroll and where its viewport starts on the window — what the
 * summary rail needs to stay in view while the boxes scroll past it.
 */
internal class StickyViewport(val scroll: ScrollState, val viewportTop: () -> Float)

/** Which surface a filing is showing: its companies, or one company's return. */
enum class TaxFilingView { Registrations, Return }

/**
 * The landing page's catalogue — the web's `TaxFilingHub`.
 *
 * Filings for the countries the production's companies are in come first,
 * under "Your countries"; the rest follow under "Other countries".
 */
data class CatalogState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val filings: List<TaxFiling> = emptyList(),
    /** Upper-case country codes of the production's companies. */
    val companyCountries: Set<String> = emptySet(),
) {
    val mine: List<TaxFiling> get() = filings.filter { it.country.uppercase() in companyCountries }
    val others: List<TaxFiling> get() = filings.filterNot { it.country.uppercase() in companyCountries }

    /** Two labelled groups only when both have something; one plain grid otherwise. */
    val grouped: Boolean get() = mine.isNotEmpty() && others.isNotEmpty()
}

/** A registration being added — the web's `RegisterModal`. */
data class RegistrationDraft(
    val companyId: String = "",
    /** Digits only, at most nine — the field refuses anything else as it is typed. */
    val registrationNumber: String = "",
    /** `yyyy-mm-dd`, optional. */
    val registrationDate: String = "",
    val frequency: String = TaxFrequency.Quarterly.wire,
    val saving: Boolean = false,
) {
    /**
     * A UK VAT number is nine digits.
     *
     * Checked here as well as by the server because a wrong number does not
     * fail on save — it fails later against HMRC, by which point the
     * accountant is somewhere else entirely.
     */
    val numberValid: Boolean
        get() = registrationNumber.length == VRN_DIGITS && registrationNumber.all { it.isDigit() }

    /** A date typed but not a date. Blank is fine: the field is optional. */
    val dateInvalid: Boolean
        get() = registrationDate.isNotBlank() && !TaxDates.isValid(registrationDate)

    val canSubmit: Boolean
        get() = companyId.isNotBlank() && numberValid && !dateInvalid && !saving

    companion object {
        const val VRN_DIGITS = 9

        /** What the number field keeps of what was typed or pasted. */
        fun cleanNumber(typed: String): String = typed.filter { it.isDigit() }.take(VRN_DIGITS)
    }
}

/**
 * What a box mapping is chosen from, loaded once per production.
 *
 * [coaFailed] is kept apart from an empty chart: "couldn't load the chart" and
 * "the chart is empty" call for different things from the accountant.
 */
data class MappingLookups(
    val loaded: Boolean = false,
    val coa: List<CoaCode> = emptyList(),
    val coaLoading: Boolean = false,
    val coaFailed: Boolean = false,
    val layerSets: List<LayerSet> = emptyList(),
    val assetTags: List<String> = emptyList(),
)

/**
 * One registration's filing surface — the web's `VATReturnView`.
 *
 * [draft] is what the ledger produced for the chosen period, and it is not the
 * return until it is submitted — building it as often as the mapping changes
 * is the point of having it.
 */
data class ReturnState(
    val registration: TaxRegistration? = null,
    val obligations: List<FilingObligation> = emptyList(),
    val obligationsLoading: Boolean = false,
    /** Blank until the accountant picks one, as on the web. */
    val periodKey: String = "",
    /** The company's boxes as edited on screen; a box with no entry is unmapped. */
    val mappings: Map<VatBox, BoxMapping> = emptyMap(),
    val mappingLoading: Boolean = false,
    val savingMapping: Boolean = false,
    /** Which accordion rows are open. Box 1 starts open, as on the web. */
    val expanded: Set<VatBox> = setOf(VatBox.DueOnSales),
    val draft: VatReturn? = null,
    /** What the server saw while building [draft] — see the all-zero message. */
    val diagnostics: DraftDiagnostics = DraftDiagnostics(),
    /**
     * The mapping has changed since [draft] was built.
     *
     * The figures stay on screen, as they do on the web, but they no longer
     * describe the mapping beside them — so they cannot be submitted until
     * they are recalculated.
     */
    val draftStale: Boolean = false,
    /** Returns already filed through Zillit, with HMRC's receipts. */
    val filed: List<FiledReturn> = emptyList(),
    val syncing: Boolean = false,
    val calculating: Boolean = false,
    val exporting: Boolean = false,
    val submitting: Boolean = false,
    /** Set when the accountant has asked to submit, before they confirm. */
    val confirmingSubmit: Boolean = false,
    /**
     * The period this screen has just filed.
     *
     * Held because the obligation list still says *open* until HMRC has been
     * asked again, and that gap is wide enough to press the button twice. The
     * second filing would be refused by HMRC, but as an error the accountant
     * has to interpret rather than a button that has stopped offering.
     */
    val filedPeriodKey: String = "",
) {
    val period: FilingObligation?
        get() = obligations.firstOrNull { it.periodKey == periodKey && periodKey.isNotBlank() }

    /** The draft with boxes 3 and 5 worked out, which is what is shown. */
    val shown: VatReturn? get() = draft?.computed()

    /**
     * The receipt for the chosen period, when Zillit filed it.
     *
     * A period fulfilled elsewhere has no row here and is not a mistake: it
     * is fulfilled all the same, and there is simply nothing to show.
     */
    val filedForPeriod: FiledReturn?
        get() = filed.firstOrNull { it.periodKey == periodKey && it.periodKey.isNotBlank() }

    fun mappingFor(box: VatBox): BoxMapping = mappings[box] ?: BoxMapping(box = box.slot)

    /** Every box as it would be saved, in HMRC's order. */
    val mappingRows: List<BoxMapping> get() = VatBox.mappable.map(::mappingFor)

    /** The first box whose date window is not a date, which blocks saving. */
    val invalidDateBox: VatBox? get() = VatBox.mappable.firstOrNull { mappingFor(it).hasInvalidDate }

    val anyCollapsed: Boolean get() = VatBox.mappable.any { it !in expanded }

    val connected: Boolean get() = registration?.connected == true

    /**
     * Whether this return can be filed.
     *
     * A period that is not open has been filed already and cannot be filed
     * again; a draft that has not been built — or no longer matches its
     * mapping — is not the return anyone checked.
     */
    val canSubmit: Boolean
        get() = connected &&
            period?.isOpen == true &&
            draft != null &&
            !draftStale &&
            !submitting &&
            !calculating &&
            periodKey != filedPeriodKey
}

data class TaxFilingUiState(
    val route: TaxFilingRoute = TaxFilingRoute.Catalog,
    val catalog: CatalogState = CatalogState(),
    /** The registrations and companies are being fetched for the first time. */
    val loading: Boolean = false,
    val companies: List<TaxCompany> = emptyList(),
    val registrations: List<TaxRegistration> = emptyList(),
    val view: TaxFilingView = TaxFilingView.Registrations,
    val returnState: ReturnState = ReturnState(),
    val draft: RegistrationDraft? = null,
    val removing: TaxRegistration? = null,
    val removeInFlight: Boolean = false,
    /** The registration whose HMRC consent is open in the browser. */
    val connectingId: String? = null,
    val lookups: MappingLookups = MappingLookups(),
    /** False when the host wired no anti-fraud signals; HMRC calls are refused. */
    val canReachAuthority: Boolean = true,
) {
    /** The registrations wearing their companies' names. */
    val named: List<TaxRegistration> get() = registrations.map { it.named(companies) }

    /** Companies with no registration yet — one registration per company. */
    val availableCompanies: List<TaxCompany>
        get() {
            val taken = registrations.map { it.companyId }.toSet()
            return companies.filterNot { it.id in taken }
        }

    val inReturn: Boolean get() = view == TaxFilingView.Return && returnState.registration != null
}
