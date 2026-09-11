package com.zillit.desktop.feature.taxfiling.ui

import com.zillit.desktop.feature.taxfiling.domain.BoxMapping
import com.zillit.desktop.feature.taxfiling.domain.DraftDiagnostics
import com.zillit.desktop.feature.taxfiling.domain.FiledReturn
import com.zillit.desktop.feature.taxfiling.domain.FilingObligation
import com.zillit.desktop.feature.taxfiling.domain.TaxCompany
import com.zillit.desktop.feature.taxfiling.domain.TaxFiling
import com.zillit.desktop.feature.taxfiling.domain.TaxRegistration
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.domain.VatReturn

/** Which surface is showing: the registrations, or one registration's returns. */
enum class TaxFilingView { Registrations, Return }

/** A registration being added. */
data class RegistrationDraft(
    val companyId: String = "",
    val registrationNumber: String = "",
    val frequency: String = QUARTERLY,
    val saving: Boolean = false,
) {
    /**
     * A UK VAT number is nine digits, sometimes written with spaces or a `GB`.
     *
     * Checked here as well as by the server because a wrong number does not
     * fail on save — it fails later against HMRC, by which point the
     * accountant is somewhere else entirely.
     */
    val problem: String?
        get() = when {
            companyId.isBlank() -> "Choose the company this registration belongs to."
            digits.length != VRN_DIGITS -> "A VAT registration number is nine digits."
            else -> null
        }

    val digits: String get() = registrationNumber.filter { it.isDigit() }

    companion object {
        const val QUARTERLY = "quarterly"
        const val MONTHLY = "monthly"
        const val ANNUAL = "annual"
        private const val VRN_DIGITS = 9

        val frequencies = listOf(QUARTERLY, MONTHLY, ANNUAL)
    }
}

/**
 * One registration's filing surface.
 *
 * [draft] is what the ledger produced for the chosen period, and it is not the
 * return until it is submitted — building it as often as the mapping changes
 * is the point of having it.
 */
data class ReturnState(
    val registration: TaxRegistration? = null,
    val obligations: List<FilingObligation> = emptyList(),
    val periodKey: String = "",
    val boxMap: List<BoxMapping> = emptyList(),
    val draft: VatReturn? = null,
    /** What the server saw while building [draft] — see the all-zero notice. */
    val diagnostics: DraftDiagnostics = DraftDiagnostics(),
    /** Returns already filed through Zillit, with HMRC's receipts. */
    val filed: List<FiledReturn> = emptyList(),
    val loading: Boolean = false,
    val syncing: Boolean = false,
    val calculating: Boolean = false,
    val exporting: Boolean = false,
    val submitting: Boolean = false,
    /** Set when the accountant has asked to file, before they confirm. */
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
    val period: FilingObligation? get() = obligations.firstOrNull { it.periodKey == periodKey }

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

    fun mappingFor(box: VatBox): BoxMapping =
        boxMap.firstOrNull { it.box == box.slot } ?: BoxMapping(box = box.slot)

    /**
     * Whether this return can be filed.
     *
     * A period that is not open has been filed already and cannot be filed
     * again; a draft that has not been built is a return of nothing.
     */
    val canSubmit: Boolean
        get() = registration?.connected == true &&
            period?.isOpen == true &&
            draft != null &&
            !submitting &&
            periodKey != filedPeriodKey
}

data class TaxFilingUiState(
    val loading: Boolean = false,
    val filings: List<TaxFiling> = emptyList(),
    val companies: List<TaxCompany> = emptyList(),
    val registrations: List<TaxRegistration> = emptyList(),
    val view: TaxFilingView = TaxFilingView.Registrations,
    val returnState: ReturnState = ReturnState(),
    val draft: RegistrationDraft? = null,
    val removing: TaxRegistration? = null,
    val notice: String? = null,
    /** False when the host wired no anti-fraud signals; HMRC calls are refused. */
    val canReachAuthority: Boolean = true,
)
