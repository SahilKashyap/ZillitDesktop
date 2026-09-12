package com.zillit.desktop.feature.taxfiling.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * The tax-filing service, and the three account-hub lists a box mapping is
 * picked from.
 *
 * Two kinds of call live here and the difference matters: most reach only
 * Zillit's own services, but [syncObligations] and [submitReturn] reach the tax
 * authority, carry [FraudSignals], and in the second case file a legal return.
 */
interface TaxFilingRepository {

    /** The regimes this service can file. One today: HMRC's MTD VAT. */
    suspend fun catalog(): ZillitResult<List<TaxFiling>>

    suspend fun companies(): ZillitResult<List<TaxCompany>>

    /** Every registration on the production, unnamed — see [TaxRegistration.named]. */
    suspend fun registrations(): ZillitResult<List<TaxRegistration>>

    suspend fun createRegistration(request: RegistrationRequest): ZillitResult<Unit>

    /**
     * Removes a registration. The service disconnects it from HMRC and clears
     * its obligation and return history; the company's box mapping is kept.
     */
    suspend fun deleteRegistration(id: String): ZillitResult<Unit>

    /**
     * Everything the service holds for a registration, no secrets, as the
     * pretty-printed JSON the data-portability export saves.
     */
    suspend fun exportRegistration(id: String): ZillitResult<String>

    /**
     * The authority's consent page for this registration.
     *
     * Opened in the machine's browser: the grant is between the accountant and
     * HMRC, and it is not this application's to intermediate.
     */
    suspend fun connectUrl(registrationId: String): ZillitResult<String>

    /** Which boxes read which ledger entries, for a company. */
    suspend fun boxMap(companyId: String): ZillitResult<List<BoxMapping>>

    suspend fun saveBoxMap(companyId: String, rows: List<BoxMapping>): ZillitResult<Unit>

    /** The periods already known, without asking the authority again. */
    suspend fun obligations(registrationId: String): ZillitResult<List<FilingObligation>>

    /** Re-asks the authority. Reaches HMRC, so it carries [signals]. */
    suspend fun syncObligations(
        registrationId: String,
        fromDate: String,
        toDate: String,
        signals: FraudSignals,
    ): ZillitResult<List<FilingObligation>>

    /**
     * Builds the nine boxes for a period from the ledger.
     *
     * Reads only: nothing is filed, and it can be run as often as the
     * accountant wants to check the mapping.
     */
    suspend fun buildDraft(registrationId: String, periodKey: String): ZillitResult<VatDraft>

    /** The returns filed through Zillit, with HMRC's receipts. */
    suspend fun filedReturns(registrationId: String): ZillitResult<List<FiledReturn>>

    /** The ledger lines behind each box for a period, for the export. */
    suspend fun ledgerLines(registrationId: String, periodKey: String): ZillitResult<List<LedgerLine>>

    /**
     * Files the return with the authority.
     *
     * The one call here that cannot be undone. A period, once fulfilled,
     * cannot be filed again.
     */
    suspend fun submitReturn(
        registrationId: String,
        periodKey: String,
        vatReturn: VatReturn,
        signals: FraudSignals,
    ): ZillitResult<Unit>

    /** The chart's postable accounts, in code order. */
    suspend fun coaCodes(): ZillitResult<List<CoaCode>>

    /** The active tracking sets, each with its pickable codes. */
    suspend fun layerSets(): ZillitResult<List<LayerSet>>

    /** The production's asset tags, as Production Setup keeps them. */
    suspend fun assetTags(): ZillitResult<List<String>>
}
