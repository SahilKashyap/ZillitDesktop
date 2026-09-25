package com.zillit.desktop.feature.cardexpenses.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.domain.BulkAction
import com.zillit.desktop.feature.cardexpenses.domain.BulkCoding
import com.zillit.desktop.feature.cardexpenses.domain.BulkItem
import com.zillit.desktop.feature.cardexpenses.domain.BulkOutcome
import com.zillit.desktop.feature.cardexpenses.domain.CardAction
import com.zillit.desktop.feature.cardexpenses.domain.CardActivation
import com.zillit.desktop.feature.cardexpenses.domain.CardServerNote
import com.zillit.desktop.feature.cardexpenses.domain.CardExportRow
import com.zillit.desktop.feature.cardexpenses.domain.ExportFormat
import com.zillit.desktop.feature.cardexpenses.domain.FundRequest
import com.zillit.desktop.feature.cardexpenses.domain.FundRequestDraft
import com.zillit.desktop.feature.cardexpenses.domain.QueryThread
import com.zillit.desktop.feature.cardexpenses.domain.ProcessRefs
import com.zillit.desktop.feature.cardexpenses.domain.ProcessSubmission
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptAssignment
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptCoding
import com.zillit.desktop.feature.cardexpenses.domain.InboxWrite
import com.zillit.desktop.feature.cardexpenses.domain.StoredStatement
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptEdit
import com.zillit.desktop.feature.cardexpenses.domain.CardDates
import com.zillit.desktop.feature.cardexpenses.domain.TierVisibility
import com.zillit.desktop.feature.cardexpenses.domain.TransactionFilters
import com.zillit.desktop.feature.cardexpenses.domain.CardAlert
import com.zillit.desktop.feature.cardexpenses.domain.CardAnalytics
import com.zillit.desktop.feature.cardexpenses.domain.CardDetailsEdit
import com.zillit.desktop.feature.cardexpenses.domain.CardHistoryEntry
import com.zillit.desktop.feature.cardexpenses.domain.CardMetadata
import com.zillit.desktop.feature.cardexpenses.domain.CardOverview
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardRepository
import com.zillit.desktop.feature.cardexpenses.domain.CardSettings
import com.zillit.desktop.feature.cardexpenses.domain.CardTopUp
import com.zillit.desktop.feature.cardexpenses.domain.CardTransaction
import com.zillit.desktop.feature.cardexpenses.domain.DraftCardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.NewCardRequest
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptScope
import com.zillit.desktop.feature.cardexpenses.domain.SettingsSection
import com.zillit.desktop.feature.cardexpenses.domain.StatementImport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * A POST that answers a file rather than an envelope — the two exports.
 *
 * A host seam, as Bank Reconciliation's `BankRecBinaryPost` is: the shared
 * client decodes JSON envelopes only, and the host's raw client signs the
 * request the same way and hands back the bytes (or the server's refusal).
 */
fun interface CardBinaryPost {
    suspend fun post(url: String, body: JsonObject): ZillitResult<ByteArray>
}

/**
 * Every `/api/v2/card-expenses` route, on the card service's own host.
 *
 * Header module is `ProjectUser` throughout, for the reason given in the cash
 * repository: these calls are scoped to one person on one production and the
 * service refuses a lighter header rather than falling back.
 */
@Suppress("TooManyFunctions") // Mirrors the server's operation surface; see the interface.
class CardRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /**
     * The host's byte POST, for the two exports. The shared client speaks JSON
     * envelopes only; null leaves the exports refusing with a reason rather
     * than saving an error page as a PDF.
     */
    private val binaryPost: CardBinaryPost? = null,
) : CardRepository {

    private val base = "${config.baseUrl(ZillitService.CardExpenses)}/api/v2/card-expenses"

    override suspend fun metadata(): ZillitResult<CardMetadata> =
        get("$base/metadata", CardMetadataDto.serializer()).map { it.toDomain() }

    override suspend fun overview(): ZillitResult<CardOverview> =
        get("$base/overview", OverviewDto.serializer()).map { it.toDomain() }

    override suspend fun analytics(fromIso: String?, toIso: String?): ZillitResult<CardAnalytics> =
        get(
            url = "$base/analytics/overview",
            serializer = AnalyticsDto.serializer(),
            query = buildMap {
                fromIso?.let { put("start", it) }
                toIso?.let { put("end", it) }
            },
        ).map { it.toDomain() }

    // -- cards -------------------------------------------------------------

    override suspend fun cards(mineOnly: Boolean): ZillitResult<List<ExpenseCard>> =
        get(
            url = "$base/cards",
            serializer = ListSerializer(CardDto.serializer()),
            query = if (mineOnly) mapOf("my" to "true") else emptyMap(),
        ).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun cardHistory(cardId: String): ZillitResult<List<CardHistoryEntry>> =
        get("$base/cards/$cardId/history", ListSerializer(CardHistoryDto.serializer()))
            .map { rows -> rows.map { it.toDomain() } }

    override suspend fun cardReceipts(cardId: String): ZillitResult<List<CardReceipt>> =
        receiptList("$base/cards/$cardId/receipts")

    /**
     * Raises a card request.
     *
     * The limit goes as **`proposed_limit`**, not `card_limit`: a request
     * states what the holder believes they need, and `card_limit` is the
     * authorised figure an accountant writes when they approve. The create
     * route's allowlist drops unknown keys without complaint, so sending the
     * wrong one produced a card request carrying no limit at all — and
     * likewise `justification` rather than `reason`. `card_type` and `status`
     * are the server's to set and are not sent.
     */
    override suspend fun requestCard(request: NewCardRequest): ZillitResult<Unit> = post(
        "$base/cards",
        buildJsonObject {
            put("user_id", JsonPrimitive(request.holderId))
            put("proposed_limit", JsonPrimitive(request.proposedLimit))
            putIfPresent("currency", request.currency)
            putIfPresent("department_id", request.departmentId)
            putIfPresent("justification", request.justification)
            // HANDOFF card-company-pin: an absent company leaves the server to
            // derive one from the bank chain, so the key is omitted rather
            // than blanked.
            putIfPresent("company_id", request.companyId)
            putIfPresent("card_provider_id", request.providerId)
            putIfPresent("card_issuer", request.issuer)
            putIfPresent("bs_control_code", request.bsControlCode)
        },
    )

    /**
     * An accountant's full edit of a card request.
     *
     * Carries `status: "pending"` and a recomputed balance, both of which the
     * server acts on: the status is read as a resubmit and wipes the card's
     * collected approvals, and the balance moves with the change to the limit.
     * Correct on a card still in its chain; destructive on a live one, which
     * is why [updateBsControlCode] exists as the narrow alternative.
     *
     * `company_id` and `card_issuer` go as empty strings rather than being
     * omitted: an omitted key leaves the column alone, and clearing the pin is
     * something this form has to be able to do.
     */
    override suspend fun updateCardDetails(
        cardId: String,
        edit: CardDetailsEdit,
    ): ZillitResult<Unit> = patch(
        "$base/cards/$cardId",
        buildJsonObject {
            put("card_limit", JsonPrimitive(edit.limit))
            put("balance", JsonPrimitive(edit.balance))
            put("card_provider_id", JsonPrimitive(edit.providerId.orEmpty()))
            put("card_issuer", JsonPrimitive(edit.issuer.orEmpty()))
            put("company_id", JsonPrimitive(edit.companyId.orEmpty()))
            putIfPresent("currency", edit.currency)
            put("bs_control_code", JsonPrimitive(edit.bsControlCode.trim()))
            put("justification", JsonPrimitive(edit.justification.trim()))
            put("status", JsonPrimitive(PENDING))
        },
    )

    override suspend fun deleteCard(cardId: String): ZillitResult<Unit> =
        delete("$base/cards/$cardId")

    override suspend fun approveCard(cardId: String, step: TierVisibility, userId: String): ZillitResult<Unit> =
        post(
            "$base/cards/$cardId/approve",
            buildJsonObject {
                step.nextTier?.let { put("tier_number", JsonPrimitive(it)) }
                put("total_tiers", JsonPrimitive(step.totalTiers))
                put("user_id", JsonPrimitive(userId))
            },
        )

    override suspend fun rejectCard(cardId: String, reason: String, userId: String): ZillitResult<Unit> =
        post("$base/cards/$cardId/reject", actorBody(userId, reason))

    override suspend fun overrideCard(cardId: String, userId: String, reason: String): ZillitResult<Unit> =
        post("$base/cards/$cardId/override", actorBody(userId, reason))

    override suspend fun activateCard(cardId: String, activation: CardActivation): ZillitResult<Unit> =
        post("$base/cards/$cardId/activate", activation.body())

    override suspend fun suspendCard(cardId: String): ZillitResult<Unit> =
        post("$base/cards/$cardId/suspend", null)

    override suspend fun reactivateCard(cardId: String): ZillitResult<Unit> =
        post("$base/cards/$cardId/reactivate", null)

    /**
     * Attaches a physical card to a live digital one.
     *
     * Two details fail **silently** when wrong, which is why they are spelled
     * out here rather than left to a call site:
     *
     *  - the key is `card_type`, not `type` — there is no `type` column, and
     *    the update allowlist drops unknown keys without complaint;
     *  - the value must be lowercase, because the server compares
     *    `card_type === 'physical'`;
     *  - the number goes as `physical_card_number`, not activation's
     *    `full_card_number` — only the former runs the "already assigned to
     *    another card" uniqueness check, and the latter would land in no
     *    column at all.
     */
    override suspend fun assignPhysicalCard(cardId: String, cardNumber: String): ZillitResult<Unit> {
        val digits = cardNumber.filter(Char::isDigit)
        return patch(
            "$base/cards/$cardId",
            buildJsonObject {
                put("last4", JsonPrimitive(digits.takeLast(LAST_FOUR)))
                put("card_type", JsonPrimitive(PHYSICAL))
                put("physical_card_number", JsonPrimitive(digits))
            },
        )
    }

    /**
     * A minimal PATCH, and the minimalism is the point.
     *
     * The full details form sends `status: "pending"` — which the server reads
     * as a resubmit and answers by **wiping the card's collected approvals** —
     * plus a recomputed balance. Both are correct when reviewing a card that is
     * not yet approved, and both are destructive on one that is live or
     * mid-approval. A code correction must carry neither.
     */
    override suspend fun updateBsControlCode(cardId: String, code: String): ZillitResult<Unit> =
        patch(
            "$base/cards/$cardId",
            buildJsonObject { put("bs_control_code", JsonPrimitive(code.trim())) },
        )

    /** Every register and Card-tab write, answered with the server's message; see [CardActionCalls]. */
    override suspend fun cardAction(action: CardAction): ZillitResult<CardServerNote> = actionCalls.send(action)

    private val actionCalls = CardActionCalls(apiClient, config)

    // -- statement imports -------------------------------------------------

    override suspend fun imports(): ZillitResult<List<StatementImport>> =
        get("$base/imports", ListSerializer(ImportDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    // -- transactions ------------------------------------------------------

    override suspend fun transactions(filters: TransactionFilters): ZillitResult<List<CardTransaction>> =
        get("$base/transactions", ListSerializer(TransactionDto.serializer()), filters.query())
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun codeTransaction(
        transactionId: String,
        nominalCode: String,
        description: String?,
    ): ZillitResult<Unit> = patch(
        "$base/transactions/$transactionId",
        buildJsonObject {
            put("nominal_code", JsonPrimitive(nominalCode))
            putIfPresent("code_description", description)
        },
    )

    override suspend fun submitTransaction(transactionId: String): ZillitResult<Unit> =
        post("$base/transactions/$transactionId/submit", null)

    override suspend fun postTransaction(transactionId: String): ZillitResult<Unit> =
        post("$base/transactions/$transactionId/post", null)

    override suspend fun queryTransaction(transactionId: String, reason: String): ZillitResult<Unit> =
        post(
            "$base/transactions/$transactionId/query",
            buildJsonObject { put("reason", JsonPrimitive(reason)) },
        )

    override suspend fun rejectTransaction(transactionId: String, reason: String): ZillitResult<Unit> =
        post(
            "$base/transactions/$transactionId/reject",
            buildJsonObject { put("reason", JsonPrimitive(reason)) },
        )

    override suspend fun flagTransactionPersonal(transactionId: String): ZillitResult<Unit> =
        post("$base/transactions/$transactionId/flag-personal", null)

    /**
     * Removes a statement line.
     *
     * A matched receipt is **unlinked, not destroyed** — it returns to the
     * receipt inbox as unmatched — which is why every caller refreshes the
     * receipts alongside the transactions.
     */
    override suspend fun deleteTransaction(transactionId: String): ZillitResult<Unit> =
        delete("$base/transactions/$transactionId")

    /**
     * The bulk sibling of [deleteTransaction].
     *
     * Ids the server does not recognise are skipped rather than failing the
     * batch, so the returned counts describe what actually went — not what was
     * asked for.
     */
    override suspend fun bulkDeleteTransactions(
        transactionIds: List<String>,
    ): ZillitResult<BulkOutcome> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/transactions/bulk-delete",
        serializer = BulkOutcomeDto.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject { put("transactionIds", jsonIds(transactionIds)) },
    ).map { it.toDomain() }

    // -- the reconciliation surfaces (see CardInboxCalls) -------------------

    private val inbox = CardInboxCalls(apiClient, config)

    override suspend fun inboxReceiptDetail(receiptId: String) = inbox.receiptDetail(receiptId)

    override suspend fun inboxMatchCandidates(receiptId: String) = inbox.matchCandidates(receiptId)

    override suspend fun inboxWrite(write: InboxWrite) = inbox.write(write)

    override suspend fun bulkDeleteTransactionsCounted(transactionIds: List<String>) =
        inbox.bulkDelete(transactionIds)

    override suspend fun importStatementFile(attachment: StoredStatement, currency: String?) =
        inbox.importStatement(attachment, currency)

    override suspend fun submitToCrew(transactionIds: List<String>) = inbox.submitToCrew(transactionIds)

    // -- receipts ----------------------------------------------------------

    override suspend fun receipts(scope: ReceiptScope): ZillitResult<List<CardReceipt>> =
        receiptList("$base${scope.path}")

    override suspend fun matchCandidates(receiptId: String): ZillitResult<List<CardTransaction>> =
        get("$base/receipts/$receiptId/match-candidates", ListSerializer(TransactionDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun matchReceipt(receiptId: String, transactionId: String): ZillitResult<Unit> =
        post(
            "$base/receipts/$receiptId/manual-match",
            buildJsonObject { put("transactionId", JsonPrimitive(transactionId)) },
        )

    override suspend fun unmatchReceipt(receiptId: String): ZillitResult<Unit> =
        post("$base/receipts/$receiptId/unmatch", null)

    override suspend fun confirmReceiptMatch(receiptId: String): ZillitResult<Unit> =
        post("$base/receipts/$receiptId/confirm-match", null)

    /**
     * Uploads a batch of receipts against one card.
     *
     * The coding key is **`cost_code`**, not `nominal_code` — the create route
     * and the coding routes disagree about the name, and the create route
     * silently drops the one it does not know. `card_id` and `currency` are
     * the card's own: a receipt is committed against a card's limit, and the
     * server refuses a batch that does not say which.
     */
    override suspend fun submitReceipts(
        card: ExpenseCard?,
        receipts: List<DraftCardReceipt>,
    ): ZillitResult<Unit> = post(
        "$base/receipts/submit-batch",
        buildJsonObject {
            put(
                "receipts",
                buildJsonArray {
                    receipts.forEach { receipt ->
                        add(
                            buildJsonObject {
                                // The web's batch row, key for key (`UserReceiptsPage.jsx:354-368`):
                                // card and company explicit null when unknown, the three
                                // coding keys always present (blank when not given), and
                                // the date as the form's `YYYY-MM-DD` — not epoch millis.
                                put("card_id", card?.id?.let(::JsonPrimitive) ?: JsonNull)
                                putIfPresent("currency", card?.currency)
                                val company = card?.companyId?.takeIf { it.isNotBlank() }
                                put("company_id", company?.let(::JsonPrimitive) ?: JsonNull)
                                put("description", JsonPrimitive(receipt.description.trim()))
                                put("amount", JsonPrimitive(receipt.amountValue))
                                put("category", JsonPrimitive(receipt.category.wire))
                                receipt.date?.let { put("date", JsonPrimitive(CardDates.toIso(it))) }
                                put("is_urgent", JsonPrimitive(receipt.urgent))
                                put("request_top_up", JsonPrimitive(receipt.requestTopUp))
                                put("cost_code", JsonPrimitive(receipt.costCode.trim()))
                                put("episode", JsonPrimitive(receipt.episode.trim()))
                                put("coded_description", JsonPrimitive(receipt.codedDescription.trim()))
                                // The stored file as the web's AttachmentModel; a key on
                                // its own only where the host could say nothing more.
                                val file = receipt.attachment
                                if (file != null) {
                                    put("receipt_attachment", file.model())
                                } else {
                                    putIfPresent("receipt_attachment", receipt.attachmentKey)
                                }
                            },
                        )
                    }
                },
            )
        },
    )

    override suspend fun submitReceiptForApproval(
        receiptId: String,
        coding: ReceiptCoding?,
    ): ZillitResult<Unit> = post("$base/receipts/$receiptId/submit-for-approval", coding?.body())

    /**
     * Codes a receipt and clears its first approval in one step.
     *
     * Offered only to a coordinator who is also an approver: they would
     * otherwise code the receipt and then immediately approve their own
     * coding from the next screen.
     */
    override suspend fun approveAndSubmitReceipt(
        receiptId: String,
        coding: ReceiptCoding,
    ): ZillitResult<Unit> = post("$base/receipts/$receiptId/approve-and-submit", coding.body())

    /**
     * Saves coding without moving the receipt on.
     *
     * PATCH rather than the `submit-coding` route, which advances the
     * workflow: the coding queue's "Save draft" exists precisely so a
     * half-finished code can be put down and picked up again.
     */
    override suspend fun updateReceiptCoding(
        receiptId: String,
        coding: ReceiptCoding,
    ): ZillitResult<Unit> = patch("$base/receipts/$receiptId", coding.body())

    override suspend fun codeReceipt(
        receiptId: String,
        coding: ReceiptCoding,
    ): ZillitResult<Unit> = post("$base/receipts/$receiptId/submit-coding", coding.body())

    override suspend fun postReceipt(receiptId: String): ZillitResult<Unit> =
        post("$base/receipts/$receiptId/post", null)

    override suspend fun receiptDetail(receiptId: String): ZillitResult<CardReceipt> =
        when (val read = get("$base/receipts/$receiptId/detail", ReceiptDto.serializer())) {
            is ZillitResult.Failure -> read
            is ZillitResult.Success -> read.data.toDomain()?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Unknown("receipt detail carried no id"))
        }

    override suspend fun saveProcessReceipt(
        receiptId: String,
        submission: ProcessSubmission,
    ): ZillitResult<Unit> = post("$base/receipts/$receiptId/save-process", submission.body())

    /**
     * Assigning and reassigning share a body; the route is what tells the
     * server's history "Assigned" from "Reassigned from …" (ZL-20749).
     */
    override suspend fun assignReceipt(
        receiptId: String,
        assignment: ReceiptAssignment,
        reassign: Boolean,
    ): ZillitResult<Unit> = post(
        "$base/assignments/$receiptId/${if (reassign) "reassign" else "assign"}",
        buildJsonObject {
            put("assign_to", JsonPrimitive(assignment.assignTo))
            put("assigned_by", JsonPrimitive(assignment.assignedBy))
            put("reason", JsonPrimitive(assignment.reason))
        },
    )

    override suspend fun flagReceiptPersonal(receiptId: String): ZillitResult<Unit> =
        post("$base/receipts/$receiptId/flag-personal", null)

    override suspend fun dismissDuplicate(receiptId: String): ZillitResult<Unit> =
        post("$base/receipts/$receiptId/dismiss-duplicate", null)

    override suspend fun dismissPersonal(receiptId: String): ZillitResult<Unit> =
        post("$base/receipts/$receiptId/dismiss-personal", null)

    override suspend fun deleteReceipt(receiptId: String): ZillitResult<Unit> =
        delete("$base/receipts/$receiptId")

    override suspend fun receiptHistory(receiptId: String): ZillitResult<List<CardHistoryEntry>> =
        get("$base/receipts/$receiptId/history", ListSerializer(CardHistoryDto.serializer()))
            .map { rows -> rows.map { it.toDomain() } }

    // -- the cardholder's pages ----------------------------------------------

    override suspend fun cardsForApproval(): ZillitResult<List<ExpenseCard>> =
        get(
            url = "$base/cards",
            serializer = ListSerializer(CardDto.serializer()),
            query = mapOf("status" to PENDING, "for_approval" to "true"),
        ).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun updateReceipt(receiptId: String, edit: ReceiptEdit): ZillitResult<Unit> =
        patch("$base/receipts/$receiptId", edit.body())

    override suspend fun confirmReceipt(receiptId: String): ZillitResult<Unit> =
        post("$base/receipts/$receiptId/confirm", null)

    // -- bulk processing ---------------------------------------------------

    override suspend fun bulkProcessable(): ZillitResult<List<BulkItem>> =
        get("$base/bulk", ListSerializer(BulkItemDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun bulkProcess(
        receiptIds: List<String>,
        coding: BulkCoding,
    ): ZillitResult<BulkOutcome> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/bulk/process",
        serializer = BulkOutcomeDto.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put(
                "details",
                buildJsonObject {
                    // Explicit null means "keep each row's own coding" — the
                    // key is always present, so the server never has to tell
                    // "unset" from "absent". A blank string here instead would
                    // clear forty rows at once.
                    putOrNull("nominal_code", coding.nominalCode)
                    putOrNull("department_id", coding.departmentId)
                    putOrNull("episode", coding.episode)
                    // `null` or the type with its rate — never a bare string,
                    // which the server cannot apply.
                    put(
                        "tax",
                        coding.taxType?.takeIf { it.isNotBlank() }?.let { type ->
                            buildJsonObject {
                                put("tax_type", JsonPrimitive(type))
                                put("tax_rate", JsonPrimitive(coding.taxRate ?: 0.0))
                            }
                        } ?: JsonNull,
                    )
                    put("topup", JsonPrimitive(coding.topUp.wire))
                },
            )
            put(
                "items",
                buildJsonArray {
                    receiptIds.forEach { id ->
                        add(buildJsonObject { put("receipt_id", JsonPrimitive(id)) })
                    }
                },
            )
        },
    ).map { it.toDomain() }

    // -- approvals ---------------------------------------------------------

    override suspend fun approvalQueue(): ZillitResult<List<CardReceipt>> =
        receiptList("$base/approvals")

    override suspend fun approveReceipt(receiptId: String, tierNumber: Int, userId: String): ZillitResult<Unit> =
        post("$base/approvals/$receiptId/approve", approveBody(tierNumber, userId))

    override suspend fun rejectReceipt(receiptId: String, reason: String, userId: String): ZillitResult<Unit> =
        post("$base/approvals/$receiptId/reject", rejectBody(reason, userId))

    private val processCalls = CardProcessCalls(apiClient, config)

    override suspend fun processReferences(): ZillitResult<ProcessRefs> = processCalls.references()

    override suspend fun overrideReceipt(receiptId: String, userId: String, reason: String): ZillitResult<Unit> =
        post("$base/approvals/$receiptId/override", actorBody(userId, reason))

    override suspend fun bulkApproval(
        action: BulkAction,
        receiptIds: List<String>,
    ): ZillitResult<Unit> = post(
        "$base/approvals/bulk",
        buildJsonObject {
            put("action", JsonPrimitive(action.wire))
            put("receiptIds", buildJsonArray { receiptIds.forEach { add(JsonPrimitive(it)) } })
        },
    )

    // -- top-ups -----------------------------------------------------------

    /**
     * The funding queue — card top-ups only. `/topups` also carries cash float
     * top-ups, which live elsewhere; a row with no `entity_type` is a card's
     * (`TopUpToDoPage.jsx:141`).
     */
    override suspend fun topUps(): ZillitResult<List<CardTopUp>> =
        topUpList("$base/topups").map { rows -> rows.filter { (it.entityType ?: CARD_ENTITY) == CARD_ENTITY } }

    override suspend fun cardTopUps(cardId: String): ZillitResult<List<CardTopUp>> =
        topUpList("$base/cards/$cardId/top-ups")

    override suspend fun topUpHistory(topUpId: String): ZillitResult<List<CardHistoryEntry>> =
        get("$base/topups/$topUpId/history", ListSerializer(CardHistoryDto.serializer()))
            .map { rows -> rows.map { it.toDomain() } }

    override suspend fun requestTopUp(
        cardId: String,
        amount: Double,
        reason: String?,
    ): ZillitResult<Unit> = post(
        "$base/cards/$cardId/request-top-up",
        buildJsonObject {
            // Currency and method are omitted: the server defaults them from
            // the card row, and a client-supplied currency could disagree with
            // the card's own.
            put("amount", JsonPrimitive(amount))
            putIfPresent("reason", reason)
        },
    )

    /** An empty object, as the web's `completeTopup(t.id, {})` sends (`TopUpToDoPage.jsx:181`). */
    override suspend fun completeTopUp(topUpId: String): ZillitResult<Unit> =
        patch("$base/topups/$topUpId/complete", JsonObject(emptyMap()))

    override suspend fun partialTopUp(topUpId: String, amount: Double?, note: String): ZillitResult<Unit> =
        patch(
            "$base/topups/$topUpId/partial",
            buildJsonObject {
                // Omitted rather than zero when not given — the web sends
                // `undefined` and the server takes the note on its own.
                amount?.let { put("amount", JsonPrimitive(it)) }
                put("note", JsonPrimitive(note.trim()))
            },
        )

    override suspend fun skipTopUp(topUpId: String): ZillitResult<Unit> =
        patch("$base/topups/$topUpId/skip", null)

    // -- alerts and settings -----------------------------------------------

    // -- queries and fund requests -----------------------------------------

    private val extras = CardQueryFundCalls(apiClient, config)

    override suspend fun queryThread(entityType: String, entityId: String): ZillitResult<QueryThread> =
        extras.thread(entityType, entityId)

    override suspend fun sendQuery(
        thread: QueryThread,
        entityType: String,
        entityId: String,
        text: String,
    ): ZillitResult<QueryThread> = extras.send(thread, entityType, entityId, text)

    override suspend fun fundRequests(): ZillitResult<List<FundRequest>> = extras.fundRequests()

    override suspend fun createFundRequest(draft: FundRequestDraft): ZillitResult<Unit> =
        extras.createFundRequest(draft)

    override suspend fun receiveFundRequest(id: String): ZillitResult<Unit> = extras.receive(id)

    override suspend fun cancelFundRequest(id: String): ZillitResult<Unit> = extras.cancel(id)

    // -- exports -----------------------------------------------------------

    override suspend fun exportCards(format: ExportFormat, rows: List<CardExportRow>): ZillitResult<ByteArray> =
        bytes("$base/cards/export", cardExportBody(format, rows))

    override suspend fun exportTransactions(format: ExportFormat): ZillitResult<ByteArray> =
        bytes("$base/transactions/export", transactionExportBody(format))

    private suspend fun bytes(url: String, body: JsonObject): ZillitResult<ByteArray> =
        binaryPost?.post(url, body)
            ?: ZillitResult.Failure(ZillitError.Validation(str(S.desktop_cannot_download_exports)))

    override suspend fun alerts(): ZillitResult<List<CardAlert>> =
        get("$base/alerts", ListSerializer(AlertDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    /**
     * Always `{note}`, empty or not — the web's `resolveAlert(id, {note})`
     * (`SmartAlertsPage.jsx:144-148`), where the note is optional.
     */
    override suspend fun resolveAlert(alertId: String, note: String?): ZillitResult<Unit> =
        patch("$base/alerts/$alertId/resolve", buildJsonObject { put("note", JsonPrimitive(note.orEmpty())) })

    override suspend fun dismissAlert(alertId: String): ZillitResult<Unit> =
        patch("$base/alerts/$alertId/dismiss", null)

    override suspend fun investigateAlert(alertId: String): ZillitResult<Unit> =
        patch("$base/alerts/$alertId/investigate", null)

    override suspend fun settings(): ZillitResult<CardSettings> =
        get("$base/settings", CardSettingsDto.serializer()).map { it.toDomain() }

    /**
     * Saves **one section** of the settings document.
     *
     * A PATCH merges, so a body carrying one key leaves the rest of the
     * document alone — which is what makes per-section saving safe when two
     * accountants have the page open. Sending the whole document instead lets
     * a stale copy of the coordinators overwrite a change somebody made to the
     * providers thirty seconds earlier.
     *
     * Only these five keys exist on the row. An earlier build of this screen
     * sent `auto_match_enabled`, `duplicate_detection` and three more that are
     * not columns; the allowlist dropped every one and the page still said
     * "Settings saved".
     */
    override suspend fun updateSettings(
        section: SettingsSection,
        settings: CardSettings,
    ): ZillitResult<CardSettings> = apiClient.request(
        verb = HttpVerb.Patch,
        url = "$base/settings",
        serializer = CardSettingsDto.serializer(),
        module = RequestModule.ProjectUser,
        body = settings.sectionBody(section),
    ).map { it.toDomain() }

    private fun CardSettings.sectionBody(section: SettingsSection): JsonObject = when (section) {
        SettingsSection.Team -> buildJsonObject { put("team_members", teamBody()) }
        SettingsSection.Coordinators -> buildJsonObject { put("department_coordinators", coordinatorBody()) }
        SettingsSection.Overrides -> buildJsonObject { put("approval_override", overridesBody()) }
        SettingsSection.Providers -> buildJsonObject { put("card_providers", providersBody()) }
        // The whole object, all four keys: a bare figure here is a shape the
        // web does not read, and the next web save would overwrite it.
        SettingsSection.RequestCap -> buildJsonObject { put("request_cap", requestCap.body()) }
    }

    private fun CardSettings.teamBody() = buildJsonArray {
        teamMembers.map { it.normalised() }.forEach { member ->
            add(
                buildJsonObject {
                    put("user_id", JsonPrimitive(member.userId))
                    // Explicit null, never absent: null is "unlimited" and zero
                    // is "may post nothing", and an omitted key would be read
                    // as neither.
                    put("posting_limit", member.postingLimit?.let(::JsonPrimitive) ?: JsonNull)
                    put("can_override", JsonPrimitive(member.canOverride))
                    put("is_senior", JsonPrimitive(member.isSenior))
                },
            )
        }
    }

    private fun CardSettings.coordinatorBody() = buildJsonArray {
        coordinators.forEach { coordinator ->
            add(
                buildJsonObject {
                    put("department_id", JsonPrimitive(coordinator.departmentId))
                    put("user_ids", jsonIds(coordinator.userIds))
                    put("coding_required", JsonPrimitive(coordinator.codingRequired))
                },
            )
        }
    }

    private fun CardSettings.overridesBody() = buildJsonObject {
        put("override_card_req", JsonPrimitive(overrides.overrideCardRequests))
        put("override_receipt", JsonPrimitive(overrides.overrideReceipts))
        put("require_coord_code", JsonPrimitive(overrides.requireCoordinatorCoding))
        put("require_senior_sign_off", JsonPrimitive(overrides.requireSeniorSignOff))
    }

    /**
     * Every field of every provider: the list is replaced whole, and a row
     * sent as `{id, name}` loses its bank binding, its company and its
     * custodian and float accounts. Only rows with nothing typed at all are
     * dropped (`sanitizeCardProviders`) — a row with a bank but no name yet
     * is kept, and flagged on the page instead.
     */
    private fun CardSettings.providersBody() = buildJsonArray {
        providers.filterNot { it.blank }.forEach { provider -> add(provider.body()) }
    }

    // -- plumbing ----------------------------------------------------------

    private suspend fun receiptList(url: String) =
        get(url, ListSerializer(ReceiptDto.serializer())).map { rows -> rows.mapNotNull { it.toDomain() } }

    private suspend fun topUpList(url: String) =
        get(url, ListSerializer(CardTopUpDto.serializer())).map { rows -> rows.mapNotNull { it.toDomain() } }

    private suspend fun <T> get(
        url: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        query: Map<String, Any?> = emptyMap(),
    ): ZillitResult<T> = apiClient.request(
        verb = HttpVerb.Get,
        url = url,
        serializer = serializer,
        module = RequestModule.ProjectUser,
        queryParameters = query,
    )

    private suspend fun post(url: String, body: JsonObject?): ZillitResult<Unit> =
        apiClient.envelope(HttpVerb.Post, url, RequestModule.ProjectUser, body).map { }

    private suspend fun patch(url: String, body: JsonObject?): ZillitResult<Unit> =
        apiClient.envelope(HttpVerb.Patch, url, RequestModule.ProjectUser, body).map { }

    private suspend fun delete(url: String): ZillitResult<Unit> =
        apiClient.envelope(HttpVerb.Delete, url, RequestModule.ProjectUser).map { }

    /**
     * The body every receipt-coding write shares.
     *
     * `card_id` and `currency` ride along on all of them: the service refuses a
     * receipt write that does not name the card it belongs to, and the receipt
     * already knows its own — so they are echoed rather than looked up.
     */
    private fun ReceiptCoding.body(): JsonObject = buildJsonObject {
        // Left out when blank, as the web's `costCode || undefined`: the
        // coding queue's Save Draft may go with no code, and a blank string
        // would overwrite one somebody else had saved.
        putIfPresent("nominal_code", nominalCode)
        putIfPresent("episode", episode)
        putIfPresent("code_description", codeDescription)
        put("card_id", cardId?.let(::JsonPrimitive) ?: JsonNull)
        put("currency", currency?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun jsonIds(ids: List<String>) =
        buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }

    /** Who did it and why — the body every reject and override carries on the web. */
    private fun actorBody(userId: String, reason: String): JsonObject = buildJsonObject {
        put("user_id", JsonPrimitive(userId))
        put("reason", JsonPrimitive(reason.trim()))
    }

    private companion object {
        const val PENDING = "pending"
        const val CARD_ENTITY = "card"
        const val PHYSICAL = "physical"
        const val LAST_FOUR = 4
    }
}

/** Adds [key] only when [value] has something in it. See the cash repository. */
internal fun JsonObjectBuilder.putIfPresent(key: String, value: String?) {
    val trimmed = value?.trim()
    if (!trimmed.isNullOrEmpty()) put(key, JsonPrimitive(trimmed))
}

/**
 * Always adds [key], with an explicit null where there is nothing to say.
 *
 * For the bulk-coding overrides, where null is a value in its own right —
 * "leave each row's own coding alone" — rather than the absence of one.
 */
internal fun JsonObjectBuilder.putOrNull(key: String, value: String?) {
    val trimmed = value?.trim()
    put(key, if (trimmed.isNullOrEmpty()) JsonNull else JsonPrimitive(trimmed))
}
