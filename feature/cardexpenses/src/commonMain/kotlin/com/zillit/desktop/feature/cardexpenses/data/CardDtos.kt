package com.zillit.desktop.feature.cardexpenses.data

import com.zillit.desktop.core.common.CurrencyCodeSerializer
import com.zillit.desktop.core.common.toAmount
import com.zillit.desktop.core.common.toAmountOrNull
import com.zillit.desktop.core.common.toEpochMillisOrNull
import com.zillit.desktop.feature.cardexpenses.domain.AlertSeverity
import com.zillit.desktop.feature.cardexpenses.domain.AnalyticsSlice
import com.zillit.desktop.feature.cardexpenses.domain.BulkItem
import com.zillit.desktop.feature.cardexpenses.domain.BulkOutcome
import com.zillit.desktop.feature.cardexpenses.domain.CardAlert
import com.zillit.desktop.feature.cardexpenses.domain.CardAnalytics
import com.zillit.desktop.feature.cardexpenses.domain.CardHistoryEntry
import com.zillit.desktop.feature.cardexpenses.domain.CardMetadata
import com.zillit.desktop.feature.cardexpenses.domain.CardOverview
import com.zillit.desktop.feature.cardexpenses.domain.CardProvider
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.ApprovalOverrides
import com.zillit.desktop.feature.cardexpenses.domain.CardSettings
import com.zillit.desktop.feature.cardexpenses.domain.CardTeamMember
import com.zillit.desktop.feature.cardexpenses.domain.DepartmentCoordinator
import com.zillit.desktop.feature.cardexpenses.domain.CardStatus
import com.zillit.desktop.feature.cardexpenses.domain.CardTopUp
import com.zillit.desktop.feature.cardexpenses.domain.CardTransaction
import com.zillit.desktop.feature.cardexpenses.domain.CardType
import com.zillit.desktop.feature.cardexpenses.domain.CardWorkflowStatus
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.InboxSection
import com.zillit.desktop.feature.cardexpenses.domain.MatchStatus
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptProcessing
import com.zillit.desktop.feature.cardexpenses.domain.StatementImport
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt
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
    /**
     * `/overview` spells it `last4`; `/cards` spells it `last_four`.
     *
     * The overview projects its card rows to a thinner field set with its own
     * names — the web reads `c.last4` there and `card.last_four` everywhere
     * else. Reading only one of them printed "Card bb0cb7" (the id) in place
     * of a card number on the dashboard.
     */
    @SerialName("last4") val lastFourShort: String? = null,
    @SerialName("card_issuer") val issuer: String? = null,
    @SerialName("card_provider_id") val providerId: String? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @SerialName("card_limit") val cardLimit: String? = null,
    @SerialName("monthly_limit") val monthlyLimit: String? = null,
    @SerialName("balance") val balance: String? = null,
    @SerialName("current_balance") val currentBalance: String? = null,
    @SerialName("receipts_commit") val receiptsCommit: String? = null,
    @SerialName("bs_control_code") val bsControlCode: String? = null,
    @SerialName("proposed_limit") val proposedLimit: String? = null,
    @SerialName("justification") val justification: String? = null,
    @SerialName("requested_by") val requestedBy: String? = null,
    @SerialName("rejected_by") val rejectedBy: String? = null,
    @SerialName("rejection_reason") val rejectionReason: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    /** The chain's sign-offs: an array, or a JSON string of one. */
    @SerialName("approvals") val approvals: JsonElement? = null,
    @SerialName("digital_card_number") val digitalCardNumber: String? = null,
    @SerialName("physical_card_number") val physicalCardNumber: String? = null,
    /** On `/overview` rows only; see `ExpenseCard.spent`. */
    @SerialName("spent") val spent: String? = null,
    @SerialName("rejected_at") val rejectedAt: String? = null,
    /** The issuing bank, joined: `{id, name, …}`. Read for its name only. */
    @SerialName("bank_account") val bankAccount: JsonElement? = null,
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
            lastFour = lastFour?.takeIf { it.isNotBlank() } ?: lastFourShort,
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
            proposedLimit = proposedLimit.toAmountOrNull(),
            justification = justification,
            requestedBy = requestedBy,
            rejectedBy = rejectedBy,
            rejectionReason = rejectionReason,
            createdAt = createdAt.toEpochMillisOrNull(),
            approvals = approvals.readApprovals(),
            digitalCardNumber = digitalCardNumber?.takeIf { it.isNotBlank() },
            physicalCardNumber = physicalCardNumber?.takeIf { it.isNotBlank() },
            serverSpent = spent.toAmountOrNull(),
            rejectedAt = rejectedAt.toEpochMillisOrNull(),
            bankName = ((bankAccount as? JsonObject)?.get("name") as? JsonPrimitive)
                ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() },
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
    @Serializable(with = CurrencyCodeSerializer::class)
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
    // The camelCase and raw spellings the web table falls back to
    // (`TransactionTable.jsx:124,190,230,260,291`): some routes answer them.
    @SerialName("cardLastFour") val cardLastFourCamel: String? = null,
    @SerialName("receiptId") val receiptIdCamel: String? = null,
    @SerialName("userId") val userIdCamel: String? = null,
    @SerialName("nominalCode") val nominalCodeCamel: String? = null,
    @SerialName("code") val code: String? = null,
    @SerialName("merchant_raw") val merchantRaw: String? = null,
) {
    fun toDomain(): CardTransaction? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        val workflow = CardWorkflowStatus.from(status)
        return CardTransaction(
            id = identifier,
            cardId = cardId,
            // The importer's `0000` names no card; the web prints a dash for it.
            cardLastFour = (cardLastFour ?: cardLastFourCamel)?.takeIf { it.isNotBlank() && it != NO_CARD },
            holderId = (userId ?: userIdCamel)?.takeIf { it.isNotBlank() },
            holderName = holderName.orEmpty(),
            merchant = listOf(merchantRaw, merchant, description).firstOrNull { !it.isNullOrBlank() }.orEmpty(),
            description = description,
            amount = amount.toAmount(),
            currency = currency,
            date = (transactionDate ?: date).toEpochMillisOrNull(),
            status = workflow,
            nominalCode = listOf(code, nominalCode, nominalCodeCamel).firstOrNull { !it.isNullOrBlank() },
            codeDescription = codeDescription,
            episode = episode,
            vatAmount = vatAmount.toAmount(),
            matchStatus = MatchStatus.from(matchStatus),
            receiptId = (receiptId ?: receiptIdCamel)?.takeIf { it.isNotBlank() },
            personal = workflow == CardWorkflowStatus.Personal,
        )
    }
}

/** The importer's placeholder for a line with no card on it. */
private const val NO_CARD = "0000"

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
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @SerialName("date") val date: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("match_status") val matchStatus: String? = null,
    /**
     * Confidence, as a **fraction** — `0.63`, not `63`.
     *
     * Typed `Int?` here once, which threw on the first row carrying a real
     * score and took the **whole list** down with it: kotlinx stops at the
     * first bad element, so one receipt blanked the entire Receipt Inbox
     * behind "The server sent something unexpected". Seen live 2026-09-12.
     * The web multiplies by 100 at every reading and thresholds at 0.8/0.5.
     */
    @SerialName("match_score") val matchScore: String? = null,
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
    @SerialName("duplicate_score") val duplicateScore: String? = null,
    @SerialName("duplicate_dismissed") val duplicateDismissed: Boolean? = null,
    @SerialName("personal_score") val personalScore: String? = null,
    @SerialName("personal_dismissed") val personalDismissed: Boolean? = null,
    // -- the process editor's inputs, carried by `/receipts/:id/detail` -------
    /** Coded and server-owned lines together: an array, or a JSON string of one. */
    @SerialName("line_items") val lineItems: JsonElement? = null,
    /** Rule hits: strings, or `{flag,title,description}` objects, or a JSON string of either. */
    @SerialName("processing_flags") val processingFlags: JsonElement? = null,
    @SerialName("card_limit") val cardLimit: String? = null,
    @SerialName("card_balance") val cardBalance: String? = null,
    @SerialName("request_top_up") val requestTopUp: JsonElement? = null,
    @SerialName("effective_date") val effectiveDate: String? = null,
    @SerialName("assigned_to") val assignedTo: String? = null,
    @SerialName("escalation_reason") val escalationReason: String? = null,
    @SerialName("escalated_by") val escalatedBy: String? = null,
    @SerialName("escalated_at") val escalatedAt: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("card_last_four") val cardLastFour: String? = null,
    @SerialName("approvals") val approvals: JsonElement? = null,
    @SerialName("category") val category: String? = null,
    @SerialName("rejection_reason") val rejectionReason: String? = null,
    @SerialName("rejected_by") val rejectedBy: String? = null,
    @SerialName("rejected_at") val rejectedAt: String? = null,
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
            matchScore = matchScore.asPercent(),
            duplicateScore = duplicateScore.asPercent(),
            duplicateDismissed = duplicateDismissed == true,
            personalScore = personalScore.asPercent(),
            personalDismissed = personalDismissed == true,
            createdAt = createdAt.toEpochMillisOrNull(),
            processing = processing(),
            assignedTo = assignedTo?.takeIf { it.isNotBlank() },
            departmentId = departmentId?.takeIf { it.isNotBlank() },
            cardLastFour = cardLastFour?.takeIf { it.isNotBlank() },
            approvals = approvals.readApprovals(),
            // Off the raw values: MatchStatus folds `suggested_match`,
            // `duplicate` and `personal` into "matched", and the sections
            // need them apart.
            inboxSection = InboxSection.of(matchStatus, status, transactionId),
            category = category?.takeIf { it.isNotBlank() },
            rejectionReason = rejectionReason?.takeIf { it.isNotBlank() },
            rejectedBy = rejectedBy?.takeIf { it.isNotBlank() },
            rejectedAt = rejectedAt.toEpochMillisOrNull(),
            attachmentName = attachment.readAttachmentName(),
        )
    }

    /** Only a detail read carries `line_items`; a queue row reads as not loaded. */
    private fun processing(): ReceiptProcessing {
        val read = lineItems.readLineItems()
        return ReceiptProcessing(
            loaded = lineItems != null,
            lines = read.coded,
            fixedLines = read.fixed,
            flags = processingFlags.readFlags(),
            rules = processingFlags.readFlagRules(),
            taxLine = read.taxLine,
            escalatedBy = escalatedBy?.takeIf { it.isNotBlank() },
            escalatedAt = escalatedAt.toEpochMillisOrNull(),
            cardLimit = cardLimit.toAmountOrNull(),
            cardBalance = cardBalance.toAmountOrNull(),
            requestTopUp = requestTopUp.truthy(),
            effectiveDate = effectiveDate.toEpochMillisOrNull(),
            escalationReason = escalationReason?.takeIf { it.isNotBlank() },
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
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @SerialName("method") val method: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("card_limit") val cardLimit: String? = null,
    @SerialName("card_balance") val cardBalance: String? = null,
    @SerialName("upload_type") val uploadType: String? = null,
    @SerialName("note") val note: String? = null,
    @SerialName("receipt_merchant") val receiptMerchant: String? = null,
    @SerialName("receipt_amount") val receiptAmount: String? = null,
    @SerialName("bs_control_code") val bsControlCode: String? = null,
    @SerialName("issued_amount") val issuedAmount: String? = null,
    @SerialName("entity_id") val entityId: String? = null,
    @SerialName("entity_type") val entityType: String? = null,
    /** The row's audit trail: a JSON-stringified array, or an array. */
    @SerialName("history") val history: JsonElement? = null,
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
            cardLimit = cardLimit.toAmountOrNull(),
            cardBalance = cardBalance.toAmountOrNull(),
            uploadType = uploadType?.takeIf { it.isNotBlank() },
            note = note?.takeIf { it.isNotBlank() },
            receiptMerchant = receiptMerchant?.takeIf { it.isNotBlank() },
            receiptAmount = receiptAmount.toAmountOrNull(),
            bsControlCode = bsControlCode?.takeIf { it.isNotBlank() },
            issuedAmount = issuedAmount.toAmountOrNull(),
            entityId = entityId?.takeIf { it.isNotBlank() },
            entityType = entityType?.takeIf { it.isNotBlank() },
            trail = history.readTopUpTrail(),
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
    @SerialName("resolution") val resolution: String? = null,
    /** `[{merchant, ref, holder, amount}]`, camelCase on the wire (`SmartAlertsPage.jsx:222`). */
    @SerialName("relatedTxns") val relatedTxns: JsonElement? = null,
) {
    fun toDomain(): CardAlert? {
        val identifier = id?.takeIf { it.isNotBlank() } ?: return null
        return CardAlert(
            id = identifier,
            title = title.orEmpty(),
            description = description,
            severity = AlertSeverity.from(severity),
            // A row with no status is a live one: the web defaults it to
            // `active` before it reads anything off it (SmartAlertsPage.jsx:112).
            status = status?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: CardAlert.ACTIVE,
            type = type,
            savings = savings.toAmountOrNull(),
            at = timestamp.toEpochMillisOrNull(),
            resolution = resolution?.takeIf { it.isNotBlank() },
            relatedTxns = relatedTxns.readAlertTxns(),
        )
    }
}

@Serializable
internal data class ImportDto(
    @SerialName("id") val id: String? = null,
    /** The label is `name || file_name || id` on the web (`AllTransactionsPage.jsx:386`). */
    @SerialName("name") val name: String? = null,
    @SerialName("file_name") val fileName: String? = null,
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
            filename = listOf(name, fileName, filename).firstOrNull { !it.isNullOrBlank() },
            status = status?.lowercase().orEmpty(),
            rowCount = rowCount ?: 0,
            matchedCount = matchedCount ?: 0,
            importedAt = createdAt.toEpochMillisOrNull(),
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
    @Serializable(with = CurrencyCodeSerializer::class)
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
    /** Chain rows: `{scope, department_id, tiers}`, `tiers` an array or a JSON string of one. */
    @SerialName("approval_tier_configs") val tierConfigs: JsonElement? = null,
    @SerialName("card_override") val cardOverride: JsonElement? = null,
    @SerialName("receipt_override") val receiptOverride: JsonElement? = null,
    /** The crew's copy of the request ceiling (`CardExpensesModule.jsx:137`). */
    @SerialName("request_cap") val requestCap: JsonElement? = null,
) {
    fun toDomain() = CardMetadata(
        isApprover = isApprover == true,
        isCoordinator = isCoordinator == true,
        isSenior = isSenior == true,
        codingRequired = codingRequired == true,
        canOverride = canOverride == true,
        postingLimit = postingLimit.toAmountOrNull(),
        tierConfigs = tierConfigs.readTierConfigs(),
        cardOverride = cardOverride.truthy(),
        receiptOverride = receiptOverride.truthy(),
        requestCap = requestCap?.takeUnless { it is JsonNull }?.readRequestCap(),
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

/**
 * `GET /analytics/overview`.
 *
 * The service answers `{summary: {…}, by_department: […], by_holder: […]}`
 * (`AnalyticsPage.jsx:99-125`); the desktop read the figures off the top level
 * under names the service does not use, so every tile read zero. Both shapes
 * are read, the summary first.
 */
@Serializable
internal data class AnalyticsDto(
    @SerialName("summary") val summary: AnalyticsSummaryDto? = null,
    @SerialName("total_spend") val totalSpend: String? = null,
    @SerialName("transaction_count") val transactionCount: String? = null,
    @SerialName("average_transaction") val averageTransaction: String? = null,
    @SerialName("by_category") val byCategory: List<SliceDto>? = null,
    @SerialName("by_holder") val byHolder: List<SliceDto>? = null,
    @SerialName("by_month") val byMonth: List<SliceDto>? = null,
    @SerialName("by_department") val byDepartment: List<SliceDto>? = null,
) {
    fun toDomain() = CardAnalytics(
        totalSpend = (summary?.totalSpend ?: totalSpend).toAmount(),
        transactionCount = (summary?.transactionCount ?: transactionCount).toAmount().toInt(),
        averageTransaction = (summary?.averageTransaction ?: averageTransaction).toAmount(),
        byCategory = byCategory.orEmpty().map { it.toDomain() },
        byHolder = byHolder.orEmpty().map { it.toDomain() },
        byMonth = byMonth.orEmpty().map { it.toDomain() },
        byDepartment = byDepartment.orEmpty().map { it.toDomain() },
        activeCards = summary?.activeCards.toAmount().toInt(),
        postedTotal = summary?.postedTotal.toAmount(),
        totalCards = summary?.totalCards.toAmount().toInt(),
        topDepartmentId = summary?.topDepartmentId?.takeIf { it.isNotBlank() },
        avgImportToCoded = summary?.avgImportToCoded.toAmount(),
        avgCodedToApproved = summary?.avgCodedToApproved.toAmount(),
        avgApprovedToPosted = summary?.avgApprovedToPosted.toAmount(),
        receiptsMissingPct = summary?.receiptsMissingPct.toAmount(),
        autoReconciledPct = summary?.autoReconciledPct.toAmount(),
    )
}

@Serializable
internal data class AnalyticsSummaryDto(
    @SerialName("total_spend") val totalSpend: String? = null,
    @SerialName("transaction_count") val transactionCount: String? = null,
    @SerialName("avg_transaction") val averageTransaction: String? = null,
    @SerialName("active_cards") val activeCards: String? = null,
    @SerialName("posted_total") val postedTotal: String? = null,
    @SerialName("total_cards") val totalCards: String? = null,
    @SerialName("top_department_id") val topDepartmentId: String? = null,
    @SerialName("avg_import_to_coded") val avgImportToCoded: String? = null,
    @SerialName("avg_coded_to_approved") val avgCodedToApproved: String? = null,
    @SerialName("avg_approved_to_posted") val avgApprovedToPosted: String? = null,
    @SerialName("receipts_missing_pct") val receiptsMissingPct: String? = null,
    @SerialName("auto_reconciled_pct") val autoReconciledPct: String? = null,
)

@Serializable
internal data class SliceDto(
    @SerialName("label") val label: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("amount") val amount: String? = null,
    @SerialName("total") val total: String? = null,
    @SerialName("count") val count: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("card_last_four") val cardLastFour: String? = null,
    @SerialName("card_limit") val cardLimit: String? = null,
    @SerialName("balance") val balance: String? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
) {
    fun toDomain() = AnalyticsSlice(
        label = label?.takeIf { it.isNotBlank() } ?: name.orEmpty(),
        amount = (amount ?: total).toAmount(),
        count = count.toAmount().toInt(),
        userId = userId?.takeIf { it.isNotBlank() },
        departmentId = departmentId?.takeIf { it.isNotBlank() },
        cardLastFour = cardLastFour?.takeIf { it.isNotBlank() },
        cardLimit = cardLimit.toAmountOrNull(),
        balance = balance.toAmountOrNull(),
        currency = currency?.takeIf { it.isNotBlank() },
    )
}

@Serializable
internal data class CardHistoryDto(
    @SerialName("action") val action: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("note") val note: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    /** The top-up trail's spelling (`HistoryPanel.jsx:19-23`). */
    @SerialName("action_by") val actionBy: String? = null,
    @SerialName("action_at") val actionAt: String? = null,
) {
    fun toDomain() = CardHistoryEntry(
        action = action ?: status.orEmpty(),
        userId = userId ?: actionBy,
        note = note,
        at = (createdAt ?: actionAt).toEpochMillisOrNull(),
    )
}

@Serializable
internal data class CardSettingsDto(
    /** Each of these five is a JSON array or object, or a string holding one. */
    @SerialName("team_members") val teamMembers: JsonElement? = null,
    @SerialName("department_coordinators") val coordinators: JsonElement? = null,
    @SerialName("approval_override") val approvalOverride: JsonElement? = null,
    @SerialName("card_providers") val cardProviders: JsonElement? = null,
    /**
     * An **object** on the wire — `{enabled, basis, max_amount,
     * salary_multiplier}` — typed `String?` here once, which failed the whole
     * `/settings` decode on any production the web had saved a cap on. Read
     * tolerantly; see [readRequestCap].
     */
    @SerialName("request_cap") val requestCap: JsonElement? = null,
    @SerialName("assignment_rules") val assignmentRules: JsonElement? = null,
) {
    fun toDomain() = CardSettings(
        teamMembers = teamMembers.readList(TeamMemberDto.serializer()).map { it.toDomain() },
        coordinators = coordinators.readList(CoordinatorDto.serializer()).map { it.toDomain() },
        overrides = approvalOverride.readObject(OverridesDto.serializer())?.toDomain() ?: ApprovalOverrides(),
        providers = cardProviders.readProviders(),
        requestCap = requestCap.readRequestCap(),
        assignmentRules = assignmentRules.readAssignmentRules(),
    )
}

@Serializable
internal data class TeamMemberDto(
    @SerialName("user_id") val userId: String? = null,
    /**
     * Null and zero are different answers.
     *
     * Null is an unlimited poster and zero is one who may post nothing, so
     * this stays a nullable string all the way to the domain rather than
     * being defaulted anywhere on the way.
     */
    @SerialName("posting_limit") val postingLimit: String? = null,
    @SerialName("can_override") val canOverride: Boolean? = null,
    @SerialName("is_senior") val isSenior: Boolean? = null,
) {
    fun toDomain() = CardTeamMember(
        userId = userId.orEmpty(),
        postingLimit = postingLimit.toAmountOrNull(),
        canOverride = canOverride == true,
        isSenior = isSenior == true,
    )
}

@Serializable
internal data class CoordinatorDto(
    @SerialName("department_id") val departmentId: String? = null,
    @SerialName("user_ids") val userIds: List<String>? = null,
    @SerialName("coding_required") val codingRequired: Boolean? = null,
) {
    fun toDomain() = DepartmentCoordinator(
        departmentId = departmentId.orEmpty(),
        userIds = userIds.orEmpty(),
        codingRequired = codingRequired == true,
    )
}

@Serializable
internal data class OverridesDto(
    @SerialName("override_card_req") val overrideCardRequests: Boolean? = null,
    @SerialName("override_receipt") val overrideReceipts: Boolean? = null,
    @SerialName("require_coord_code") val requireCoordinatorCoding: Boolean? = null,
    @SerialName("require_senior_sign_off") val requireSeniorSignOff: Boolean? = null,
) {
    fun toDomain() = ApprovalOverrides(
        overrideCardRequests = overrideCardRequests == true,
        overrideReceipts = overrideReceipts == true,
        requireCoordinatorCoding = requireCoordinatorCoding == true,
        requireSeniorSignOff = requireSeniorSignOff == true,
    )
}

@Serializable
internal data class ProviderDto(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("bank_id") val bankId: String? = null,
    @SerialName("company_id") val companyId: String? = null,
    @SerialName("custodian_account") val custodianAccount: String? = null,
    @SerialName("float_min") val floatMin: String? = null,
    @SerialName("float_max") val floatMax: String? = null,
)

// -- tolerant JSON reading ---------------------------------------------------

private const val PERCENT = 100.0

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
    // `media` first: it is the web's AttachmentModel key (`attachmentUpload.js`),
    // and every receipt uploaded from the web carries it and nothing else.
    return listOf("media", "key", "url", "path", "file_name")
        .firstNotNullOfOrNull { field ->
            (obj[field] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
        }
}

internal fun JsonElement?.readProviders(): List<CardProvider> =
    readList(ProviderDto.serializer()).mapNotNull { dto ->
        val id = dto.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        CardProvider(
            id = id,
            name = dto.name?.takeIf { it.isNotBlank() } ?: id,
            bankId = dto.bankId.orEmpty(),
            companyId = dto.companyId.orEmpty(),
            custodianAccount = dto.custodianAccount.orEmpty(),
            floatMin = dto.floatMin.orEmpty(),
            floatMax = dto.floatMax.orEmpty(),
        )
    }

internal fun <T> JsonElement?.readList(serializer: KSerializer<T>): List<T> {
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

/**
 * A confidence score as a whole percentage, from whatever the wire sent.
 *
 * The engine reports fractions (`0.63`), and every web reading multiplies by
 * a hundred. A value above one is taken as already being a percentage rather
 * than as 6,300% — defensive, because a field this small is not worth a second
 * outage if a service ever changes its mind. Zero and below read as no score
 * at all, matching the web's `> 0` guard on all three.
 */
internal fun String?.asPercent(): Int? {
    val value = this?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull() ?: return null
    if (value <= 0) return null
    val percent = if (value <= 1.0) value * PERCENT else value
    return percent.roundToInt().coerceAtMost(PERCENT.toInt())
}

/**
 * The object sibling of [readList].
 *
 * Same tolerance for the same reason: the settings columns hold a JSON object
 * on a production saved by the current web build and a JSON *string* of one on
 * a production saved by an older release.
 */
internal fun <T> JsonElement?.readObject(serializer: KSerializer<T>): T? {
    val element = when {
        this == null -> return null
        this is JsonPrimitive && isString ->
            runCatching { lenientJson.parseToJsonElement(content) }.getOrNull() ?: return null

        else -> this
    }
    return runCatching { lenientJson.decodeFromJsonElement(serializer, element) }.getOrNull()
}
