package com.zillit.desktop.feature.cashexpenses.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.common.toAmount
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.forms.CustomFieldGroup
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.cashexpenses.domain.CashAssignmentRule
import com.zillit.desktop.feature.cashexpenses.domain.CashCompany
import com.zillit.desktop.feature.cashexpenses.domain.CashDates
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashHistoryEntry
import com.zillit.desktop.feature.cashexpenses.domain.CashMetadata
import com.zillit.desktop.feature.cashexpenses.domain.CashQueue
import com.zillit.desktop.feature.cashexpenses.domain.CashRepository
import com.zillit.desktop.feature.cashexpenses.domain.CashReturn
import com.zillit.desktop.feature.cashexpenses.domain.CashSettings
import com.zillit.desktop.feature.cashexpenses.domain.CashTeamMember
import com.zillit.desktop.feature.cashexpenses.domain.CashTopUp
import com.zillit.desktop.feature.cashexpenses.domain.Claim
import com.zillit.desktop.feature.cashexpenses.domain.ClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.ClaimLineItem
import com.zillit.desktop.feature.cashexpenses.domain.DepartmentOverview
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.domain.ExportFormat
import com.zillit.desktop.feature.cashexpenses.domain.FundRequest
import com.zillit.desktop.feature.cashexpenses.domain.MyCashOverview
import com.zillit.desktop.feature.cashexpenses.domain.NewClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.NewFloatRequest
import com.zillit.desktop.feature.cashexpenses.domain.OutOfPocketOverview
import com.zillit.desktop.feature.cashexpenses.domain.PaymentRouting
import com.zillit.desktop.feature.cashexpenses.domain.PettyCashOverview
import com.zillit.desktop.feature.cashexpenses.domain.PostBatchRequest
import com.zillit.desktop.feature.cashexpenses.domain.QueryThread
import com.zillit.desktop.feature.cashexpenses.domain.Reconciliation
import com.zillit.desktop.feature.cashexpenses.domain.ReconDraft
import com.zillit.desktop.feature.cashexpenses.domain.RequestCap
import com.zillit.desktop.feature.cashexpenses.domain.TierStep
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
 *
 * ## Writes read the envelope's `status`
 *
 * A refusal arrives as HTTP 200 with `status: 0`, and `ApiClient` passes that
 * through as a success. Posting money and reading "Posted" over a refusal is
 * the worst outcome this module has, so every write checks it — see [write].
 */
@Suppress("TooManyFunctions") // Mirrors the server's operation surface; see the interface.
class CashRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** The register exports' byte POST; see [CashBinaryPost]. */
    binaryPost: CashBinaryPost? = null,
) : CashRepository {

    private val base = "${config.baseUrl(ZillitService.CashExpenses)}/api/v2/cash-expenses"

    /** The account hub and cost-report routes, and the exports — see [CashHubSource]. */
    private val hub = CashHubSource(apiClient, config, binaryPost, base)

    // -- who am I ----------------------------------------------------------

    /**
     * Read raw first: `posting_limit: null` (unlimited) and no `posting_limit`
     * at all (no grant) decode to the same null, and the posting rule needs
     * to tell them apart.
     */
    override suspend fun metadata(): ZillitResult<CashMetadata> =
        get("$base/metadata", JsonElement.serializer()).flatMap { raw ->
            val body = raw as? JsonObject ?: JsonObject(emptyMap())
            val limit = body["posting_limit"]
            val decoded = runCatching { cashLenient.decodeFromJsonElement(MetadataDto.serializer(), body) }
                .getOrNull()
                ?: return@flatMap ZillitResult.Failure(ZillitError.Serialization("metadata did not decode"))
            ZillitResult.Success(
                decoded.toDomain().copy(
                    postingLimitUnlimited = limit is JsonNull || (limit as? JsonPrimitive)?.content == UNLIMITED,
                ),
            )
        }

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
                // An accountant raising the float for a crew member — the
                // server makes it theirs (`PCFloatRequestPage.jsx:541`).
                putIfPresent("target_user_id", request.targetUserId)
                // Only when the production configured some. This service keeps
                // more of each field than purchase orders do — the key, the
                // type and a select's source — so a saved answer can be shown
                // again without re-reading the template.
                if (request.customFields.isNotEmpty()) {
                    put("custom_fields", request.customFields.toFloatJson())
                }
            },
        )

    /** `{tier_number, total_tiers}` — the level signed, as the web's approve sends it. */
    override suspend fun approveFloat(floatId: String, tier: TierStep?): ZillitResult<Unit> =
        post("$base/float-requests/$floatId/approve", tier?.let { tierBody(it) })

    override suspend fun rejectFloat(floatId: String, reason: String): ZillitResult<Unit> =
        post("$base/float-requests/$floatId/reject", buildJsonObject { put("reason", JsonPrimitive(reason)) })

    override suspend fun overrideFloat(floatId: String): ZillitResult<Unit> =
        post("$base/float-requests/$floatId/override", null)

    override suspend fun markFloatReadyToCollect(
        floatId: String,
        companyId: String?,
        bsCode: String?,
    ): ZillitResult<Unit> {
        // Each key only when there is something in it — never `""` — and no
        // body at all when neither is, which is the legacy route's call.
        val body = buildJsonObject {
            putIfPresent("company_id", companyId)
            putIfPresent("bs_code", bsCode)
        }
        return post("$base/float-requests/$floatId/ready-to-collect", body.takeIf { it.isNotEmpty() })
    }

    override suspend fun issueFloat(floatId: String): ZillitResult<Unit> =
        post("$base/float-requests/$floatId/issue", null)

    override suspend fun collectFloat(floatId: String): ZillitResult<Unit> =
        post("$base/float-requests/$floatId/collect", null)

    override suspend fun closeFloat(floatId: String): ZillitResult<Unit> =
        post("$base/float-requests/$floatId/mark-closed", null)

    /**
     * The web's `RecordCashReturnModal` body. It was `{amount, note}`, and the
     * route reads `return_amount`: every return recorded from here was zero.
     */
    override suspend fun recordCashReturn(floatId: String, cashReturn: CashReturn): ZillitResult<Unit> = post(
        "$base/float-requests/$floatId/record-return",
        buildJsonObject {
            put("return_amount", JsonPrimitive(cashReturn.amount))
            put("received_date", JsonPrimitive(cashReturn.receivedDate))
            put("return_reason", JsonPrimitive(cashReturn.reason))
            val notes = cashReturn.notes?.trim()?.ifBlank { null }
            put("reason_notes", notes?.let(::JsonPrimitive) ?: JsonNull)
            put("notes", notes?.let(::JsonPrimitive) ?: JsonNull)
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

    /** `{amount, note}` — the note is the reason for the shortfall and the web requires it. */
    override suspend fun partialTopUp(topUpId: String, amount: Double, note: String): ZillitResult<Unit> =
        patch(
            "$base/top-ups/$topUpId/partial",
            buildJsonObject {
                put("amount", JsonPrimitive(amount))
                put("note", JsonPrimitive(note.trim()))
            },
        )

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

    override suspend fun saveClaims(
        batchId: String,
        claims: List<Claim>,
        verified: Map<String, Boolean>,
    ): ZillitResult<Unit> =
        post("$base/claims/$batchId/save-claims", buildJsonObject { put("claims", claims.toClaimsPayload(verified)) })

    override suspend fun saveAndSubmitCoded(batchId: String, claims: List<Claim>?): ZillitResult<Unit> =
        post("$base/claims/$batchId/save-and-submit", claims?.let(::claimsBody))

    override suspend fun saveAndVerify(batchId: String, claims: List<Claim>?): ZillitResult<Unit> =
        post("$base/claims/$batchId/save-and-verify", claims?.let(::claimsBody))

    /**
     * `{action, claim_ids, tier_number, total_tiers}` — `claim_ids` null for
     * the whole batch, the chosen receipts for a partial approval.
     */
    override suspend fun approveBatch(
        batchId: String,
        tier: TierStep?,
        claimIds: List<String>?,
    ): ZillitResult<Unit> = post(
        "$base/claims/$batchId/batch-approval",
        buildJsonObject {
            put("action", JsonPrimitive(APPROVE))
            put("claim_ids", claimIds.toIdArray())
            tier?.let {
                put("tier_number", JsonPrimitive(it.tierNumber))
                put("total_tiers", JsonPrimitive(it.totalTiers))
            }
        },
    )

    override suspend fun rejectBatch(batchId: String, reason: String, claimIds: List<String>?): ZillitResult<Unit> =
        post(
            "$base/claims/$batchId/batch-approval",
            buildJsonObject {
                put("action", JsonPrimitive(REJECT))
                put("claim_ids", claimIds.toIdArray())
                put("reason", JsonPrimitive(reason))
            },
        )

    override suspend fun overrideBatch(batchId: String): ZillitResult<Unit> =
        post("$base/claims/$batchId/override", null)

    /** `{escalation_reason}` — this sent `{note}`, which the route does not read. */
    override suspend fun escalateBatch(batchId: String, reason: String): ZillitResult<Unit> =
        post("$base/claims/$batchId/escalate", buildJsonObject { put("escalation_reason", JsonPrimitive(reason)) })

    override suspend fun deescalateBatch(batchId: String): ZillitResult<Unit> =
        post("$base/claims/$batchId/deescalate", null)

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

    /**
     * Post & Ledger sends `{claims, effective_date}`; the senior sign-off
     * sends `{senior_notes, effective_date}`. The date is what the server
     * refuses a post without.
     */
    override suspend fun postBatch(batchId: String, request: PostBatchRequest): ZillitResult<Unit> = post(
        "$base/claims/$batchId/post",
        buildJsonObject {
            request.claims?.let { put("claims", it.toClaimsPayload()) }
            if (request.claims == null) {
                put("senior_notes", request.seniorNotes?.trim()?.ifBlank { null }?.let(::JsonPrimitive) ?: JsonNull)
            }
            put("effective_date", JsonPrimitive(request.effectiveDate))
        },
    )

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
        get("$base/claims/overview/payment-routing", PaymentRoutingDto.serializer()).map { it.toDomain() }

    // -- reconciliation ----------------------------------------------------

    override suspend fun reconciliations(): ZillitResult<List<Reconciliation>> =
        get(
            "$base/reconciliations",
            ListSerializer(ReconciliationDto.serializer()),
            mapOf("sort" to "created_at", "order" to "desc"),
        ).map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun reconciliation(id: String): ZillitResult<Reconciliation> =
        get("$base/reconciliations/$id", ReconciliationDto.serializer()).flatMap { it.reconciliation() }

    /** The page's figure asks with nothing; a draft asks for its own opening balance and month. */
    override suspend fun computeBookBalance(draft: ReconDraft?): ZillitResult<Double> {
        val query = draft?.let {
            val (start, end) = CashDates.monthBounds(it.year, it.month)
            mapOf("initial_amount" to it.opening, "period_start" to start, "period_end" to end)
        }.orEmpty()
        return get("$base/reconciliations/compute-book", BookBalanceDto.serializer(), query).map {
            (it.bookBalance ?: it.balance).toAmount()
        }
    }

    /**
     * Opens a period — `{opening_safe_balance, currency, period_start,
     * period_end, book_balance, denominations}`, as the web's create sends it.
     * This used to send `{counted_balance, note}`.
     */
    override suspend fun createReconciliation(draft: ReconDraft): ZillitResult<Reconciliation> {
        val (start, end) = CashDates.monthBounds(draft.year, draft.month)
        return writeRecon(
            HttpVerb.Post,
            "$base/reconciliations",
            buildJsonObject {
                put("opening_safe_balance", JsonPrimitive(draft.opening))
                putIfPresent("currency", draft.currency)
                put("period_start", JsonPrimitive(start))
                put("period_end", JsonPrimitive(end))
                put("book_balance", JsonPrimitive(draft.opening))
                put("denominations", draft.denominations.toJson())
            },
        )
    }

    override suspend fun updateReconciliation(draft: ReconDraft): ZillitResult<Reconciliation> =
        writeRecon(HttpVerb.Patch, "$base/reconciliations/${draft.id}", draft.payload())

    override suspend fun submitReconciliationForReview(draft: ReconDraft): ZillitResult<Unit> =
        post("$base/reconciliations/${draft.id}/submit-for-review", draft.payload())

    override suspend fun signOffReconciliation(draft: ReconDraft): ZillitResult<Unit> =
        post("$base/reconciliations/${draft.id}/sign-off", draft.payload())

    // -- settings ----------------------------------------------------------

    override suspend fun settings(): ZillitResult<CashSettings> =
        get("$base/settings", SettingsDto.serializer()).map { it.toDomain() }

    override suspend fun updateSettings(settings: CashSettings): ZillitResult<CashSettings> =
        patchSettings(
            buildJsonObject {
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
                // A boolean on the wire — the validator turns down a string.
                put("reimburse_to_payroll", JsonPrimitive(settings.reimburseToPayroll))
                put(
                    "deduction_rules",
                    buildJsonArray { settings.deductionRules.forEach { add(DeductionRuleDto.of(it)) } },
                )
                put(
                    "quick_codes",
                    buildJsonArray { settings.quickCodes.forEach { add(QuickCodeDto.of(it)) } },
                )
            },
        )

    /** `{team_members}` on its own, as the web's `persistTeam` sends it. */
    override suspend fun updateTeamMembers(members: List<CashTeamMember>): ZillitResult<CashSettings> =
        patchSettings(
            buildJsonObject { put("team_members", buildJsonArray { members.forEach { add(TeamMemberDto.of(it)) } }) },
        )

    override suspend fun updateRequestCap(cap: RequestCap): ZillitResult<CashSettings> =
        patchSettings(buildJsonObject { put("request_cap", cap.toJson()) })

    override suspend fun saveAssignmentRule(rule: CashAssignmentRule): ZillitResult<CashAssignmentRule> =
        hub.saveAssignmentRule(rule)

    override suspend fun deleteAssignmentRule(id: String): ZillitResult<Unit> = hub.deleteAssignmentRule(id)

    // -- the rest of the account hub ------------------------------------------

    override suspend fun lockedThrough(): ZillitResult<String?> = hub.lockedThrough()

    override suspend fun companies(): ZillitResult<List<CashCompany>> = hub.companies()

    override suspend fun fundRequests(): ZillitResult<List<FundRequest>> =
        get("$base/fund-requests", JsonElement.serializer()).map { data ->
            data.readList(FundRequestDto.serializer()).mapNotNull { it.toDomain() }
        }

    override suspend fun createFundRequest(fundAccount: String, currency: String?, amount: Double): ZillitResult<Unit> =
        post(
            "$base/fund-requests",
            buildJsonObject {
                put("fund_account", JsonPrimitive(fundAccount.trim()))
                putIfPresent("currency", currency)
                put("amount", JsonPrimitive(amount))
            },
        )

    override suspend fun receiveFundRequest(id: String): ZillitResult<Unit> =
        patch("$base/fund-requests/$id/receive", JsonObject(emptyMap()))

    override suspend fun cancelFundRequest(id: String): ZillitResult<Unit> =
        patch("$base/fund-requests/$id/cancel", JsonObject(emptyMap()))

    override suspend fun queryThread(batchId: String): ZillitResult<QueryThread> = hub.queryThread(batchId)

    override suspend fun sendQuery(batchId: String, threadId: String?, text: String): ZillitResult<QueryThread> =
        hub.sendQuery(batchId, threadId, text)

    override suspend fun exportFloats(format: ExportFormat): ZillitResult<ByteArray> = hub.exportFloats(format)

    override suspend fun exportReceipts(
        format: ExportFormat,
        expenseType: ExpenseType?,
        historyOnly: Boolean,
    ): ZillitResult<ByteArray> = hub.exportReceipts(format, expenseType, historyOnly)

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
    private suspend fun post(url: String, body: JsonObject?): ZillitResult<Unit> = write(HttpVerb.Post, url, body)

    private suspend fun patch(url: String, body: JsonObject?): ZillitResult<Unit> = write(HttpVerb.Patch, url, body)

    /** A write, with a stated `status: 0` read as the refusal it is. */
    private suspend fun write(verb: HttpVerb, url: String, body: JsonObject?): ZillitResult<Unit> =
        apiClient.envelope(verb = verb, url = url, module = RequestModule.ProjectUser, body = body)
            .flatMap { it.confirmed() }

    private suspend fun patchSettings(body: JsonObject): ZillitResult<CashSettings> =
        apiClient.envelope(
            verb = HttpVerb.Patch,
            url = "$base/settings",
            module = RequestModule.ProjectUser,
            body = body,
        ).flatMap { envelope ->
            envelope.confirmed().flatMap {
                // The saved document when it came back, else a fresh read.
                val data = envelope.data as? JsonObject ?: return@flatMap settings()
                ZillitResult.Success(cashLenient.decodeFromJsonElement(SettingsDto.serializer(), data).toDomain())
            }
        }

    private suspend fun writeRecon(verb: HttpVerb, url: String, body: JsonObject): ZillitResult<Reconciliation> =
        apiClient.envelope(verb = verb, url = url, module = RequestModule.ProjectUser, body = body)
            .flatMap { envelope ->
                envelope.confirmed().flatMap {
                    val data = envelope.data as? JsonObject
                        ?: return@flatMap ZillitResult.Failure(
                            ZillitError.Serialization("reconciliation came back without a body"),
                        )
                    cashLenient.decodeFromJsonElement(ReconciliationDto.serializer(), data).reconciliation()
                }
            }

    private fun ReconciliationDto.reconciliation(): ZillitResult<Reconciliation> =
        toDomain()?.let { ZillitResult.Success(it) }
            ?: ZillitResult.Failure(ZillitError.Serialization("reconciliation came back without an id"))

    private fun ReconDraft.payload(): JsonObject {
        val (start, end) = CashDates.monthBounds(year, month)
        return toPayload(start, end)
    }

    private fun claimsBody(claims: List<Claim>): JsonObject =
        buildJsonObject { put("claims", claims.toClaimsPayload()) }

    private fun tierBody(tier: TierStep): JsonObject = buildJsonObject {
        put("tier_number", JsonPrimitive(tier.tierNumber))
        put("total_tiers", JsonPrimitive(tier.totalTiers))
    }

    private fun noteBody(note: String?): JsonObject? =
        note?.takeIf { it.isNotBlank() }?.let { buildJsonObject { put("note", JsonPrimitive(it)) } }

    private fun List<String>?.toIdArray(): JsonElement =
        this?.let { ids -> JsonArray(ids.map(::JsonPrimitive)) } ?: JsonNull

    private companion object {
        const val APPROVE = "approve"
        const val REJECT = "reject"
        const val UNLIMITED = "unlimited"
        const val HTTP_OK = 200
    }

    private fun ApiEnvelope.confirmed(): ZillitResult<Unit> =
        if (status == 0) {
            ZillitResult.Failure(ZillitError.Http(status = HTTP_OK, serverMessage = message))
        } else {
            ZillitResult.Success(Unit)
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

/** The float request's custom answers, in the shape this service stores. */
private fun List<CustomFieldGroup>.toFloatJson(): JsonArray = buildJsonArray {
    forEach { group ->
        add(
            buildJsonObject {
                put("section", JsonPrimitive(group.section))
                put(
                    "fields",
                    buildJsonArray {
                        group.fields.forEach { field ->
                            add(
                                buildJsonObject {
                                    put("name", JsonPrimitive(field.name))
                                    put("label", JsonPrimitive(field.label))
                                    put("type", JsonPrimitive(field.type))
                                    field.selectionType?.let {
                                        put("selection_type", JsonPrimitive(it))
                                    }
                                    put("value", JsonPrimitive(field.value))
                                },
                            )
                        }
                    },
                )
            },
        )
    }
}
