package com.zillit.desktop.feature.bankrec.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * The bank reconciliation service.
 *
 * On its own host, `bankreconciliationapi`, with one exception: the production's
 * bank accounts belong to the account hub and are read from there. That is not
 * a tidiness problem to fix — the account is the only source of a period's
 * currency, and this module has to know it to say what a balance means.
 */
@Suppress("TooManyFunctions") // One call per endpoint; the service has this many.
interface BankRecRepository {

    // -- periods ------------------------------------------------------------

    suspend fun periods(): ZillitResult<List<BankPeriod>>

    suspend fun period(id: String): ZillitResult<BankPeriod>

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

    suspend fun updatePeriodNote(id: String, note: String): ZillitResult<Unit>

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
     *
     * A POST rather than a DELETE because it carries a body.
     */
    suspend fun deletePeriods(periodIds: List<String>): ZillitResult<Unit>

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
    suspend fun matchTransaction(
        id: String,
        entityId: String,
        kind: LedgerEntryKind,
    ): ZillitResult<Unit>

    /** Runs the matching rules over a period again. */
    suspend fun rerunAutoMatch(periodId: String): ZillitResult<Unit>

    /**
     * Ingests a statement already uploaded to storage.
     *
     * The file goes to storage first and only its pointer comes here; the
     * service takes no multipart upload.
     */
    suspend fun importStatement(
        attachment: StatementUpload,
        bankAccountId: String?,
        periodId: String?,
    ): ZillitResult<Unit>

    // -- exceptions ---------------------------------------------------------

    suspend fun exceptions(periodId: String?): ZillitResult<List<BankException>>

    suspend fun setExceptionStatus(
        id: String,
        status: ExceptionStatus,
        notes: String,
    ): ZillitResult<Unit>

    /** Posts an exception to the ledger and clears it. */
    suspend fun quickAddException(id: String, form: QuickAddForm): ZillitResult<Unit>

    // -- fraud --------------------------------------------------------------

    suspend fun fraudAlerts(periodId: String?): ZillitResult<List<FraudAlert>>

    suspend fun escalateFraudAlert(id: String): ZillitResult<Unit>

    suspend fun dismissFraudAlert(id: String): ZillitResult<Unit>

    suspend fun fraudAuditLog(periodId: String?): ZillitResult<List<FraudAuditEntry>>

    // -- FX -----------------------------------------------------------------

    suspend fun fxVariances(periodId: String?): ZillitResult<List<FxVariance>>

    suspend fun postFxVariance(id: String, posting: FxPosting): ZillitResult<Unit>

    /** Posts every unposted variance in a period at once. */
    suspend fun postAllFxVariances(periodId: String): ZillitResult<Unit>

    // -- rules --------------------------------------------------------------

    suspend fun rulesSettings(): ZillitResult<RulesSettings>

    /** Saves one half of the rules; the other is left as it stands. */
    suspend fun saveAutoMatchRules(rules: Map<String, Boolean>): ZillitResult<Unit>

    suspend fun saveFraudRules(rules: Map<String, FraudRule>): ZillitResult<Unit>

    // -- shared links -------------------------------------------------------

    suspend fun portalLinks(): ZillitResult<List<PortalLink>>

    suspend fun createPortalLink(draft: PortalLinkDraft): ZillitResult<PortalLink>

    suspend fun updatePortalLink(id: String, draft: PortalLinkDraft): ZillitResult<PortalLink>

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

/** Where the host puts a chosen statement file. Absent leaves import unavailable. */
fun interface StatementUploader {
    /** Uploads and returns the pointer, or a failure the screen can show. */
    suspend fun upload(): ZillitResult<StatementUpload?>
}

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
}
