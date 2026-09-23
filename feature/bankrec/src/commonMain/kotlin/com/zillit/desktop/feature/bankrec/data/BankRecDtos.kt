package com.zillit.desktop.feature.bankrec.data

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.bankrec.domain.AlertInvoice
import com.zillit.desktop.feature.bankrec.domain.BankAccountRef
import com.zillit.desktop.feature.bankrec.domain.BankException
import com.zillit.desktop.feature.bankrec.domain.BankPeriod
import com.zillit.desktop.feature.bankrec.domain.BankTransaction
import com.zillit.desktop.feature.bankrec.domain.ExceptionStatus
import com.zillit.desktop.feature.bankrec.domain.ExceptionType
import com.zillit.desktop.feature.bankrec.domain.FraudAlert
import com.zillit.desktop.feature.bankrec.domain.FraudAuditEntry
import com.zillit.desktop.feature.bankrec.domain.FraudDetection
import com.zillit.desktop.feature.bankrec.domain.FraudRule
import com.zillit.desktop.feature.bankrec.domain.FraudSignal
import com.zillit.desktop.feature.bankrec.domain.FraudStatus
import com.zillit.desktop.feature.bankrec.domain.FraudType
import com.zillit.desktop.feature.bankrec.domain.FraudVendor
import com.zillit.desktop.feature.bankrec.domain.FxDetail
import com.zillit.desktop.feature.bankrec.domain.FxStatus
import com.zillit.desktop.feature.bankrec.domain.FxVariance
import com.zillit.desktop.feature.bankrec.domain.ImportResult
import com.zillit.desktop.feature.bankrec.domain.LedgerEntry
import com.zillit.desktop.feature.bankrec.domain.LedgerEntryKind
import com.zillit.desktop.feature.bankrec.domain.PeriodStatus
import com.zillit.desktop.feature.bankrec.domain.PortalLink
import com.zillit.desktop.feature.bankrec.domain.PortalNotify
import com.zillit.desktop.feature.bankrec.domain.PortalOrgType
import com.zillit.desktop.feature.bankrec.domain.PortalPermission
import com.zillit.desktop.feature.bankrec.domain.PortalPreview
import com.zillit.desktop.feature.bankrec.domain.PortalStatus
import com.zillit.desktop.feature.bankrec.domain.PreviewException
import com.zillit.desktop.feature.bankrec.domain.PreviewFraudAlert
import com.zillit.desktop.feature.bankrec.domain.PreviewFx
import com.zillit.desktop.feature.bankrec.domain.ProjectRates
import com.zillit.desktop.feature.bankrec.domain.RulesSettings
import com.zillit.desktop.feature.bankrec.domain.TxnStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * A number, the text of one, or nothing.
 *
 * This service writes money and epochs as both, depending on the column and
 * the controller, so every numeric field is read through here rather than
 * typed as `Double?` and silently lost when it arrives quoted.
 */
internal fun JsonElement?.asDouble(): Double? = when {
    this == null || this is JsonNull -> null
    this is JsonPrimitive -> doubleOrNull ?: content.trim().toDoubleOrNull()
    else -> null
}

internal fun JsonElement?.asLong(): Long? = when {
    this == null || this is JsonNull -> null
    this is JsonPrimitive -> longOrNull ?: content.trim().toDoubleOrNull()?.toLong()
    else -> null
}

internal fun JsonElement?.asInt(): Int? = asDouble()?.toInt()

internal fun JsonElement?.asText(): String = when {
    this == null || this is JsonNull -> ""
    this is JsonPrimitive -> content
    else -> ""
}

/**
 * A currency code, whichever of its two shapes arrived.
 *
 * Older rows carry `"GBP"`, newer ones the whole currency —
 * `{"code":"GBP","name":…,"symbol":"£"}`. A field typed `String` does not merely
 * miss the code on the second shape: the whole response fails to decode, and
 * one changed row empties a list.
 */
internal fun JsonElement?.asCurrencyCode(): String? = when (this) {
    is JsonPrimitive -> if (isString) content.trim().takeIf { it.isNotEmpty() }?.uppercase() else null
    is JsonObject -> (this["code"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
    else -> null
}

/**
 * A list, or the JSON text of one.
 *
 * `matched_invoice_ids`, `tr_ids`, `permissions`, `signals` and
 * `ledger_description` all arrive as either, because the service stores them as
 * text and parses inconsistently on the way out. Reading only the array shape
 * leaves a matched line looking unmatched.
 */
internal fun JsonElement?.asArray(): JsonArray? = when {
    this == null || this is JsonNull -> null
    this is JsonArray -> this
    this is JsonPrimitive && isString -> runCatching { lenient.parseToJsonElement(content) as? JsonArray }.getOrNull()
    else -> null
}

internal fun JsonElement?.asStringList(): List<String> =
    asArray()?.map { it.asText() }?.filter { it.isNotBlank() }.orEmpty()

/** An object, or the JSON text of one — the audit trail's `metadata` is both. */
internal fun JsonElement?.asObject(): JsonObject? = when {
    this == null || this is JsonNull -> null
    this is JsonObject -> this
    this is JsonPrimitive && isString -> runCatching { lenient.parseToJsonElement(content) as? JsonObject }.getOrNull()
    else -> null
}

/** True, "true", or 1 — this service writes booleans all three ways. */
internal fun JsonElement?.asBoolean(): Boolean = when {
    this == null || this is JsonNull -> false
    this is JsonPrimitive -> content.equals("true", ignoreCase = true) || content == "1"
    else -> false
}

@Serializable
internal data class PeriodDto(
    @SerialName("id") val id: String? = null,
    @SerialName("period") val period: JsonElement? = null,
    @SerialName("month") val month: JsonElement? = null,
    @SerialName("year") val year: JsonElement? = null,
    @SerialName("bank_account_id") val bankAccountId: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("total_txns") val totalTxns: JsonElement? = null,
    /** The preview route's spelling of the same count. */
    @SerialName("total_transactions") val totalTransactions: JsonElement? = null,
    @SerialName("matched_count") val matchedCount: JsonElement? = null,
    @SerialName("suggested_count") val suggestedCount: JsonElement? = null,
    @SerialName("unmatched_count") val unmatchedCount: JsonElement? = null,
    @SerialName("fraud_count") val fraudCount: JsonElement? = null,
    @SerialName("opening_bank") val openingBank: JsonElement? = null,
    @SerialName("closing_bank") val closingBank: JsonElement? = null,
    @SerialName("closing_zillit") val closingZillit: JsonElement? = null,
    @SerialName("difference") val difference: JsonElement? = null,
    @SerialName("fx_variance_total") val fxVarianceTotal: JsonElement? = null,
    @SerialName("opening_date") val openingDate: JsonElement? = null,
    @SerialName("closing_date") val closingDate: JsonElement? = null,
    @SerialName("signed_by") val signedBy: String? = null,
    @SerialName("signed_by_name") val signedByName: String? = null,
    @SerialName("signed_by_designation") val signedByDesignation: String? = null,
    @SerialName("signed_at") val signedAt: JsonElement? = null,
    @SerialName("sign_off_notes") val signOffNotes: String? = null,
    @SerialName("created_at") val createdAt: JsonElement? = null,
    @SerialName("project_name") val projectName: String? = null,
) {
    fun toDomain() = BankPeriod(
        id = id.orEmpty(),
        periodMillis = period.asLong()?.takeIf { it > 0 },
        legacyMonth = month.asInt(),
        legacyYear = year.asInt(),
        bankAccountId = bankAccountId.orEmpty(),
        status = PeriodStatus.from(status),
        totalTxns = (totalTxns ?: totalTransactions).asInt() ?: totalTransactions.asInt() ?: 0,
        matchedCount = matchedCount.asInt() ?: 0,
        suggestedCount = suggestedCount.asInt() ?: 0,
        unmatchedCount = unmatchedCount.asInt() ?: 0,
        fraudCount = fraudCount.asInt() ?: 0,
        openingBank = openingBank.asDouble() ?: 0.0,
        closingBank = closingBank.asDouble() ?: 0.0,
        closingZillit = closingZillit.asDouble() ?: 0.0,
        difference = difference.asDouble() ?: 0.0,
        fxVarianceTotal = fxVarianceTotal.asDouble() ?: 0.0,
        openingDateMillis = openingDate.asLong(),
        closingDateMillis = closingDate.asLong(),
        signedBy = signedBy.orEmpty(),
        signedByName = signedByName.orEmpty(),
        signedByDesignation = signedByDesignation.orEmpty(),
        signedAtMillis = signedAt.asLong(),
        signOffNotes = signOffNotes.orEmpty(),
        createdAtMillis = createdAt.asLong(),
        projectName = projectName.orEmpty(),
    )
}

@Serializable
internal data class TransactionDto(
    @SerialName("id") val id: String? = null,
    @SerialName("period_id") val periodId: String? = null,
    @SerialName("transaction_date") val transactionDate: JsonElement? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("vendor_name") val vendorName: String? = null,
    @SerialName("reference") val reference: String? = null,
    @SerialName("tr_reference") val trReference: String? = null,
    @SerialName("payment_method") val paymentMethod: String? = null,
    @SerialName("debit") val debit: JsonElement? = null,
    @SerialName("credit") val credit: JsonElement? = null,
    @SerialName("currency") val currency: JsonElement? = null,
    @SerialName("transaction_currency") val transactionCurrency: JsonElement? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("fraud_type") val fraudType: String? = null,
    @SerialName("fraud_status") val fraudStatus: String? = null,
    @SerialName("fraud_score") val fraudScore: JsonElement? = null,
    @SerialName("match_confidence") val matchConfidence: JsonElement? = null,
    @SerialName("matched_invoice_ids") val matchedInvoiceIds: JsonElement? = null,
    @SerialName("foreign_amount") val foreignAmount: JsonElement? = null,
    @SerialName("budget_rate") val budgetRate: JsonElement? = null,
    @SerialName("bank_rate") val bankRate: JsonElement? = null,
    @SerialName("fx_variance") val fxVariance: JsonElement? = null,
    @SerialName("fx_variance_id") val fxVarianceId: String? = null,
    @SerialName("fx_variance_status") val fxVarianceStatus: String? = null,
    @SerialName("exception_id") val exceptionId: String? = null,
    @SerialName("exception_type") val exceptionType: String? = null,
    @SerialName("exception_title") val exceptionTitle: String? = null,
    @SerialName("exception_description") val exceptionDescription: String? = null,
) {
    fun toDomain(): BankTransaction {
        val code = currency.asCurrencyCode() ?: transactionCurrency.asCurrencyCode()
        // A foreign amount is what makes a line an FX payment. Judging by
        // "currency is not the project's" reads every line on a euro account
        // as a foreign payment, which is the whole account.
        val foreign = foreignAmount.asDouble()?.takeIf { it != 0.0 }
        return BankTransaction(
            id = id.orEmpty(),
            periodId = periodId.orEmpty(),
            transactionDateMillis = transactionDate.asLong(),
            description = description.orEmpty(),
            vendorName = vendorName.orEmpty(),
            reference = reference.orEmpty(),
            trReference = trReference.orEmpty(),
            paymentMethod = paymentMethod.orEmpty(),
            debit = debit.asDouble() ?: 0.0,
            credit = credit.asDouble() ?: 0.0,
            currency = code,
            status = TxnStatus.from(status),
            fraudType = FraudType.from(fraudType),
            fraudStatus = FraudStatus.from(fraudStatus),
            fraudScore = fraudScore.asInt(),
            matchConfidence = matchConfidence.asInt(),
            matchedInvoiceIds = matchedInvoiceIds.asStringList(),
            fx = foreign?.let {
                FxDetail(
                    foreignAmount = it,
                    currency = code.orEmpty(),
                    budgetRate = budgetRate.asDouble() ?: 0.0,
                    bankRate = bankRate.asDouble() ?: 0.0,
                    gain = fxVariance.asDouble() ?: 0.0,
                    varianceId = fxVarianceId.orEmpty(),
                    varianceStatus = fxVarianceStatus?.takeIf { s -> s.isNotBlank() } ?: "unposted",
                )
            },
            exceptionId = exceptionId.orEmpty(),
            exceptionType = exceptionType.orEmpty(),
            exceptionTitle = exceptionTitle.orEmpty(),
            exceptionDescription = exceptionDescription.orEmpty(),
        )
    }
}

/**
 * The ledger side of the workspace.
 *
 * Called `invoices` on the wire, but only some of them are: the same list also
 * carries quick-added transactions and posted FX variances, told apart by
 * `entity_type`.
 */
@Serializable
internal data class LedgerEntryDto(
    @SerialName("id") val id: String? = null,
    @SerialName("ledger_id") val ledgerId: String? = null,
    @SerialName("entity_type") val entityType: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("vendor_name") val vendorName: String? = null,
    @SerialName("supplier_name") val supplierName: String? = null,
    @SerialName("invoice_number") val invoiceNumber: String? = null,
    @SerialName("pay_method") val payMethod: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("gross_amount") val grossAmount: JsonElement? = null,
    @SerialName("debit") val debit: JsonElement? = null,
    @SerialName("credit") val credit: JsonElement? = null,
    @SerialName("currency") val currency: JsonElement? = null,
    @SerialName("invoice_date") val invoiceDate: JsonElement? = null,
    @SerialName("date") val date: JsonElement? = null,
    @SerialName("ledger_status") val ledgerStatus: String? = null,
    @SerialName("tr_ids") val trIds: JsonElement? = null,
    @SerialName("ledger_description") val ledgerDescription: JsonElement? = null,
) {
    fun toDomain(): LedgerEntry = LedgerEntry(
        // The ledger row's id when it has one, which is not the record's.
        id = ledgerId?.takeIf { it.isNotBlank() } ?: id.orEmpty(),
        entityId = id.orEmpty(),
        kind = LedgerEntryKind.from(entityType),
        title = title.orEmpty(),
        vendorName = vendorName.orEmpty(),
        supplierName = supplierName.orEmpty(),
        invoiceNumber = invoiceNumber.orEmpty(),
        payMethod = payMethod.orEmpty(),
        departmentId = departmentId.orEmpty(),
        ledgerDescription = ledgerDescription.asStringList(),
        grossAmount = grossAmount.asDouble(),
        debit = debit.asDouble(),
        credit = credit.asDouble(),
        currency = currency.asCurrencyCode(),
        invoiceDateMillis = invoiceDate.asLong(),
        dateMillis = date.asLong(),
        ledgerStatus = ledgerStatus.orEmpty(),
        transactionIds = trIds.asStringList(),
    )
}

@Serializable
internal data class WorkspaceDto(
    @SerialName("transactions") val transactions: List<TransactionDto>? = null,
    @SerialName("invoices") val invoices: List<LedgerEntryDto>? = null,
    @SerialName("closing_zillit") val closingZillit: JsonElement? = null,
)

@Serializable
internal data class BankAccountDto(
    @SerialName("id") val id: String? = null,
    @SerialName("_id") val altId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("bank_name") val bankName: String? = null,
    @SerialName("account_holder_name") val holderName: String? = null,
    @SerialName("account_number") val accountNumber: String? = null,
    @SerialName("sort_code") val sortCode: String? = null,
    @SerialName("iban_number") val iban: String? = null,
    @SerialName("nominal_code") val nominalCode: String? = null,
    @SerialName("currency") val currency: JsonElement? = null,
) {
    fun toDomain() = BankAccountRef(
        id = (id ?: altId).orEmpty(),
        name = name.orEmpty(),
        bankName = bankName.orEmpty(),
        holderName = holderName.orEmpty(),
        currencyCode = currency.asCurrencyCode().orEmpty(),
        accountNumber = accountNumber.orEmpty(),
        sortCode = sortCode.orEmpty(),
        iban = iban.orEmpty(),
        nominalCode = nominalCode.orEmpty(),
    )
}

@Serializable
internal data class ExceptionDto(
    @SerialName("id") val id: String? = null,
    @SerialName("period_id") val periodId: String? = null,
    @SerialName("exception_type") val type: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("currency") val currency: JsonElement? = null,
    @SerialName("transaction") val transaction: TransactionDto? = null,
) {
    fun toDomain() = BankException(
        id = id.orEmpty(),
        periodId = periodId.orEmpty(),
        type = ExceptionType.from(type),
        status = ExceptionStatus.from(status),
        title = title.orEmpty(),
        description = description.orEmpty(),
        notes = notes.orEmpty(),
        transaction = transaction?.toDomain(),
        currency = currency.asCurrencyCode(),
    )
}

@Serializable
internal data class FraudAlertDto(
    @SerialName("id") val id: String? = null,
    @SerialName("period_id") val periodId: String? = null,
    @SerialName("alert_type") val alertType: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("risk_score") val riskScore: JsonElement? = null,
    @SerialName("created_at") val createdAt: JsonElement? = null,
    @SerialName("signals") val signals: JsonElement? = null,
    @SerialName("invoices") val invoices: JsonElement? = null,
    @SerialName("vendor") val vendor: FraudVendorDto? = null,
    @SerialName("transaction") val transaction: TransactionDto? = null,
) {
    fun toDomain() = FraudAlert(
        id = id.orEmpty(),
        periodId = periodId.orEmpty(),
        alertType = FraudType.from(alertType),
        alertTypeWire = alertType.orEmpty(),
        status = FraudStatus.from(status) ?: FraudStatus.Active,
        title = title.orEmpty(),
        description = description.orEmpty(),
        riskScore = riskScore.asInt() ?: 0,
        createdAtMillis = createdAt.asLong(),
        signals = signals.asArray().orEmpty().mapNotNull(::signalOf),
        invoices = invoices.asArray().orEmpty().mapNotNull { element ->
            runCatching { lenient.decodeFromJsonElement<AlertInvoiceDto>(element) }.getOrNull()?.toDomain()
        },
        vendor = vendor?.takeIf { !it.name.isNullOrBlank() }?.toDomain(),
        transaction = transaction?.toDomain(),
    )

    private fun signalOf(element: JsonElement): FraudSignal? = when (element) {
        is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }?.let { FraudSignal(it, it) }
        is JsonObject -> FraudSignal(
            title = element["title"].asText().ifBlank { str(S.desktop_signal) },
            detail = element["detail"].asText(),
        )

        else -> null
    }
}

@Serializable
internal data class AlertInvoiceDto(
    @SerialName("id") val id: String? = null,
    @SerialName("invoice_number") val invoiceNumber: String? = null,
    @SerialName("supplier_name") val supplierName: String? = null,
    @SerialName("gross_amount") val grossAmount: JsonElement? = null,
    @SerialName("currency") val currency: JsonElement? = null,
    @SerialName("pay_method") val payMethod: String? = null,
) {
    fun toDomain() = AlertInvoice(
        id = id.orEmpty(),
        invoiceNumber = invoiceNumber.orEmpty(),
        supplierName = supplierName.orEmpty(),
        grossAmount = grossAmount.asDouble(),
        currency = currency.asCurrencyCode(),
        payMethod = payMethod.orEmpty(),
    )
}

@Serializable
internal data class FraudVendorDto(
    @SerialName("name") val name: String? = null,
    @SerialName("sort_code") val sortCode: String? = null,
    @SerialName("account_number") val accountNumber: String? = null,
) {
    fun toDomain() = FraudVendor(
        name = name.orEmpty(),
        sortCode = sortCode.orEmpty(),
        accountNumber = accountNumber.orEmpty(),
    )
}

/**
 * One row of the audit trail.
 *
 * `metadata` is an object on some rows and the JSON text of one on others, and
 * carries the fraud type and risk score — the row's own top level does not.
 */
@Serializable
internal data class FraudAuditDto(
    @SerialName("id") val id: String? = null,
    @SerialName("action") val action: String? = null,
    @SerialName("performed_by") val performedBy: String? = null,
    @SerialName("bank_account_id") val bankAccountId: String? = null,
    @SerialName("period_id") val periodId: String? = null,
    @SerialName("created_at") val createdAt: JsonElement? = null,
    @SerialName("metadata") val metadata: JsonElement? = null,
    @SerialName("transaction") val transaction: TransactionDto? = null,
    @SerialName("period") val period: PeriodDto? = null,
    @SerialName("bank_account") val bankAccount: BankAccountDto? = null,
) {
    fun toDomain(): FraudAuditEntry {
        val meta = metadata.asObject()
        return FraudAuditEntry(
            id = id.orEmpty(),
            action = action.orEmpty(),
            performedBy = performedBy.orEmpty(),
            bankAccountId = bankAccountId.orEmpty(),
            periodId = periodId.orEmpty(),
            createdAtMillis = createdAt.asLong(),
            fraudType = meta?.get("fraud_type").asText(),
            riskScore = meta?.get("risk_score").asInt()?.takeIf { it != 0 },
            file = meta?.get("file").asText(),
            reason = meta?.get("reason").asText(),
            transaction = transaction?.toDomain(),
            periodMillis = period?.period.asLong()?.takeIf { it > 0 },
            bankName = bankAccount?.let { (it.name ?: it.bankName).orEmpty() }.orEmpty(),
            bankSortCode = bankAccount?.sortCode.orEmpty(),
            bankAccountNumber = bankAccount?.accountNumber.orEmpty(),
        )
    }
}

@Serializable
internal data class FxVarianceDto(
    @SerialName("id") val id: String? = null,
    @SerialName("period_id") val periodId: String? = null,
    @SerialName("invoice_currency") val invoiceCurrency: JsonElement? = null,
    @SerialName("foreign_amount") val foreignAmount: JsonElement? = null,
    @SerialName("budget_gbp") val budgetAmount: JsonElement? = null,
    @SerialName("gbp_paid") val paidAmount: JsonElement? = null,
    @SerialName("variance") val variance: JsonElement? = null,
    @SerialName("budget_rate") val budgetRate: JsonElement? = null,
    @SerialName("bank_rate") val bankRate: JsonElement? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("created_at") val createdAt: JsonElement? = null,
    @SerialName("nominal_code") val nominalCode: String? = null,
    @SerialName("cost_centre") val costCentre: String? = null,
    @SerialName("transaction") val transaction: TransactionDto? = null,
) {
    fun toDomain() = FxVariance(
        id = id.orEmpty(),
        periodId = periodId.orEmpty(),
        invoiceCurrency = invoiceCurrency.asCurrencyCode().orEmpty(),
        foreignAmount = foreignAmount.asDouble() ?: 0.0,
        // `budget_gbp` and `gbp_paid` are the service's own names and are
        // *not* sterling — they are the project's default currency, whatever
        // that is. Renamed here so nothing downstream believes the field name.
        budgetAmount = budgetAmount.asDouble() ?: 0.0,
        paidAmount = paidAmount.asDouble() ?: 0.0,
        variance = variance.asDouble() ?: 0.0,
        budgetRate = budgetRate.asDouble(),
        bankRate = bankRate.asDouble(),
        status = FxStatus.from(status),
        createdAtMillis = createdAt.asLong(),
        vendorName = transaction?.vendorName.orEmpty(),
        reference = transaction?.reference.orEmpty(),
        nominalCode = nominalCode.orEmpty(),
        costCentre = costCentre.orEmpty(),
    )
}

@Serializable
internal data class PortalLinkDto(
    @SerialName("id") val id: String? = null,
    @SerialName("token") val token: String? = null,
    @SerialName("recipient_name") val recipientName: String? = null,
    @SerialName("recipient_email") val recipientEmail: String? = null,
    @SerialName("org_type") val orgType: String? = null,
    @SerialName("bank_account_id") val bankAccountId: String? = null,
    @SerialName("period_id") val periodId: String? = null,
    @SerialName("period_label") val periodLabel: String? = null,
    @SerialName("permissions") val permissions: JsonElement? = null,
    @SerialName("notify_on_view") val notifyOnView: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("expires_at") val expiresAt: JsonElement? = null,
    @SerialName("created_at") val createdAt: JsonElement? = null,
    @SerialName("last_viewed_at") val lastViewedAt: JsonElement? = null,
    /** The web reads `views`; `view_count` is kept for rows written before it. */
    @SerialName("views") val views: JsonElement? = null,
    @SerialName("view_count") val viewCount: JsonElement? = null,
) {
    fun toDomain() = PortalLink(
        id = id.orEmpty(),
        token = token.orEmpty(),
        recipientName = recipientName.orEmpty(),
        recipientEmail = recipientEmail.orEmpty(),
        orgType = PortalOrgType.from(orgType),
        orgTypeWire = orgType.orEmpty(),
        bankAccountId = bankAccountId.orEmpty(),
        periodId = periodId.orEmpty(),
        periodLabel = periodLabel.orEmpty(),
        permissions = permissions.asStringList().mapNotNull(PortalPermission::from).distinct(),
        notifyOnView = PortalNotify.from(notifyOnView),
        status = PortalStatus.from(status),
        expiresAtMillis = expiresAt.asLong(),
        createdAtMillis = createdAt.asLong(),
        lastViewedAtMillis = lastViewedAt.asLong(),
        views = views.asInt() ?: viewCount.asInt() ?: 0,
    )
}

/**
 * The accountant's preview of a link's page.
 *
 * `permissions` stays a raw element because its *absence* is meaningful — see
 * [PortalPreview]. Null here, or JSON null, is "unspecified".
 */
@Serializable
internal data class PortalPreviewDto(
    @SerialName("period") val period: PeriodDto? = null,
    @SerialName("bank_account") val bankAccount: BankAccountDto? = null,
    @SerialName("project_name") val projectName: String? = null,
    @SerialName("exceptions") val exceptions: List<PreviewExceptionDto>? = null,
    @SerialName("fraud_alerts") val fraudAlerts: List<PreviewFraudAlertDto>? = null,
    @SerialName("fx_variances") val fxVariances: List<PreviewFxDto>? = null,
    @SerialName("transactions") val transactions: List<TransactionDto>? = null,
    @SerialName("ledger_entries") val ledgerEntries: List<LedgerEntryDto>? = null,
    @SerialName("permissions") val permissions: JsonElement? = null,
) {
    fun toDomain(): PortalPreview? {
        val row = period?.toDomain() ?: return null
        return PortalPreview(
            period = row,
            bankAccountName = bankAccount?.name.orEmpty(),
            bankAccountHolder = bankAccount?.holderName.orEmpty(),
            bankAccountCurrency = bankAccount?.currency.asCurrencyCode().orEmpty(),
            projectName = row.projectName.ifBlank { projectName.orEmpty() },
            exceptions = exceptions.orEmpty().map { it.toDomain() },
            fraudAlerts = fraudAlerts.orEmpty().map { it.toDomain() },
            fxVariances = fxVariances.orEmpty().map { it.toDomain() },
            transactions = transactions.orEmpty().map { it.toDomain() },
            ledgerEntries = ledgerEntries.orEmpty().map { it.toDomain() },
            permissions = if (permissions == null || permissions is JsonNull) {
                null
            } else {
                permissions.asStringList().mapNotNull(PortalPermission::from).toSet()
            },
        )
    }
}

@Serializable
internal data class PreviewExceptionDto(
    @SerialName("title") val title: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("debit") val debit: JsonElement? = null,
    @SerialName("credit") val credit: JsonElement? = null,
    @SerialName("currency") val currency: JsonElement? = null,
) {
    fun toDomain() = PreviewException(
        title = title.orEmpty(),
        status = ExceptionStatus.from(status),
        debit = debit.asDouble() ?: 0.0,
        credit = credit.asDouble() ?: 0.0,
        currency = currency.asCurrencyCode(),
    )
}

@Serializable
internal data class PreviewFraudAlertDto(
    @SerialName("title") val title: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("description") val description: String? = null,
) {
    fun toDomain() = PreviewFraudAlert(
        title = title.orEmpty(),
        status = FraudStatus.from(status) ?: FraudStatus.Active,
        description = description.orEmpty(),
    )
}

@Serializable
internal data class PreviewFxDto(
    @SerialName("currency") val currency: JsonElement? = null,
    @SerialName("invoice_currency") val invoiceCurrency: JsonElement? = null,
    @SerialName("foreign_amount") val foreignAmount: JsonElement? = null,
    @SerialName("gbp_paid") val paidAmount: JsonElement? = null,
    @SerialName("variance") val variance: JsonElement? = null,
    @SerialName("budget_rate") val budgetRate: JsonElement? = null,
    @SerialName("bank_rate") val bankRate: JsonElement? = null,
) {
    fun toDomain() = PreviewFx(
        currency = currency.asCurrencyCode() ?: invoiceCurrency.asCurrencyCode().orEmpty(),
        foreignAmount = foreignAmount.asDouble() ?: 0.0,
        paidAmount = paidAmount.asDouble() ?: 0.0,
        variance = variance.asDouble() ?: 0.0,
        budgetRate = budgetRate.asDouble() ?: 0.0,
        bankRate = bankRate.asDouble() ?: 0.0,
    )
}

@Serializable
internal data class ImportResultDto(
    @SerialName("imported") val imported: JsonElement? = null,
    @SerialName("matched") val matched: JsonElement? = null,
    @SerialName("suggested") val suggested: JsonElement? = null,
    @SerialName("unmatched") val unmatched: JsonElement? = null,
    @SerialName("fraud") val fraud: JsonElement? = null,
) {
    fun toDomain() = ImportResult(
        imported = imported.asInt() ?: 0,
        matched = matched.asInt() ?: 0,
        suggested = suggested.asInt() ?: 0,
        unmatched = unmatched.asInt() ?: 0,
        fraud = fraud.asInt() ?: 0,
    )
}

/**
 * The rules, in the two halves the screen saves separately.
 *
 * A fraud check is a boolean when it has no threshold and an object when it
 * does, so the whole map is read as raw elements and shaped here.
 */
@Serializable
internal data class RulesSettingsDto(
    @SerialName("auto_match_rules") val autoMatch: Map<String, JsonElement>? = null,
    @SerialName("fraud_detections") val fraud: Map<String, JsonElement>? = null,
) {
    fun toDomain(): RulesSettings {
        val match = RulesSettings.DEFAULT_AUTO_MATCH.toMutableMap()
        autoMatch.orEmpty().forEach { (key, value) -> match[key] = value.asBoolean() }

        val checks = RulesSettings.DEFAULT_FRAUD.toMutableMap()
        fraud.orEmpty().forEach { (key, value) ->
            val detection = FraudDetection.entries.firstOrNull { it.key == key }
            checks[key] = when (value) {
                is JsonObject -> FraudRule(
                    enabled = value["enabled"].asBoolean(),
                    // Kept null for a check with no threshold, even when the
                    // server sends one: an amount on a boolean rule is stored
                    // in a shape the engine does not read.
                    amount = detection?.defaultAmount?.let { fallback -> value["amount"].asDouble() ?: fallback },
                )

                else -> FraudRule(enabled = value.asBoolean(), amount = detection?.defaultAmount)
            }
        }
        return RulesSettings(autoMatch = match, fraud = checks)
    }
}

/** Every account-hub project-settings slice reads wrapped under `value`. */
@Serializable
internal data class ValueDto<T>(@SerialName("value") val value: T? = null)

/**
 * The project's currencies, in either of the slice's two shapes.
 *
 * The current one is `{currencies: [{code, exr}], default}`; older productions
 * still hold a bare array of codes, which has no rates and no default. Decoding
 * only the object shape fails the whole read on those, and every balance then
 * reads as unconvertible. With no default set the web falls back to GBP.
 */
internal fun JsonElement?.toProjectRates(): ProjectRates {
    val root = this as? JsonObject ?: return ProjectRates()
    val rows = root["currencies"] as? JsonArray
    return ProjectRates(
        defaultCode = root["default"].asCurrencyCode() ?: "GBP",
        rates = rows.orEmpty().mapNotNull { row ->
            val entry = row as? JsonObject ?: return@mapNotNull null
            val code = entry["code"].asCurrencyCode() ?: return@mapNotNull null
            // Rate against the project default. Named `exr` on the wire.
            val rate = entry["exr"].asDouble()?.takeIf { it > 0 } ?: return@mapNotNull null
            code to rate
        }.toMap(),
    )
}
