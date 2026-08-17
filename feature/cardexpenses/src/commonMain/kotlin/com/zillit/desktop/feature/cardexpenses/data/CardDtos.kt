package com.zillit.desktop.feature.cardexpenses.data

import com.zillit.desktop.core.common.toAmount
import com.zillit.desktop.core.common.toAmountOrNull
import com.zillit.desktop.core.common.toEpochMillisOrNull
import com.zillit.desktop.feature.cardexpenses.domain.AlertSeverity
import com.zillit.desktop.feature.cardexpenses.domain.AnalyticsSlice
import com.zillit.desktop.feature.cardexpenses.domain.BulkItem
import com.zillit.desktop.feature.cardexpenses.domain.BulkOutcome
import com.zillit.desktop.feature.cardexpenses.domain.StatementRow
import com.zillit.desktop.feature.cardexpenses.domain.CardAlert
import com.zillit.desktop.feature.cardexpenses.domain.CardAnalytics
import com.zillit.desktop.feature.cardexpenses.domain.CardHistoryEntry
import com.zillit.desktop.feature.cardexpenses.domain.CardMetadata
import com.zillit.desktop.feature.cardexpenses.domain.CardOverview
import com.zillit.desktop.feature.cardexpenses.domain.CardProvider
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardSettings
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.CardTopUp
import com.zillit.desktop.feature.cardexpenses.domain.CardTransaction
import com.zillit.desktop.feature.cardexpenses.domain.CardType
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.MatchStatus
import com.zillit.desktop.feature.cardexpenses.domain.StatementImport
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The card service's wire types.
 *
 * Read as tolerantly as the cash module's, and for the same reason: amounts
 * and timestamps arrive as numbers or strings depending on the column behind
 * them. See `CashDtos` for the full account.
 */
@Serializable
internal data class CardDto(
    @SerialName("id") val id: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("holder_name") val holderName: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("company_id") val companyId: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("card_type") val cardType: String? = null,
    @SerialName("last_four") val lastFour: String? = null,
    @SerialName("card_issuer") val issuer: String? = null,
    @SerialName("card_provider_id") val providerId: String? = null,
    @SerialName("currency") val currency: String? = null,
    @SerialName("card_limit") val cardLimit: String? = null,
    @SerialName("monthly_limit") val monthlyLimit: String? = null,
    @SerialName("balance") val balance: String? = null,
    @SerialName("current_balance") val currentBalance: String? = null,
    @SerialName("receipts_commit") val receiptsCommit: String? = null,
    @SerialName("bs_control_code") val bsControlCode: String? = null,
    @SerialName("requested_by") val requestedBy: String? = null,
    @SerialName("rejected_by") val rejectedBy: String? = null,
    @SerialName("rejection_reason") val rejectionReason: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toDomain(): ExpenseCard? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        return ExpenseCard(
            id = identifier,
            holderId = userId.orEmpty(),
            holderName = holderName?.takeIf { it.isNotBlank() }
                ?: fullName?.takeIf { it.isNotBlank() }
                ?: name.orEmpty(),
            departmentId = departmentId,
            companyId = companyId,
            status = CardStatus.from(status),
            type = CardType.from(cardType),
            lastFour = lastFour,
            issuer = issuer,
            providerId = providerId,
            currency = currency,
            limit = cardLimit.toAmount(),
            monthlyLimit = monthlyLimit.toAmountOrNull(),
            // `balance` then `current_balance`, nullish rather than falsy, so a
            // genuine zero balance is a zero and not a missing figure — the
            // one-card-per-user rule turns on that distinction.
            balance = balance.toAmountOrNull() ?: currentBalance.toAmountOrNull(),
            receiptsCommit = receiptsCommit.toAmountOrNull(),
            bsControlCode = bsControlCode,
            requestedBy = requestedBy,
            rejectedBy = rejectedBy,
            rejectionReason = rejectionReason,
            createdAt = createdAt.toEpochMillisOrNull(),
        )
    }
}

@Serializable
internal data class TransactionDto(
    @SerialName("id") val id: String? = null,
    @SerialName("card_id") val cardId: String? = null,
    @SerialName("card_last_four") val cardLastFour: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("holder_name") val holderName: String? = null,
    @SerialName("merchant") val merchant: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("amount") val amount: String? = null,
    @SerialName("currency") val currency: String? = null,
    @SerialName("date") val date: String? = null,
    @SerialName("transaction_date") val transactionDate: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("nominal_code") val nominalCode: String? = null,
    @SerialName("code_description") val codeDescription: String? = null,
    @SerialName("episode") val episode: String? = null,
    @SerialName("vat_amount") val vatAmount: String? = null,
    @SerialName("match_status") val matchStatus: String? = null,
    @SerialName("receipt_id") val receiptId: String? = null,
) {
    fun toDomain(): CardTransaction? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        val workflow = CardWorkflowStatus.from(status)
        return CardTransaction(
            id = identifier,
            cardId = cardId,
            cardLastFour = cardLastFour,
            holderId = userId,
            holderName = holderName.orEmpty(),
            merchant = merchant.orEmpty(),
            description = description,
            amount = amount.toAmount(),
            currency = currency,
            date = (date ?: transactionDate).toEpochMillisOrNull(),
            status = workflow,
            nominalCode = nominalCode,
            codeDescription = codeDescription,
            episode = episode,
            vatAmount = vatAmount.toAmount(),
            matchStatus = MatchStatus.from(matchStatus),
            receiptId = receiptId,
            personal = workflow == CardWorkflowStatus.Personal,
        )
    }
}

@Serializable
internal data class ReceiptDto(
    @SerialName("id") val id: String? = null,
    @SerialName("card_id") val cardId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("holder_name") val holderName: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("merchant") val merchant: String? = null,
    @SerialName("amount") val amount: String? = null,
    @SerialName("currency") val currency: String? = null,
    @SerialName("date") val date: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("match_status") val matchStatus: String? = null,
    @SerialName("match_score") val matchScore: Int? = null,
    @SerialName("transaction_id") val transactionId: String? = null,
    @SerialName("transaction_merchant") val transactionMerchant: String? = null,
    @SerialName("transaction_amount") val transactionAmount: String? = null,
    @SerialName("transaction_date") val transactionDate: String? = null,
    @SerialName("transaction_card_last4") val transactionCardLastFour: String? = null,
    @SerialName("nominal_code") val nominalCode: String? = null,
    @SerialName("code_description") val codeDescription: String? = null,
    @SerialName("episode") val episode: String? = null,
    /** An attachment model, a bare key, or a JSON string holding one. */
    @SerialName("receipt_attachment") val attachment: JsonElement? = null,
    @SerialName("is_urgent") val urgent: Boolean? = null,
    @SerialName("duplicate_score") val duplicateScore: Int? = null,
    @SerialName("duplicate_dismissed") val duplicateDismissed: Boolean? = null,
    @SerialName("personal_score") val personalScore: Int? = null,
    @SerialName("personal_dismissed") val personalDismissed: Boolean? = null,
) {
    fun toDomain(): CardReceipt? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        return CardReceipt(
            id = identifier,
            cardId = cardId,
            holderId = userId,
            holderName = holderName?.takeIf { it.isNotBlank() } ?: fullName.orEmpty(),
            description = description.orEmpty(),
            merchant = merchant,
            amount = amount.toAmount(),
            currency = currency,
            date = (date ?: createdAt).toEpochMillisOrNull(),
            status = CardWorkflowStatus.from(status),
            matchStatus = MatchStatus.from(matchStatus),
            transactionId = transactionId,
            transactionMerchant = transactionMerchant,
            transactionAmount = transactionAmount.toAmountOrNull(),
            transactionDate = transactionDate.toEpochMillisOrNull(),
            transactionCardLastFour = transactionCardLastFour,
            nominalCode = nominalCode,
            codeDescription = codeDescription,
            episode = episode,
            attachmentKey = attachment.readAttachmentKey(),
            urgent = urgent == true,
            matchScore = matchScore,
            duplicateScore = duplicateScore,
            duplicateDismissed = duplicateDismissed == true,
            personalScore = personalScore,
            personalDismissed = personalDismissed == true,
            createdAt = createdAt.toEpochMillisOrNull(),
        )
    }
}

@Serializable
internal data class CardTopUpDto(
    @SerialName("id") val id: String? = null,
    @SerialName("card_id") val cardId: String? = null,
    @SerialName("card_last_four") val cardLastFour: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("holder_name") val holderName: String? = null,
    @SerialName("amount") val amount: String? = null,
    @SerialName("currency") val currency: String? = null,
    @SerialName("method") val method: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toDomain(): CardTopUp? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        return CardTopUp(
            id = identifier,
            cardId = cardId,
            cardLastFour = cardLastFour,
            holderId = userId,
            holderName = holderName.orEmpty(),
            amount = amount.toAmount(),
            currency = currency,
            method = method,
            status = status?.lowercase().orEmpty(),
            createdAt = createdAt.toEpochMillisOrNull(),
        )
    }
}

@Serializable
internal data class AlertDto(
    @SerialName("id") val id: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("severity") val severity: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("savings") val savings: String? = null,
    @SerialName("timestamp") val timestamp: String? = null,
) {
    fun toDomain(): CardAlert? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        return CardAlert(
            id = identifier,
            title = title.orEmpty(),
            description = description,
            severity = AlertSeverity.from(severity),
            status = status?.lowercase().orEmpty(),
            type = type,
            savings = savings.toAmountOrNull(),
            at = timestamp.toEpochMillisOrNull(),
        )
    }
}

@Serializable
internal data class ImportDto(
    @SerialName("id") val id: String? = null,
    @SerialName("filename") val filename: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("row_count") val rowCount: Int? = null,
    @SerialName("matched_count") val matchedCount: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toDomain(): StatementImport? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        return StatementImport(
            id = identifier,
            filename = filename,
            status = status?.lowercase().orEmpty(),
            rowCount = rowCount ?: 0,
            matchedCount = matchedCount ?: 0,
            importedAt = createdAt.toEpochMillisOrNull(),
        )
    }
}

@Serializable
internal data class StatementRowDto(
    @SerialName("id") val id: String? = null,
    @SerialName("merchant") val merchant: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("amount") val amount: String? = null,
    @SerialName("currency") val currency: String? = null,
    @SerialName("date") val date: String? = null,
    @SerialName("transaction_date") val transactionDate: String? = null,
    @SerialName("card_last_four") val cardLastFour: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("holder_name") val holderName: String? = null,
    @SerialName("status") val status: String? = null,
) {
    fun toDomain(): StatementRow? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        return StatementRow(
            id = identifier,
            merchant = merchant.orEmpty(),
            description = description,
            amount = amount.toAmount(),
            currency = currency,
            date = (date ?: transactionDate).toEpochMillisOrNull(),
            cardLastFour = cardLastFour,
            holderId = userId?.takeIf { it.isNotBlank() },
            holderName = holderName,
            // An unstated status is a fresh row: that is what an import
            // produces, and treating it as processed would hide the work.
            status = status?.lowercase()?.takeIf { it.isNotBlank() } ?: "new",
        )
    }
}

@Serializable
internal data class BulkItemDto(
    @SerialName("id") val id: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("holder_name") val holderName: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("merchant") val merchant: String? = null,
    @SerialName("amount") val amount: String? = null,
    @SerialName("currency") val currency: String? = null,
    @SerialName("date") val date: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("card_last_four") val cardLastFour: String? = null,
    @SerialName("nominal_code") val nominalCode: String? = null,
    @SerialName("code_description") val codeDescription: String? = null,
    @SerialName("episode") val episode: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("assigned_to") val assignedTo: String? = null,
    @SerialName("is_urgent") val urgent: Boolean? = null,
) {
    fun toDomain(): BulkItem? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        return BulkItem(
            id = identifier,
            holderId = userId,
            holderName = holderName?.takeIf { it.isNotBlank() } ?: fullName.orEmpty(),
            description = description.orEmpty(),
            merchant = merchant,
            amount = amount.toAmount(),
            currency = currency,
            date = (date ?: createdAt).toEpochMillisOrNull(),
            cardLastFour = cardLastFour,
            nominalCode = nominalCode,
            codeDescription = codeDescription,
            episode = episode,
            status = CardWorkflowStatus.from(status),
            assignedTo = assignedTo,
            urgent = urgent == true,
        )
    }
}

@Serializable
internal data class BulkOutcomeDto(
    @SerialName("succeeded") val succeeded: Int? = null,
    @SerialName("failed") val failed: Int? = null,
) {
    fun toDomain() = BulkOutcome(succeeded = succeeded ?: 0, failed = failed ?: 0)
}

@Serializable
internal data class CardMetadataDto(
    @SerialName("is_approver") val isApprover: Boolean? = null,
    @SerialName("is_coordinator") val isCoordinator: Boolean? = null,
    @SerialName("is_senior") val isSenior: Boolean? = null,
    @SerialName("coding_required") val codingRequired: Boolean? = null,
    @SerialName("can_override") val canOverride: Boolean? = null,
    @SerialName("posting_limit") val postingLimit: String? = null,
) {
    fun toDomain() = CardMetadata(
        isApprover = isApprover == true,
        isCoordinator = isCoordinator == true,
        isSenior = isSenior == true,
        codingRequired = codingRequired == true,
        canOverride = canOverride == true,
        postingLimit = postingLimit.toAmountOrNull(),
    )
}

@Serializable
internal data class OverviewDto(
    @SerialName("counts") val counts: CountsDto? = null,
    @SerialName("totals") val totals: TotalsDto? = null,
    @SerialName("cards") val cards: List<CardDto>? = null,
    @SerialName("pendingTopups") val pendingTopUps: List<CardTopUpDto>? = null,
) {
    fun toDomain() = CardOverview(
        activeCards = counts?.activeCards ?: 0,
        requestedCards = counts?.requestedCards ?: 0,
        inbox = counts?.inbox ?: 0,
        pendingCoding = counts?.pendingCoding ?: 0,
        inApproval = counts?.inApproval ?: 0,
        approved = counts?.approved ?: 0,
        posted = counts?.posted ?: 0,
        transactionCount = counts?.transactionCount ?: 0,
        totalSpend = totals?.totalSpend.toAmount(),
        postedTotal = totals?.postedTotal.toAmount(),
        vatEstimate = totals?.vatEstimate.toAmount(),
        cards = cards.orEmpty().mapNotNull { it.toDomain() },
        pendingTopUps = pendingTopUps.orEmpty().mapNotNull { it.toDomain() },
    )
}

@Serializable
internal data class CountsDto(
    @SerialName("activeCards") val activeCards: Int? = null,
    @SerialName("requestedCards") val requestedCards: Int? = null,
    @SerialName("inbox") val inbox: Int? = null,
    @SerialName("pendingCoding") val pendingCoding: Int? = null,
    @SerialName("inApproval") val inApproval: Int? = null,
    @SerialName("approved") val approved: Int? = null,
    @SerialName("posted") val posted: Int? = null,
    @SerialName("transactionCount") val transactionCount: Int? = null,
)

@Serializable
internal data class TotalsDto(
    @SerialName("totalSpend") val totalSpend: String? = null,
    @SerialName("postedTotal") val postedTotal: String? = null,
    @SerialName("vatEstimate") val vatEstimate: String? = null,
)

@Serializable
internal data class AnalyticsDto(
    @SerialName("total_spend") val totalSpend: String? = null,
    @SerialName("transaction_count") val transactionCount: Int? = null,
    @SerialName("average_transaction") val averageTransaction: String? = null,
    @SerialName("by_category") val byCategory: List<SliceDto>? = null,
    @SerialName("by_holder") val byHolder: List<SliceDto>? = null,
    @SerialName("by_month") val byMonth: List<SliceDto>? = null,
) {
    fun toDomain() = CardAnalytics(
        totalSpend = totalSpend.toAmount(),
        transactionCount = transactionCount ?: 0,
        averageTransaction = averageTransaction.toAmount(),
        byCategory = byCategory.orEmpty().map { it.toDomain() },
        byHolder = byHolder.orEmpty().map { it.toDomain() },
        byMonth = byMonth.orEmpty().map { it.toDomain() },
    )
}

@Serializable
internal data class SliceDto(
    @SerialName("label") val label: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("amount") val amount: String? = null,
    @SerialName("count") val count: Int? = null,
) {
    fun toDomain() = AnalyticsSlice(
        label = label?.takeIf { it.isNotBlank() } ?: name.orEmpty(),
        amount = amount.toAmount(),
        count = count ?: 0,
    )
}

@Serializable
internal data class CardHistoryDto(
    @SerialName("action") val action: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("note") val note: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toDomain() = CardHistoryEntry(
        action = action ?: status.orEmpty(),
        userId = userId,
        note = note,
        at = createdAt.toEpochMillisOrNull(),
    )
}

@Serializable
internal data class CardSettingsDto(
    @SerialName("coding_required") val codingRequired: Boolean? = null,
    @SerialName("require_senior_sign_off") val requireSeniorSignOff: Boolean? = null,
    @SerialName("auto_match_enabled") val autoMatchEnabled: Boolean? = null,
    @SerialName("auto_match_threshold") val autoMatchThreshold: Int? = null,
    @SerialName("duplicate_detection") val duplicateDetection: Boolean? = null,
    @SerialName("personal_spend_detection") val personalSpendDetection: Boolean? = null,
    @SerialName("default_card_limit") val defaultCardLimit: String? = null,
    /** A JSON array, or a string holding one. See [readProviders]. */
    @SerialName("card_providers") val cardProviders: JsonElement? = null,
) {
    fun toDomain() = CardSettings(
        codingRequired = codingRequired == true,
        requireSeniorSignOff = requireSeniorSignOff == true,
        // Auto-matching defaults ON when the server says nothing: it is the
        // behaviour every production has, and defaulting it off would silently
        // stop matching on a production whose settings row predates the flag.
        autoMatchEnabled = autoMatchEnabled != false,
        autoMatchThreshold = autoMatchThreshold ?: DEFAULT_THRESHOLD,
        duplicateDetection = duplicateDetection != false,
        personalSpendDetection = personalSpendDetection != false,
        defaultCardLimit = defaultCardLimit.toAmountOrNull(),
        providers = cardProviders.readProviders(),
    )
}

@Serializable
internal data class ProviderDto(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
)

// -- tolerant JSON reading ---------------------------------------------------

private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * The storage key for a receipt document.
 *
 * The column holds an attachment model, a bare key string, or a JSON string of
 * either, depending on how old the row is and which endpoint served it.
 */
internal fun JsonElement?.readAttachmentKey(): String? = when {
    this == null -> null
    this is JsonPrimitive && isString -> content.takeIf { it.isNotBlank() && it != "null" }
        ?.let { raw ->
            // A JSON string holding an object: read the key out of it.
            runCatching { lenientJson.parseToJsonElement(raw) }.getOrNull()?.attachmentKey() ?: raw
        }

    this is JsonObject -> attachmentKey()
    else -> null
}

private fun JsonElement.attachmentKey(): String? {
    val obj = this as? JsonObject ?: return null
    return listOf("key", "url", "path", "file_name")
        .firstNotNullOfOrNull { field ->
            (obj[field] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
        }
}

internal fun JsonElement?.readProviders(): List<CardProvider> =
    readList(ProviderDto.serializer()).mapNotNull { dto ->
        val id = dto.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        CardProvider(id = id, name = dto.name?.takeIf { it.isNotBlank() } ?: id)
    }

private fun <T> JsonElement?.readList(serializer: KSerializer<T>): List<T> {
    val element = when {
        this == null -> return emptyList()
        this is JsonPrimitive && isString ->
            runCatching { lenientJson.parseToJsonElement(content) }.getOrNull() ?: return emptyList()

        else -> this
    }
    return runCatching {
        lenientJson.decodeFromJsonElement(ListSerializer(serializer), element)
    }.getOrElse { emptyList() }
}

private const val DEFAULT_THRESHOLD = 85
