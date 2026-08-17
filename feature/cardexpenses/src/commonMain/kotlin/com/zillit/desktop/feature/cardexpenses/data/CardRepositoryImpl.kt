package com.zillit.desktop.feature.cardexpenses.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.cardexpenses.domain.BulkAction
import com.zillit.desktop.feature.cardexpenses.domain.BulkCoding
import com.zillit.desktop.feature.cardexpenses.domain.BulkItem
import com.zillit.desktop.feature.cardexpenses.domain.BulkOutcome
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptLine
import com.zillit.desktop.feature.cardexpenses.domain.StatementRow
import com.zillit.desktop.feature.cardexpenses.domain.CardAlert
import com.zillit.desktop.feature.cardexpenses.domain.CardAnalytics
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
import com.zillit.desktop.feature.cardexpenses.domain.StatementImport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

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

    override suspend fun requestCard(request: NewCardRequest): ZillitResult<Unit> = post(
        "$base/cards",
        buildJsonObject {
            put("user_id", JsonPrimitive(request.holderId))
            put("card_limit", JsonPrimitive(request.limit))
            put("card_type", JsonPrimitive(request.type.wire))
            putIfPresent("currency", request.currency)
            putIfPresent("department_id", request.departmentId)
            putIfPresent("company_id", request.companyId)
            putIfPresent("card_provider_id", request.providerId)
            putIfPresent("bs_control_code", request.bsControlCode)
            putIfPresent("reason", request.reason)
            put("status", JsonPrimitive(REQUESTED))
        },
    )

    override suspend fun approveCard(cardId: String, note: String?): ZillitResult<Unit> =
        post("$base/cards/$cardId/approve", noteBody(note))

    override suspend fun rejectCard(cardId: String, reason: String): ZillitResult<Unit> =
        post("$base/cards/$cardId/reject", buildJsonObject { put("reason", JsonPrimitive(reason)) })

    override suspend fun overrideCard(cardId: String): ZillitResult<Unit> =
        post("$base/cards/$cardId/override", null)

    override suspend fun activateCard(cardId: String, fullCardNumber: String?): ZillitResult<Unit> =
        post(
            "$base/cards/$cardId/activate",
            fullCardNumber?.let {
                buildJsonObject { put("full_card_number", JsonPrimitive(it.filter(Char::isDigit))) }
            },
        )

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

    // -- statement imports -------------------------------------------------

    override suspend fun imports(): ZillitResult<List<StatementImport>> =
        get("$base/imports", ListSerializer(ImportDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun importStatement(attachmentKey: String): ZillitResult<Unit> = post(
        // The backend ingests via a storage pointer rather than multipart: the
        // client uploads the file first and hands over the key.
        "$base/imports/import-statement",
        buildJsonObject { put("attachment", JsonPrimitive(attachmentKey)) },
    )

    override suspend fun rerunMatching(statementId: String): ZillitResult<Unit> = post(
        "$base/receipts/rerun-match",
        buildJsonObject { put("statementId", JsonPrimitive(statementId)) },
    )

    override suspend fun importRows(importId: String): ZillitResult<List<StatementRow>> =
        get("$base/imports/$importId/rows", ListSerializer(StatementRowDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun processImport(importId: String, rowIds: List<String>): ZillitResult<Unit> =
        post(
            "$base/imports/$importId/process",
            buildJsonObject { put("rows", jsonIds(rowIds)) },
        )

    override suspend fun submitRowsToHolders(transactionIds: List<String>): ZillitResult<Unit> =
        post(
            "$base/transactions/submit-to-users",
            buildJsonObject { put("transactionIds", jsonIds(transactionIds)) },
        )

    // -- transactions ------------------------------------------------------

    override suspend fun transactions(): ZillitResult<List<CardTransaction>> =
        get("$base/transactions", ListSerializer(TransactionDto.serializer()))
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

    override suspend fun submitReceipts(
        cardId: String?,
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
                                putIfPresent("card_id", cardId)
                                put("description", JsonPrimitive(receipt.description))
                                put("amount", JsonPrimitive(receipt.amount.trim().toDoubleOrNull() ?: 0.0))
                                putIfPresent("merchant", receipt.merchant)
                                putIfPresent("nominal_code", receipt.nominalCode)
                                receipt.date?.let { put("date", JsonPrimitive(it)) }
                                putIfPresent("receipt_attachment", receipt.attachmentKey)
                            },
                        )
                    }
                },
            )
        },
    )

    override suspend fun submitReceiptForApproval(receiptId: String): ZillitResult<Unit> =
        post("$base/receipts/$receiptId/submit-for-approval", null)

    override suspend fun codeReceipt(
        receiptId: String,
        nominalCode: String,
        description: String?,
    ): ZillitResult<Unit> = post(
        "$base/receipts/$receiptId/submit-coding",
        buildJsonObject {
            put("nominal_code", JsonPrimitive(nominalCode))
            putIfPresent("code_description", description)
        },
    )

    override suspend fun postReceipt(receiptId: String): ZillitResult<Unit> =
        post("$base/receipts/$receiptId/post", null)

    override suspend fun flagReceiptPersonal(receiptId: String): ZillitResult<Unit> =
        post("$base/receipts/$receiptId/flag-personal", null)

    override suspend fun dismissDuplicate(receiptId: String): ZillitResult<Unit> =
        post("$base/receipts/$receiptId/dismiss-duplicate", null)

    override suspend fun dismissPersonal(receiptId: String): ZillitResult<Unit> =
        post("$base/receipts/$receiptId/dismiss-personal", null)

    override suspend fun deleteReceipt(receiptId: String): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Delete,
            url = "$base/receipts/$receiptId",
            module = RequestModule.ProjectUser,
        ).map { }

    override suspend fun receiptHistory(receiptId: String): ZillitResult<List<CardHistoryEntry>> =
        get("$base/receipts/$receiptId/history", ListSerializer(CardHistoryDto.serializer()))
            .map { rows -> rows.map { it.toDomain() } }

    /**
     * Replaces a receipt's splits.
     *
     * PUT rather than PATCH because it is a replacement: the server reconciles
     * against exactly what it is given, so a partial send removes the rest.
     */
    override suspend fun saveReceiptLines(
        receiptId: String,
        lines: List<ReceiptLine>,
    ): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Put,
        url = "$base/receipts/$receiptId/line-items",
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put(
                "line_items",
                buildJsonArray {
                    lines.forEach { line ->
                        add(
                            buildJsonObject {
                                putIfPresent("id", line.id)
                                put("description", JsonPrimitive(line.description))
                                put("nominal_code", JsonPrimitive(line.nominalCode))
                                put("net", JsonPrimitive(line.net))
                                put("tax_amount", JsonPrimitive(line.taxAmount))
                                putIfPresent("episode", line.episode)
                            },
                        )
                    }
                },
            )
        },
    ).map { }

    override suspend fun repostReceipt(receiptId: String): ZillitResult<Unit> =
        post("$base/receipts/$receiptId/repost", null)

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
                    putOrNull("tax", coding.taxType)
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

    override suspend fun batchSubmit(receiptIds: List<String>): ZillitResult<Unit> =
        post("$base/bulk/submit", buildJsonObject { put("ids", jsonIds(receiptIds)) })

    override suspend fun batchPost(receiptIds: List<String>): ZillitResult<Unit> =
        post("$base/bulk/post", buildJsonObject { put("ids", jsonIds(receiptIds)) })

    // -- approvals ---------------------------------------------------------

    override suspend fun approvalQueue(): ZillitResult<List<CardReceipt>> =
        receiptList("$base/approvals")

    override suspend fun approveReceipt(receiptId: String, note: String?): ZillitResult<Unit> =
        post("$base/approvals/$receiptId/approve", noteBody(note))

    override suspend fun rejectReceipt(receiptId: String, reason: String): ZillitResult<Unit> =
        post(
            "$base/approvals/$receiptId/reject",
            buildJsonObject { put("reason", JsonPrimitive(reason)) },
        )

    override suspend fun overrideReceipt(receiptId: String): ZillitResult<Unit> =
        post("$base/approvals/$receiptId/override", null)

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

    override suspend fun topUps(): ZillitResult<List<CardTopUp>> = topUpList("$base/topups")

    override suspend fun cardTopUps(cardId: String): ZillitResult<List<CardTopUp>> =
        topUpList("$base/cards/$cardId/top-ups")

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

    override suspend fun completeTopUp(topUpId: String): ZillitResult<Unit> =
        patch("$base/topups/$topUpId/complete", null)

    override suspend fun partialTopUp(topUpId: String, amount: Double): ZillitResult<Unit> =
        patch("$base/topups/$topUpId/partial", buildJsonObject { put("amount", JsonPrimitive(amount)) })

    override suspend fun skipTopUp(topUpId: String): ZillitResult<Unit> =
        patch("$base/topups/$topUpId/skip", null)

    // -- alerts and settings -----------------------------------------------

    override suspend fun alerts(): ZillitResult<List<CardAlert>> =
        get("$base/alerts", ListSerializer(AlertDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun resolveAlert(alertId: String, note: String?): ZillitResult<Unit> =
        patch("$base/alerts/$alertId/resolve", noteBody(note))

    override suspend fun dismissAlert(alertId: String): ZillitResult<Unit> =
        patch("$base/alerts/$alertId/dismiss", null)

    override suspend fun investigateAlert(alertId: String): ZillitResult<Unit> =
        patch("$base/alerts/$alertId/investigate", null)

    override suspend fun settings(): ZillitResult<CardSettings> =
        get("$base/settings", CardSettingsDto.serializer()).map { it.toDomain() }

    override suspend fun updateSettings(settings: CardSettings): ZillitResult<CardSettings> =
        apiClient.request(
            verb = HttpVerb.Patch,
            url = "$base/settings",
            serializer = CardSettingsDto.serializer(),
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put("coding_required", JsonPrimitive(settings.codingRequired))
                put("require_senior_sign_off", JsonPrimitive(settings.requireSeniorSignOff))
                put("auto_match_enabled", JsonPrimitive(settings.autoMatchEnabled))
                put("auto_match_threshold", JsonPrimitive(settings.autoMatchThreshold))
                put("duplicate_detection", JsonPrimitive(settings.duplicateDetection))
                put("personal_spend_detection", JsonPrimitive(settings.personalSpendDetection))
                settings.defaultCardLimit?.let { put("default_card_limit", JsonPrimitive(it)) }
            },
        ).map { it.toDomain() }

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

    private fun jsonIds(ids: List<String>) =
        buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }

    private fun noteBody(note: String?): JsonObject? =
        note?.takeIf { it.isNotBlank() }?.let { buildJsonObject { put("note", JsonPrimitive(it)) } }

    private companion object {
        const val REQUESTED = "requested"
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
