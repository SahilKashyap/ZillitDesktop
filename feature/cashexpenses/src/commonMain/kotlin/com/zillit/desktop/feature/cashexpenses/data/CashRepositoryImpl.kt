package com.zillit.desktop.feature.cashexpenses.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.common.toAmount
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashHistoryEntry
import com.zillit.desktop.feature.cashexpenses.domain.CashMetadata
import com.zillit.desktop.feature.cashexpenses.domain.CashQueue
import com.zillit.desktop.feature.cashexpenses.domain.CashRepository
import com.zillit.desktop.feature.cashexpenses.domain.CashSettings
import com.zillit.desktop.feature.cashexpenses.domain.CashTopUp
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.ClaimLineItem
import com.zillit.desktop.feature.cashexpenses.domain.DepartmentOverview
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.domain.MyCashOverview
import com.zillit.desktop.feature.cashexpenses.domain.NewClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.NewFloatRequest
import com.zillit.desktop.feature.cashexpenses.domain.OutOfPocketOverview
import com.zillit.desktop.feature.cashexpenses.domain.PaymentRouting
import com.zillit.desktop.feature.cashexpenses.domain.PettyCashOverview
import com.zillit.desktop.feature.cashexpenses.domain.Reconciliation
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Every `/api/v2/cash-expenses` route, on the cash service's own host.
 *
 * ## Why the module header is `ProjectUser`
 *
 * Every call here is scoped to one person on one production — a float belongs
 * to a crew member, a queue is filtered by what the caller may see. The lighter
 * `Device` header omits the project and user, and the cash service answers 406
 * to it rather than falling back, so this is not a preference.
 */
@Suppress("TooManyFunctions") // Mirrors the server's operation surface; see the interface.
class CashRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
) : CashRepository {

    private val base = "${config.baseUrl(ZillitService.CashExpenses)}/api/v2/cash-expenses"

    // -- who am I ----------------------------------------------------------

    override suspend fun metadata(): ZillitResult<CashMetadata> =
        get("$base/metadata", MetadataDto.serializer()).map { it.toDomain() }

    // -- floats ------------------------------------------------------------

    override suspend fun myFloats(): ZillitResult<List<CashFloat>> =
        floats("$base/float-requests/my-floats")

    override suspend fun activeFloats(): ZillitResult<List<CashFloat>> =
        floats("$base/float-requests/active-floats")

    override suspend fun floatApprovalQueue(): ZillitResult<List<CashFloat>> =
        floats("$base/float-requests/approval-queue")

    override suspend fun floatHistory(floatId: String): ZillitResult<List<CashHistoryEntry>> =
        history("$base/float-requests/$floatId/history")

    override suspend fun requestFloat(request: NewFloatRequest): ZillitResult<Unit> =
        post(
            "$base/float-requests",
            buildJsonObject {
                put("req_amount", JsonPrimitive(request.amount))
                putIfPresent("currency", request.currency)
                put("purpose", JsonPrimitive(request.purpose))
                putIfPresent("department_id", request.departmentId)
                putIfPresent("duration", request.duration)
                putIfPresent("duration_type", request.durationType)
                putIfPresent("bs_code", request.bsCode)
                putIfPresent("company_id", request.companyId)
            },
        )

    override suspend fun approveFloat(floatId: String, note: String?): ZillitResult<Unit> =
        post("$base/float-requests/$floatId/approve", noteBody(note))

    override suspend fun rejectFloat(floatId: String, reason: String): ZillitResult<Unit> =
        post("$base/float-requests/$floatId/reject", buildJsonObject { put("reason", JsonPrimitive(reason)) })

    override suspend fun overrideFloat(floatId: String): ZillitResult<Unit> =
        post("$base/float-requests/$floatId/override", null)

    override suspend fun markFloatReadyToCollect(
        floatId: String,
        companyId: String?,
    ): ZillitResult<Unit> = post(
        "$base/float-requests/$floatId/ready-to-collect",
        // Omitted entirely rather than sent as null: the legacy route takes no
        // body at all, and a float that already carries a company must not have
        // it overwritten with nothing.
        companyId?.let { buildJsonObject { put("company_id", JsonPrimitive(it)) } },
    )

    override suspend fun issueFloat(floatId: String): ZillitResult<Unit> =
        post("$base/float-requests/$floatId/issue", null)

    override suspend fun collectFloat(floatId: String): ZillitResult<Unit> =
        post("$base/float-requests/$floatId/collect", null)

    override suspend fun closeFloat(floatId: String): ZillitResult<Unit> =
        post("$base/float-requests/$floatId/mark-closed", null)

    override suspend fun recordCashReturn(
        floatId: String,
        amount: Double,
        note: String?,
    ): ZillitResult<Unit> = post(
        "$base/float-requests/$floatId/record-return",
        buildJsonObject {
            put("amount", JsonPrimitive(amount))
            putIfPresent("note", note)
        },
    )

    // -- float top-ups -----------------------------------------------------

    override suspend fun floatTopUps(floatId: String): ZillitResult<List<CashTopUp>> =
        // The route is PLURAL. The backend's own announcement email spells it
        // singular and that path 404s — verified against dev.
        topUpList("$base/float-requests/$floatId/top-ups")

    override suspend fun requestFloatTopUp(
        floatId: String,
        amount: Double,
        reason: String?,
    ): ZillitResult<Unit> = post(
        "$base/float-requests/$floatId/request-top-up",
        buildJsonObject {
            // Currency is deliberately not sent: the server reads it from the
            // float row, and a client-supplied one could disagree with the cash
            // actually held.
            put("amount", JsonPrimitive(amount))
            putIfPresent("reason", reason)
        },
    )

    override suspend fun topUps(): ZillitResult<List<CashTopUp>> = topUpList("$base/top-ups")

    override suspend fun completeTopUp(topUpId: String): ZillitResult<Unit> =
        patch("$base/top-ups/$topUpId/complete", null)

    override suspend fun partialTopUp(topUpId: String, amount: Double): ZillitResult<Unit> =
        patch("$base/top-ups/$topUpId/partial", buildJsonObject { put("amount", JsonPrimitive(amount)) })

    override suspend fun skipTopUp(topUpId: String): ZillitResult<Unit> =
        patch("$base/top-ups/$topUpId/skip", null)

    // -- claim batches -----------------------------------------------------

    override suspend fun myBatches(): ZillitResult<List<ClaimBatch>> =
        batches("$base/claims/my-batches")

    override suspend fun batch(batchId: String): ZillitResult<ClaimBatch> =
        get("$base/claims/$batchId", BatchDto.serializer()).flatMap { dto ->
            // A row with no id cannot be acted on — every action route is keyed
            // by it — so this is reported rather than rendered as a blank batch.
            dto.toDomain()?.let { ZillitResult.Success(it) }
                ?: ZillitResult.Failure(ZillitError.Serialization("batch $batchId came back without an id"))
        }

    override suspend fun batchHistory(batchId: String): ZillitResult<List<CashHistoryEntry>> =
        history("$base/claims/$batchId/history")

    override suspend fun submitReceipts(request: NewClaimBatch): ZillitResult<Unit> = post(
        "$base/claims",
        buildJsonObject {
            put("expense_type", JsonPrimitive(request.expenseType.wire))
            putIfPresent("float_request_id", request.floatId)
            putIfPresent("settlement_type", request.settlementType)
            putIfPresent("notes", request.notes)
            put(
                "claims",
                buildJsonArray {
                    request.receipts.forEach { receipt ->
                        add(
                            buildJsonObject {
                                put("description", JsonPrimitive(receipt.description))
                                put("gross_amount", JsonPrimitive(receipt.amount.trim().toDoubleOrNull() ?: 0.0))
                                putIfPresent("vat_amount", receipt.vat.trim().toDoubleOrNull()?.toString())
                                putIfPresent("supplier", receipt.supplier)
                                putIfPresent("category", receipt.category)
                                putIfPresent("cost_code", receipt.costCode)
                                receipt.date?.let { put("receipt_date", JsonPrimitive(it)) }
                                putIfPresent("receipt_url", receipt.attachmentKey)
                            },
                        )
                    }
                },
            )
        },
    )

    override suspend fun resubmitBatch(batchId: String, note: String?): ZillitResult<Unit> =
        post("$base/claims/$batchId/resubmit", noteBody(note))

    override suspend fun queue(
        queue: CashQueue,
        expenseType: ExpenseType?,
    ): ZillitResult<List<ClaimBatch>> = batches(
        url = "$base/claims/${queue.path}",
        query = expenseType?.let { mapOf("expense_type" to it.wire) }.orEmpty(),
    )

    override suspend fun codeClaim(
        batchId: String,
        claimId: String,
        costCode: String,
        description: String?,
    ): ZillitResult<Unit> = patch(
        "$base/claims/$batchId/claims/$claimId/code",
        buildJsonObject {
            put("cost_code", JsonPrimitive(costCode))
            putIfPresent("coded_description", description)
        },
    )

    override suspend fun saveClaimLines(
        batchId: String,
        claimId: String,
        lines: List<ClaimLineItem>,
    ): ZillitResult<Unit> = post(
        "$base/claims/$batchId/save-claims",
        buildJsonObject {
            put(
                "claims",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", JsonPrimitive(claimId))
                            put(
                                "line_items",
                                buildJsonArray {
                                    lines.forEach { line ->
                                        add(
                                            buildJsonObject {
                                                putIfPresent("id", line.id)
                                                put("description", JsonPrimitive(line.description))
                                                put("quantity", JsonPrimitive(line.quantity))
                                                put("unit_price", JsonPrimitive(line.unitPrice))
                                                // GROSS — see ClaimLineItem.total.
                                                put("total", JsonPrimitive(line.total))
                                                put("tax_rate", JsonPrimitive(line.taxRate ?: 0.0))
                                                putIfPresent("account", line.account)
                                                putIfPresent("tax_type", line.taxType)
                                                putIfPresent("split_parent_id", line.splitParentId)
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                },
            )
        },
    )

    override suspend fun saveAndSubmitCoded(batchId: String): ZillitResult<Unit> =
        post("$base/claims/$batchId/save-and-submit", null)

    override suspend fun saveAndVerify(batchId: String): ZillitResult<Unit> =
        post("$base/claims/$batchId/save-and-verify", null)

    override suspend fun approveBatch(batchId: String, note: String?): ZillitResult<Unit> = post(
        "$base/claims/$batchId/batch-approval",
        buildJsonObject {
            put("action", JsonPrimitive(APPROVE))
            putIfPresent("note", note)
        },
    )

    override suspend fun rejectBatch(batchId: String, reason: String): ZillitResult<Unit> = post(
        "$base/claims/$batchId/batch-approval",
        buildJsonObject {
            put("action", JsonPrimitive(REJECT))
            put("reason", JsonPrimitive(reason))
        },
    )

    override suspend fun overrideBatch(batchId: String): ZillitResult<Unit> =
        post("$base/claims/$batchId/override", null)

    override suspend fun queryBatch(batchId: String, reason: String): ZillitResult<Unit> =
        post("$base/claims/$batchId/query", buildJsonObject { put("reason", JsonPrimitive(reason)) })

    override suspend fun escalateBatch(batchId: String, reason: String?): ZillitResult<Unit> =
        post("$base/claims/$batchId/escalate", noteBody(reason))

    override suspend fun submitBatchForReview(batchId: String): ZillitResult<Unit> =
        post("$base/claims/$batchId/submit-for-review", null)

    override suspend fun assignBatch(
        batchId: String,
        userId: String,
        reason: String?,
    ): ZillitResult<Unit> = post(
        "$base/claims/$batchId/assign",
        buildJsonObject {
            put("assigned_to", JsonPrimitive(userId))
            putIfPresent("assignment_reason", reason)
        },
    )

    override suspend fun postBatch(batchId: String, note: String?): ZillitResult<Unit> =
        post("$base/claims/$batchId/post", noteBody(note))

    // -- dashboards --------------------------------------------------------

    override suspend fun pettyCashOverview(): ZillitResult<PettyCashOverview> =
        get("$base/claims/overview/petty-cash", PettyCashOverviewDto.serializer()).map { it.toDomain() }

    override suspend fun outOfPocketOverview(): ZillitResult<OutOfPocketOverview> =
        get("$base/claims/overview/out-of-pocket", OopOverviewDto.serializer()).map { it.toDomain() }

    override suspend fun myOverview(): ZillitResult<MyCashOverview> =
        get("$base/claims/overview/my", MyOverviewDto.serializer()).map { it.toDomain() }

    override suspend fun departmentOverview(departmentId: String): ZillitResult<DepartmentOverview> =
        get(
            url = "$base/claims/overview/department",
            serializer = DepartmentOverviewDto.serializer(),
            query = mapOf("department_id" to departmentId),
        ).map { it.toDomain() }

    override suspend fun paymentRouting(): ZillitResult<PaymentRouting> =
        get("$base/claims/overview/payment-routing", RoutingDto.serializer()).map { it.toDomain() }

    // -- reconciliation ----------------------------------------------------

    override suspend fun reconciliations(): ZillitResult<List<Reconciliation>> =
        get("$base/reconciliations", ListSerializer(ReconciliationDto.serializer()))
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun computeBookBalance(): ZillitResult<Double> =
        get("$base/reconciliations/compute-book", BookBalanceDto.serializer()).map {
            (it.bookBalance ?: it.balance).toAmount()
        }

    override suspend fun createReconciliation(
        countedBalance: Double,
        note: String?,
    ): ZillitResult<Reconciliation> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/reconciliations",
        serializer = ReconciliationDto.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put("counted_balance", JsonPrimitive(countedBalance))
            putIfPresent("note", note)
        },
    ).flatMap { dto ->
        dto.toDomain()?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Serialization("reconciliation came back without an id"))
    }

    override suspend fun submitReconciliationForReview(id: String): ZillitResult<Unit> =
        post("$base/reconciliations/$id/submit-for-review", null)

    override suspend fun signOffReconciliation(id: String, note: String?): ZillitResult<Unit> =
        post("$base/reconciliations/$id/sign-off", noteBody(note))

    // -- settings ----------------------------------------------------------

    override suspend fun settings(): ZillitResult<CashSettings> =
        get("$base/settings", SettingsDto.serializer()).map { it.toDomain() }

    override suspend fun updateSettings(settings: CashSettings): ZillitResult<CashSettings> =
        apiClient.request(
            verb = HttpVerb.Patch,
            url = "$base/settings",
            serializer = SettingsDto.serializer(),
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put("float_custodian_account", JsonPrimitive(settings.custodianAccount))
                put("bs_code_from", JsonPrimitive(settings.bsCodeFrom))
                put("bs_code_to", JsonPrimitive(settings.bsCodeTo))
                // Sent as a nested object, which is how the column is stored.
                // Flattening these four onto the root silently does nothing.
                put(
                    "approval_override",
                    buildJsonObject {
                        put("override_float_req", JsonPrimitive(settings.overrideFloatRequest))
                        put("override_receipt_batch", JsonPrimitive(settings.overrideReceiptBatch))
                        put("require_coord_code", JsonPrimitive(settings.requireCoordinatorCoding))
                        put("require_senior_sign_off", JsonPrimitive(settings.requireSeniorSignOff))
                    },
                )
            },
        ).map { it.toDomain() }

    // -- plumbing ----------------------------------------------------------

    private suspend fun floats(url: String) =
        get(url, ListSerializer(FloatDto.serializer())).map { rows -> rows.mapNotNull { it.toDomain() } }

    private suspend fun batches(url: String, query: Map<String, Any?> = emptyMap()) =
        get(url, ListSerializer(BatchDto.serializer()), query)
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    private suspend fun topUpList(url: String) =
        get(url, ListSerializer(TopUpDto.serializer())).map { rows -> rows.mapNotNull { it.toDomain() } }

    private suspend fun history(url: String) =
        get(url, ListSerializer(HistoryDto.serializer())).map { rows -> rows.map { it.toDomain() } }

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

    /**
     * A mutation whose response body this client does not read.
     *
     * Returns `Unit` rather than the updated row on purpose: these endpoints
     * answer with differently-shaped envelopes depending on the transition, and
     * the screens all refetch the affected queue afterwards anyway. Decoding a
     * body nobody reads is a parse failure waiting to be reported as a failed
     * approval that in fact succeeded.
     */
    private suspend fun post(url: String, body: JsonObject?): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = url,
            module = RequestModule.ProjectUser,
            body = body,
        ).map { }

    private suspend fun patch(url: String, body: JsonObject?): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Patch,
            url = url,
            module = RequestModule.ProjectUser,
            body = body,
        ).map { }

    private fun noteBody(note: String?): JsonObject? =
        note?.takeIf { it.isNotBlank() }?.let { buildJsonObject { put("note", JsonPrimitive(it)) } }

    private companion object {
        const val APPROVE = "approve"
        const val REJECT = "reject"
    }
}

/**
 * Adds [key] only when [value] has something in it.
 *
 * Sending `null` and omitting a key are different to this backend: several
 * columns are cleared by an explicit null, so a builder that always writes
 * every field wipes data the user did not touch.
 */
internal fun kotlinx.serialization.json.JsonObjectBuilder.putIfPresent(key: String, value: String?) {
    val trimmed = value?.trim()
    if (!trimmed.isNullOrEmpty()) put(key, JsonPrimitive(trimmed))
}

