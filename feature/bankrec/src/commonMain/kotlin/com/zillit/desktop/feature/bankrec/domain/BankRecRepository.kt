package com.zillit.desktop.feature.bankrec.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * The bank reconciliation service.
 *
 * On its own host, `bankreconciliationapi`, with one exception: the production's
 * bank accounts belong to the account hub and are read from there. That is not
 * a tidiness problem to fix — the account is the only source of a period's
 * currency, and this module has to know it to say what a balance means.
 *
 * The lists — exceptions, alerts, variances — are fetched whole, once, and
 * filtered by period on the client. Every row carries its own `period_id`, so
 * switching period is a filter rather than a request, which is how the web
 * reads them too.
 */
@Suppress("TooManyFunctions") // One call per endpoint; the service has this many.
interface BankRecRepository {

    // -- periods ------------------------------------------------------------

    suspend fun periods(): ZillitResult<List<BankPeriod>>

    /** The production's bank accounts, from the account hub. */
    suspend fun bankAccounts(): ZillitResult<List<BankAccountRef>>

    /**
     * The project's currencies and their rates, also from the account hub.
     *
     * Needed because a period's balances are in its **bank account's**
     * currency while the ledger's are in the project's, and the two have to
     * read as one comparable unit before a difference means anything.
     */
    suspend fun projectCurrencies(): ZillitResult<ProjectRates>

    /**
     * Closes a period.
     *
     * Marks its invoices paid, computes the closing balance and locks it. A
     * signed-off period cannot be reopened from here.
     */
    suspend fun signOffPeriod(id: String, note: String): ZillitResult<Unit>

    /**
     * Deletes periods and everything the reconciliation produced for them.
     *
     * Transactions, exceptions, fraud alerts and FX variances go with them, and
     * any ledger entry they matched is un-matched. The server refuses a period
     * that has been signed off.
     */
    suspend fun deletePeriods(periodIds: List<String>): ZillitResult<Unit>

    /** The signed-off periods as one PDF, rendered by the service. */
    suspend fun exportPeriodsPdf(periodIds: List<String>, company: CompanyDetails): ZillitResult<ByteArray>

    // -- the workspace ------------------------------------------------------

    /** One period's bank lines and the ledger entries they could match. */
    suspend fun workspace(periodId: String): ZillitResult<WorkspaceData>

    /**
     * Reconciles a bank line against one ledger entry.
     *
     * One target, not a list: the service matches a transaction to a single
     * record, and the type has to travel with the id because an invoice, a
     * quick-added transaction and an FX posting are different tables.
     */
    suspend fun matchTransaction(id: String, entityId: String, kind: LedgerEntryKind): ZillitResult<Unit>

    /** Runs the matching rules over a period again. */
    suspend fun rerunAutoMatch(periodId: String): ZillitResult<Unit>

    /**
     * Ingests a statement already uploaded to storage.
     *
     * The file goes to storage first and only its pointer comes here; the
     * service takes no multipart upload. No period is sent: the statement's
     * own dates decide which periods it opens, as on the web.
     */
    suspend fun importStatement(attachment: StatementUpload, bankAccountId: String): ZillitResult<ImportResult>

    // -- exceptions ---------------------------------------------------------

    suspend fun exceptions(): ZillitResult<List<BankException>>

    suspend fun setExceptionStatus(id: String, status: ExceptionStatus): ZillitResult<Unit>

    /**
     * Posts an exception to the ledger and clears it.
     *
     * [fromWorkspace] shapes the body the way that surface's form does: the
     * workspace drawer sends no statement date or invoice number, and a blank
     * effective date as null rather than as an empty string.
     */
    suspend fun quickAddException(id: String, form: QuickAddForm, fromWorkspace: Boolean): ZillitResult<Unit>

    /** One period's exceptions as a PDF. The route takes a single period. */
    suspend fun exportExceptionsPdf(periodId: String, company: CompanyDetails): ZillitResult<ByteArray>

    // -- fraud --------------------------------------------------------------

    suspend fun fraudAlerts(): ZillitResult<List<FraudAlert>>

    suspend fun escalateFraudAlert(id: String): ZillitResult<Unit>

    suspend fun dismissFraudAlert(id: String): ZillitResult<Unit>

    suspend fun fraudAuditLog(): ZillitResult<List<FraudAuditEntry>>

    /** The audit trail as the filters on screen scope it, as CSV or PDF. */
    suspend fun exportAuditLog(
        format: AuditExportFormat,
        filters: AuditFilters,
        company: CompanyDetails,
    ): ZillitResult<ByteArray>

    // -- FX -----------------------------------------------------------------

    suspend fun fxVariances(): ZillitResult<List<FxVariance>>

    suspend fun postFxVariance(id: String, posting: FxPosting): ZillitResult<Unit>

    // -- rules --------------------------------------------------------------

    suspend fun rulesSettings(): ZillitResult<RulesSettings>

    /** Saves one half of the rules; the other is left as it stands. */
    suspend fun saveAutoMatchRules(rules: Map<String, Boolean>): ZillitResult<Unit>

    suspend fun saveFraudRules(rules: Map<String, FraudRule>): ZillitResult<Unit>

    // -- shared links -------------------------------------------------------

    /**
     * The summary a link to [periodId] would show, as the accountant previews
     * it — every section, because this route names no link. Null when the
     * service has nothing for the period.
     */
    suspend fun portalPreview(periodId: String, bankAccountId: String): ZillitResult<PortalPreview?>

    suspend fun portalLinks(): ZillitResult<List<PortalLink>>

    suspend fun createPortalLink(draft: PortalLinkDraft): ZillitResult<Unit>

    suspend fun updatePortalLink(id: String, draft: PortalLinkDraft): ZillitResult<Unit>

    suspend fun revokePortalLink(id: String): ZillitResult<Unit>
}

/**
 * A statement file already in storage.
 *
 * The service takes the pointer, never the bytes: the host uploads the file
 * and hands back the attachment record. The field names are the account hub's
 * own attachment shape, which every module here shares — `media` is the object
 * key, for historical reasons nobody has undone.
 */
data class StatementUpload(
    val media: String = "",
    val bucket: String = "",
    val region: String = "",
    val fileName: String = "",
    val contentType: String = "",
    val contentSubtype: String = "",
)

/** Which file the audit trail is exported as. */
enum class AuditExportFormat(val wire: String, val label: String, val extension: String) {
    Csv("export-csv", "CSV", "csv"),
    Pdf("export-pdf", "PDF", "pdf"),
}

/**
 * Who the exported documents are for — printed in their headers.
 *
 * The web reads these from the open production; so does the host here.
 */
data class CompanyDetails(
    val projectName: String = "",
    val companyName: String = "",
    val companyAddress: String = "",
    val companyEmail: String = "",
)

/**
 * The project's default currency, and what a foreign one is worth against it.
 *
 * A rate is stored as `foreign = default × rate`, so converting the other way
 * is a division. A currency with no rate is not converted at all: a figure
 * converted at a rate nobody set is worse than one clearly marked as being in
 * another currency.
 */
data class ProjectRates(
    val defaultCode: String = "GBP",
    val rates: Map<String, Double> = emptyMap(),
) {
    fun rateFor(code: String): Double? =
        rates[code.trim().uppercase()]?.takeIf { it > 0 }

    /** [amount] in [code], expressed in the project's own currency. */
    fun toDefault(amount: Double, code: String?): Double? {
        val from = code?.trim()?.uppercase().orEmpty()
        if (from.isBlank() || from == defaultCode.uppercase()) return amount
        return rateFor(from)?.let { amount / it }
    }

    /**
     * [amounts] summed in the project's currency, each converted at its own
     * rate — and whether any had no rate and went in at face value, which the
     * web discloses rather than hides.
     */
    fun sumInDefault(amounts: List<Pair<Double, String?>>): Pair<Double, Boolean> {
        var total = 0.0
        var unrated = false
        amounts.forEach { (amount, code) ->
            val converted = toDefault(amount, code)
            if (converted != null) {
                total += converted
            } else {
                total += amount
                unrated = true
            }
        }
        return total to unrated
    }
}
