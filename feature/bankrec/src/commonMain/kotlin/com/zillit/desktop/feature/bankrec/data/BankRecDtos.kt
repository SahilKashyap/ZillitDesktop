package com.zillit.desktop.feature.bankrec.data

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
import com.zillit.desktop.feature.bankrec.domain.FraudStatus
import com.zillit.desktop.feature.bankrec.domain.FraudType
import com.zillit.desktop.feature.bankrec.domain.FxDetail
import com.zillit.desktop.feature.bankrec.domain.FxStatus
import com.zillit.desktop.feature.bankrec.domain.FxVariance
import com.zillit.desktop.feature.bankrec.domain.LedgerEntry
import com.zillit.desktop.feature.bankrec.domain.LedgerEntryKind
import com.zillit.desktop.feature.bankrec.domain.PeriodStatus
import com.zillit.desktop.feature.bankrec.domain.PortalLink
import com.zillit.desktop.feature.bankrec.domain.PortalNotify
import com.zillit.desktop.feature.bankrec.domain.PortalOrgType
import com.zillit.desktop.feature.bankrec.domain.PortalPermission
import com.zillit.desktop.feature.bankrec.domain.PortalStatus
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
 * A list, or the JSON text of one.
 *
 * `matched_invoice_ids`, `tr_ids`, `permissions` and `signals` all arrive as
 * either, because the service stores them as text and parses inconsistently on
 * the way out. Reading only the array shape leaves a matched line looking
 * unmatched.
 */
internal fun JsonElement?.asStringList(): List<String> = when {
    this == null || this is JsonNull -> emptyList()
    this is JsonArray -> map { it.asText() }.filter { it.isNotBlank() }
    this is JsonPrimitive && isString -> runCatching {
        (lenient.parseToJsonElement(content) as? JsonArray)?.map { it.asText() }?.filter { it.isNotBlank() }
    }.getOrNull().orEmpty()

    else -> emptyList()
}

@Serializable
internal data class PeriodDto(
    @SerialName("id") val id: String? = null,
    @SerialName("period") val period: JsonElement? = null,
    @SerialName("bank_account_id") val bankAccountId: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("total_txns") val totalTxns: JsonElement? = null,
    @SerialName("matched_count") val matchedCount: JsonElement? = null,
    @SerialName("suggested_count") val suggestedCount: JsonElement? = null,
    @SerialName("unmatched_count") val unmatchedCount: JsonElement? = null,
    @SerialName("fraud_count") val fraudCount: JsonElement? = null,
    @SerialName("closing_bank") val closingBank: JsonElement? = null,
    @SerialName("closing_zillit") val closingZillit: JsonElement? = null,
    @SerialName("difference") val difference: JsonElement? = null,
    @SerialName("signed_by") val signedBy: String? = null,
    @SerialName("signed_at") val signedAt: JsonElement? = null,
    @SerialName("created_at") val createdAt: JsonElement? = null,
    @SerialName("note") val note: String? = null,
) {
    fun toDomain() = BankPeriod(
        id = id.orEmpty(),
        periodMillis = period.asLong(),
        bankAccountId = bankAccountId.orEmpty(),
        status = PeriodStatus.from(status),
        totalTxns = totalTxns.asInt() ?: 0,
        matchedCount = matchedCount.asInt() ?: 0,
        suggestedCount = suggestedCount.asInt() ?: 0,
        unmatchedCount = unmatchedCount.asInt() ?: 0,
        fraudCount = fraudCount.asInt() ?: 0,
        closingBank = closingBank.asDouble() ?: 0.0,
        closingZillit = closingZillit.asDouble() ?: 0.0,
        difference = difference.asDouble() ?: 0.0,
        signedBy = signedBy.orEmpty(),
        signedAtMillis = signedAt.asLong(),
        createdAtMillis = createdAt.asLong(),
        note = note.orEmpty(),
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
    @SerialName("currency") val currency: String? = null,
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
        // A foreign amount is what makes a line an FX payment. Judging by
        // "currency is not the project's" reads every line on a euro account
        // as a foreign payment, which is the whole account.
        val foreign = foreignAmount.asDouble()
        return BankTransaction(
            id = id.orEmpty(),
            periodId = periodId.orEmpty(),
            transactionDateMillis = transactionDate.asLong(),
            description = description.orEmpty(),
            vendorName = vendorName?.takeIf { it.isNotBlank() } ?: description.orEmpty(),
            reference = trReference?.takeIf { it.isNotBlank() } ?: reference.orEmpty(),
            paymentMethod = paymentMethod.orEmpty(),
            debit = debit.asDouble() ?: 0.0,
            credit = credit.asDouble() ?: 0.0,
            currency = currency?.takeIf { it.isNotBlank() },
            status = TxnStatus.from(status),
            fraudType = FraudType.from(fraudType),
            fraudStatus = FraudStatus.from(fraudStatus),
            fraudScore = fraudScore.asInt(),
            matchConfidence = matchConfidence.asInt(),
            matchedInvoiceIds = matchedInvoiceIds.asStringList(),
            fx = foreign?.takeIf { it != 0.0 }?.let {
                FxDetail(
                    foreignAmount = it,
                    currency = currency.orEmpty(),
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
    @SerialName("gross_amount") val grossAmount: JsonElement? = null,
    @SerialName("debit") val debit: JsonElement? = null,
    @SerialName("credit") val credit: JsonElement? = null,
    @SerialName("currency") val currency: String? = null,
    @SerialName("invoice_date") val invoiceDate: JsonElement? = null,
    @SerialName("date") val date: JsonElement? = null,
    @SerialName("tr_ids") val trIds: JsonElement? = null,
    @SerialName("ledger_description") val ledgerDescription: JsonElement? = null,
) {
    fun toDomain(): LedgerEntry {
        val kind = LedgerEntryKind.from(entityType)
        val isInvoice = kind == LedgerEntryKind.Invoice
        return LedgerEntry(
            // The ledger row's id when it has one, which is not the record's.
            id = ledgerId?.takeIf { it.isNotBlank() } ?: id.orEmpty(),
            entityId = id.orEmpty(),
            kind = kind,
            title = when {
                isInvoice -> vendorName?.takeIf { it.isNotBlank() }
                    ?: supplierName?.takeIf { it.isNotBlank() }
                    ?: title.orEmpty()

                else -> title.orEmpty()
            },
            reference = if (isInvoice) {
                invoiceNumber.orEmpty()
            } else {
                ledgerDescription.asStringList().joinToString(" · ")
            },
            amount = if (isInvoice) {
                grossAmount.asDouble()?.let { -it }
            } else {
                debit.asDouble()?.takeIf { it != 0.0 }?.let { -it } ?: credit.asDouble()
            },
            currency = currency?.takeIf { it.isNotBlank() },
            dateMillis = (if (isInvoice) invoiceDate else date).asLong() ?: date.asLong(),
            transactionIds = trIds.asStringList(),
        )
    }
}

@Serializable
internal data class WorkspaceDto(
    @SerialName("transactions") val transactions: List<TransactionDto>? = null,
    @SerialName("invoices") val invoices: List<LedgerEntryDto>? = null,
)

@Serializable
internal data class BankAccountDto(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("account_holder_name") val holderName: String? = null,
    @SerialName("account_number") val accountNumber: String? = null,
    @SerialName("sort_code") val sortCode: String? = null,
    @SerialName("currency") val currency: CurrencyDto? = null,
) {
    fun toDomain() = BankAccountRef(
        id = id.orEmpty(),
        name = name?.takeIf { it.isNotBlank() } ?: holderName.orEmpty(),
        currencyCode = currency?.code.orEmpty(),
        accountNumber = accountNumber.orEmpty(),
        sortCode = sortCode.orEmpty(),
    )
}

/** A bank's currency is a nested object, not a code. */
@Serializable
internal data class CurrencyDto(@SerialName("code") val code: String? = null)

@Serializable
internal data class ExceptionDto(
    @SerialName("id") val id: String? = null,
    @SerialName("period_id") val periodId: String? = null,
    @SerialName("exception_type") val type: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("notes") val notes: String? = null,
    @SerialName("transaction") val transaction: TransactionDto? = null,
) {
    fun toDomain() = BankException(
        id = id.orEmpty(),
        periodId = periodId.orEmpty(),
        type = ExceptionType.from(type),
        status = ExceptionStatus.from(status),
        title = title.orEmpty(),
        notes = notes.orEmpty(),
        transaction = transaction?.toDomain(),
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
    @SerialName("vendor") val vendor: FraudVendorDto? = null,
    @SerialName("transaction") val transaction: TransactionDto? = null,
) {
    fun toDomain() = FraudAlert(
        id = id.orEmpty(),
        periodId = periodId.orEmpty(),
        alertType = FraudType.from(alertType),
        status = FraudStatus.from(status) ?: FraudStatus.Active,
        title = title.orEmpty(),
        description = description.orEmpty(),
        riskScore = riskScore.asInt() ?: 0,
        createdAtMillis = createdAt.asLong(),
        signals = signals.asStringList(),
        vendorName = vendor?.name.orEmpty(),
        vendorSortCode = vendor?.sortCode.orEmpty(),
        vendorAccountNumber = vendor?.accountNumber.orEmpty(),
        transaction = transaction?.toDomain(),
    )
}

@Serializable
internal data class FraudVendorDto(
    @SerialName("name") val name: String? = null,
    @SerialName("sort_code") val sortCode: String? = null,
    @SerialName("account_number") val accountNumber: String? = null,
)

@Serializable
internal data class FraudAuditDto(
    @SerialName("id") val id: String? = null,
    @SerialName("action") val action: String? = null,
    @SerialName("detail") val detail: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("user_name") val userName: String? = null,
    @SerialName("created_at") val createdAt: JsonElement? = null,
    @SerialName("alert_id") val alertId: String? = null,
) {
    fun toDomain() = FraudAuditEntry(
        id = id.orEmpty(),
        action = action.orEmpty(),
        detail = detail?.takeIf { it.isNotBlank() } ?: description.orEmpty(),
        userName = userName.orEmpty(),
        atMillis = createdAt.asLong(),
        alertId = alertId.orEmpty(),
    )
}

@Serializable
internal data class FxVarianceDto(
    @SerialName("id") val id: String? = null,
    @SerialName("period_id") val periodId: String? = null,
    @SerialName("invoice_currency") val invoiceCurrency: String? = null,
    @SerialName("foreign_amount") val foreignAmount: JsonElement? = null,
    @SerialName("budget_gbp") val budgetAmount: JsonElement? = null,
    @SerialName("gbp_paid") val paidAmount: JsonElement? = null,
    @SerialName("variance") val variance: JsonElement? = null,
    @SerialName("budget_rate") val budgetRate: JsonElement? = null,
    @SerialName("bank_rate") val bankRate: JsonElement? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("created_at") val createdAt: JsonElement? = null,
    @SerialName("transaction") val transaction: TransactionDto? = null,
) {
    fun toDomain() = FxVariance(
        id = id.orEmpty(),
        periodId = periodId.orEmpty(),
        invoiceCurrency = invoiceCurrency.orEmpty(),
        foreignAmount = foreignAmount.asDouble() ?: 0.0,
        // `budget_gbp` and `gbp_paid` are the service's own names and are
        // *not* sterling — they are the project's default currency, whatever
        // that is. Renamed here so nothing downstream believes the field name.
        budgetAmount = budgetAmount.asDouble() ?: 0.0,
        paidAmount = paidAmount.asDouble() ?: 0.0,
        variance = variance.asDouble() ?: 0.0,
        budgetRate = budgetRate.asDouble() ?: 0.0,
        bankRate = bankRate.asDouble() ?: 0.0,
        status = FxStatus.from(status),
        createdAtMillis = createdAt.asLong(),
        vendorName = transaction?.vendorName.orEmpty(),
        reference = transaction?.reference.orEmpty(),
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
    @SerialName("permissions") val permissions: JsonElement? = null,
    @SerialName("notify_on_view") val notifyOnView: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("expires_at") val expiresAt: JsonElement? = null,
    @SerialName("created_at") val createdAt: JsonElement? = null,
    @SerialName("last_viewed_at") val lastViewedAt: JsonElement? = null,
    @SerialName("view_count") val viewCount: JsonElement? = null,
) {
    fun toDomain() = PortalLink(
        id = id.orEmpty(),
        token = token.orEmpty(),
        recipientName = recipientName.orEmpty(),
        recipientEmail = recipientEmail.orEmpty(),
        orgType = PortalOrgType.from(orgType),
        bankAccountId = bankAccountId.orEmpty(),
        periodId = periodId.orEmpty(),
        permissions = permissions.asStringList().mapNotNull(PortalPermission::from).toSet(),
        notifyOnView = PortalNotify.from(notifyOnView),
        status = PortalStatus.from(status),
        expiresAtMillis = expiresAt.asLong(),
        createdAtMillis = createdAt.asLong(),
        lastViewedAtMillis = lastViewedAt.asLong(),
        viewCount = viewCount.asInt() ?: 0,
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
                    amount = detection?.defaultAmount?.let { _ -> value["amount"].asDouble() },
                )

                else -> FraudRule(enabled = value.asBoolean(), amount = detection?.defaultAmount)
            }
        }
        return RulesSettings(autoMatch = match, fraud = checks)
    }
}

/** True, "true", or 1 — this service writes booleans all three ways. */
internal fun JsonElement?.asBoolean(): Boolean = when {
    this == null || this is JsonNull -> false
    this is JsonPrimitive -> content.equals("true", ignoreCase = true) || content == "1"
    else -> false
}

/** Every account-hub project-settings slice reads wrapped under `value`. */
@Serializable
internal data class ValueDto<T>(@SerialName("value") val value: T? = null)

@Serializable
internal data class ProjectCurrenciesDto(
    @SerialName("currencies") val currencies: List<ProjectCurrencyDto>? = null,
    @SerialName("default") val default: String? = null,
) {
    fun toDomain() = ProjectRates(
        defaultCode = default?.takeIf { it.isNotBlank() }?.uppercase() ?: "GBP",
        rates = currencies.orEmpty().mapNotNull { row ->
            val code = row.code?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val rate = row.rate.asDouble()?.takeIf { it > 0 } ?: return@mapNotNull null
            code to rate
        }.toMap(),
    )
}

@Serializable
internal data class ProjectCurrencyDto(
    @SerialName("code") val code: String? = null,
    /** Rate against the project default. Named `exr` on the wire. */
    @SerialName("exr") val rate: JsonElement? = null,
)
