package com.zillit.desktop.feature.bankrec.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.bankrec.domain.AuditExportFormat
import com.zillit.desktop.feature.bankrec.domain.AuditFilters
import com.zillit.desktop.feature.bankrec.domain.BankAccountRef
import com.zillit.desktop.feature.bankrec.domain.BankException
import com.zillit.desktop.feature.bankrec.domain.BankPeriod
import com.zillit.desktop.feature.bankrec.domain.BankRecRepository
import com.zillit.desktop.feature.bankrec.domain.CompanyDetails
import com.zillit.desktop.feature.bankrec.domain.ExceptionStatus
import com.zillit.desktop.feature.bankrec.domain.FraudAlert
import com.zillit.desktop.feature.bankrec.domain.FraudAuditEntry
import com.zillit.desktop.feature.bankrec.domain.FraudRule
import com.zillit.desktop.feature.bankrec.domain.FxPosting
import com.zillit.desktop.feature.bankrec.domain.FxVariance
import com.zillit.desktop.feature.bankrec.domain.ImportResult
import com.zillit.desktop.feature.bankrec.domain.LedgerEntryKind
import com.zillit.desktop.feature.bankrec.domain.PortalLink
import com.zillit.desktop.feature.bankrec.domain.PortalLinkDraft
import com.zillit.desktop.feature.bankrec.domain.PortalPreview
import com.zillit.desktop.feature.bankrec.domain.ProjectRates
import com.zillit.desktop.feature.bankrec.domain.QuickAddForm
import com.zillit.desktop.feature.bankrec.domain.RulesSettings
import com.zillit.desktop.feature.bankrec.domain.StatementUpload
import com.zillit.desktop.feature.bankrec.domain.WorkspaceData
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A signed POST whose answer is a file rather than an envelope.
 *
 * The export routes stream bytes, and `ApiClient` only speaks envelopes, so the
 * host supplies this. Absent leaves every export unavailable, and says so.
 */
fun interface BankRecBinaryPost {
    suspend fun post(url: String, body: JsonObject): ZillitResult<ByteArray>
}

/**
 * The bank reconciliation service.
 *
 * Everything is on `bankreconciliationapi` except the production's bank
 * accounts and currencies, which belong to the account hub — see
 * [BankRecRepository].
 *
 * A note for anyone verifying routes against this host: **the usual 406 probe
 * does not work here.** Its moduledata guard runs before routing, so an
 * invented path answers 406 exactly as a real one does. The web's own client
 * is the authority for what exists.
 */
@Suppress("TooManyFunctions") // One override per endpoint; see the interface.
class BankRecRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    private val binaryPost: BankRecBinaryPost? = null,
) : BankRecRepository {

    private val base = "${config.baseUrl(ZillitService.BankReconciliation)}/api/v2/bank-reconciliations"

    /** The hub's, not this service's — the account is a production setting. */
    private val hubBase = "${config.baseUrl(ZillitService.AccountHub)}/api/v2/account-hub"

    // -- periods ------------------------------------------------------------

    override suspend fun periods(): ZillitResult<List<BankPeriod>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/periods",
        serializer = ListSerializer(PeriodDto.serializer()),
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("sort" to "created_at", "order" to "desc"),
    ).map { rows -> rows.map { it.toDomain() }.filter { it.id.isNotBlank() } }

    override suspend fun bankAccounts(): ZillitResult<List<BankAccountRef>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$hubBase/bank-accounts",
        serializer = ListSerializer(BankAccountDto.serializer()),
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("entity_type" to "production"),
    ).map { rows -> rows.map { it.toDomain() }.filter { it.id.isNotBlank() } }

    override suspend fun projectCurrencies(): ZillitResult<ProjectRates> = apiClient.request(
        verb = HttpVerb.Get,
        // Wrapped under `value`, like every project-settings slice. Reading
        // `data.currencies` finds nothing and reports no error at all.
        url = "$hubBase/project-settings/project-currencies",
        serializer = ValueDto.serializer(JsonElement.serializer()),
        module = RequestModule.ProjectUser,
    ).map { it.value.toProjectRates() }

    override suspend fun signOffPeriod(id: String, note: String): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = "$base/periods/$id/sign-off",
            module = RequestModule.ProjectUser,
            body = buildJsonObject { put("sign_off_notes", JsonPrimitive(note)) },
        ).map { }

    override suspend fun deletePeriods(periodIds: List<String>): ZillitResult<Unit> =
        apiClient.envelope(
            // POST, not DELETE: the delete carries a body of ids, and the
            // shared client's delete takes none.
            verb = HttpVerb.Post,
            url = "$base/periods/bulk-delete",
            module = RequestModule.ProjectUser,
            body = buildJsonObject { put("period_ids", periodIds.toJsonArray()) },
        ).map { }

    override suspend fun exportPeriodsPdf(periodIds: List<String>, company: CompanyDetails): ZillitResult<ByteArray> =
        exportBytes(
            "$base/periods/export-pdf",
            buildJsonObject {
                put("period_ids", periodIds.toJsonArray())
                putCompany(company)
            },
        )

    // -- the workspace ------------------------------------------------------

    override suspend fun workspace(periodId: String): ZillitResult<WorkspaceData> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/workspace-data",
        serializer = WorkspaceDto.serializer(),
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("period_id" to periodId),
    ).map { data ->
        WorkspaceData(
            transactions = data.transactions.orEmpty().map { it.toDomain() }.filter { it.id.isNotBlank() },
            // Called `invoices` on the wire, but the list also carries quick
            // entries and posted FX variances.
            ledger = data.invoices.orEmpty().map { it.toDomain() }.filter { it.id.isNotBlank() },
            closingZillit = data.closingZillit.asDouble(),
        )
    }

    override suspend fun matchTransaction(id: String, entityId: String, kind: LedgerEntryKind): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = "$base/$id/match",
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put("matched_to_id", JsonPrimitive(entityId))
                // An entry of a kind this client does not know is matched as an
                // invoice, which is what the web sends when it has no type.
                put("matched_to_type", JsonPrimitive(if (kind == LedgerEntryKind.Other) "invoice" else kind.wire))
            },
        ).map { }

    override suspend fun rerunAutoMatch(periodId: String): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Post,
        url = "$base/rerun-match/$periodId",
        module = RequestModule.ProjectUser,
    ).map { }

    override suspend fun importStatement(
        attachment: StatementUpload,
        bankAccountId: String,
    ): ZillitResult<ImportResult> = apiClient.requestOrNull(
        verb = HttpVerb.Post,
        url = "$base/import-statement",
        serializer = ImportResultDto.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put("attachment", attachment.toJson())
            if (bankAccountId.isNotBlank()) put("bank_account_id", JsonPrimitive(bankAccountId))
        },
    ).map { it?.toDomain() ?: ImportResult() }

    // -- exceptions ---------------------------------------------------------

    override suspend fun exceptions(): ZillitResult<List<BankException>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/exceptions",
        serializer = ListSerializer(ExceptionDto.serializer()),
        module = RequestModule.ProjectUser,
        queryParameters = listQuery(sort = "created_at"),
    ).map { rows -> rows.map { it.toDomain() }.filter { it.id.isNotBlank() } }

    override suspend fun setExceptionStatus(id: String, status: ExceptionStatus): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Patch,
            url = "$base/exceptions/$id/status",
            module = RequestModule.ProjectUser,
            // The web passes no notes, and an undefined key never reaches the wire.
            body = buildJsonObject { put("status", JsonPrimitive(status.wire)) },
        ).map { }

    override suspend fun quickAddException(id: String, form: QuickAddForm, fromWorkspace: Boolean): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = "$base/exceptions/$id/quick-add",
            module = RequestModule.ProjectUser,
            body = form.toJson(fromWorkspace),
        ).map { }

    override suspend fun exportExceptionsPdf(periodId: String, company: CompanyDetails): ZillitResult<ByteArray> =
        exportBytes(
            "$base/exceptions/export-pdf",
            buildJsonObject {
                put("period_id", JsonPrimitive(periodId))
                putCompany(company)
            },
        )

    // -- fraud --------------------------------------------------------------

    override suspend fun fraudAlerts(): ZillitResult<List<FraudAlert>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/fraud-alerts",
        serializer = ListSerializer(FraudAlertDto.serializer()),
        module = RequestModule.ProjectUser,
        // Riskiest first, as the web lists them.
        queryParameters = listQuery(sort = "risk_score"),
    ).map { rows -> rows.map { it.toDomain() }.filter { it.id.isNotBlank() } }

    override suspend fun escalateFraudAlert(id: String): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Post,
        url = "$base/fraud-alerts/$id/escalate",
        module = RequestModule.ProjectUser,
    ).map { }

    override suspend fun dismissFraudAlert(id: String): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Post,
        url = "$base/fraud-alerts/$id/dismiss",
        module = RequestModule.ProjectUser,
    ).map { }

    override suspend fun fraudAuditLog(): ZillitResult<List<FraudAuditEntry>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/fraud-alerts/audit-logs",
        serializer = ListSerializer(FraudAuditDto.serializer()),
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("per_page" to PER_PAGE),
    ).map { rows -> rows.map { it.toDomain() } }

    override suspend fun exportAuditLog(
        format: AuditExportFormat,
        filters: AuditFilters,
        company: CompanyDetails,
    ): ZillitResult<ByteArray> = exportBytes(
        "$base/fraud-alerts/audit-logs/${format.wire}",
        // camelCase here too, alone with the quick add: the web posts its
        // project details object as it stands, filters nested beside it.
        buildJsonObject {
            put("projectName", JsonPrimitive(company.projectName))
            put("companyName", JsonPrimitive(company.companyName))
            put("companyAddress", JsonPrimitive(company.companyAddress))
            put("companyEmail", JsonPrimitive(company.companyEmail))
            put(
                "filters",
                buildJsonObject {
                    filters.bankAccountId.ifNotBlank { put("bank_account_id", JsonPrimitive(it)) }
                    filters.periodId.ifNotBlank { put("period_id", JsonPrimitive(it)) }
                    filters.performedBy.ifNotBlank { put("performed_by", JsonPrimitive(it)) }
                    filters.action.ifNotBlank { put("action", JsonPrimitive(it)) }
                },
            )
        },
    )

    // -- FX -----------------------------------------------------------------

    override suspend fun fxVariances(): ZillitResult<List<FxVariance>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/fx-variances",
        serializer = ListSerializer(FxVarianceDto.serializer()),
        module = RequestModule.ProjectUser,
        queryParameters = listQuery(sort = "created_at"),
    ).map { rows -> rows.map { it.toDomain() }.filter { it.id.isNotBlank() } }

    override suspend fun postFxVariance(id: String, posting: FxPosting): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = "$base/fx-variances/$id/post",
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put("nominal_code", JsonPrimitive(posting.nominalCode))
                put("cost_centre", JsonPrimitive(posting.costCentre))
                // Both rates ride the body when the screen has them — the
                // budget rate especially, since it comes from project settings
                // and the service has no other way to know it.
                posting.budgetRate?.let { put("budget_rate", JsonPrimitive(it)) }
                posting.bankRate?.let { put("bank_rate", JsonPrimitive(it)) }
            },
        ).map { }

    // -- rules --------------------------------------------------------------

    override suspend fun rulesSettings(): ZillitResult<RulesSettings> = apiClient.requestOrNull(
        verb = HttpVerb.Get,
        url = "$base/rules-settings",
        serializer = RulesSettingsDto.serializer(),
        module = RequestModule.ProjectUser,
    ).map { it?.toDomain() ?: RulesSettings() }

    override suspend fun saveAutoMatchRules(rules: Map<String, Boolean>): ZillitResult<Unit> =
        apiClient.envelope(
            // PUT, and only the half being saved. Sending both halves would
            // carry the other section's unsaved edits with this one's save.
            verb = HttpVerb.Put,
            url = "$base/rules-settings",
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put("auto_match_rules", buildJsonObject { rules.forEach { (key, on) -> put(key, JsonPrimitive(on)) } })
            },
        ).map { }

    override suspend fun saveFraudRules(rules: Map<String, FraudRule>): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Put,
            url = "$base/rules-settings",
            module = RequestModule.ProjectUser,
            body = buildJsonObject { put("fraud_detections", fraudRulesJson(rules)) },
        ).map { }

    // -- shared links -------------------------------------------------------

    override suspend fun portalPreview(periodId: String, bankAccountId: String): ZillitResult<PortalPreview?> =
        apiClient.requestOrNull(
            verb = HttpVerb.Get,
            url = "$base/portal-links/preview",
            serializer = PortalPreviewDto.serializer(),
            module = RequestModule.ProjectUser,
            // An empty account id is dropped rather than sent, as the web's
            // query builder drops it.
            queryParameters = buildMap {
                put("period_id", periodId)
                if (bankAccountId.isNotBlank()) put("bank_account_id", bankAccountId)
            },
        ).map { it?.toDomain() }

    override suspend fun portalLinks(): ZillitResult<List<PortalLink>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/portal-links",
        serializer = ListSerializer(PortalLinkDto.serializer()),
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("per_page" to LINKS_PER_PAGE, "sort" to "created_at", "order" to "desc"),
    ).map { rows -> rows.map { it.toDomain() }.filter { it.id.isNotBlank() } }

    override suspend fun createPortalLink(draft: PortalLinkDraft): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Post,
        url = "$base/portal-links",
        module = RequestModule.ProjectUser,
        body = draft.toJson(),
    ).map { }

    override suspend fun updatePortalLink(id: String, draft: PortalLinkDraft): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Patch,
            url = "$base/portal-links/$id",
            module = RequestModule.ProjectUser,
            body = draft.toJson(),
        ).map { }

    override suspend fun revokePortalLink(id: String): ZillitResult<Unit> = apiClient.envelope(
        // A PATCH with no body — revoking is a state change on the link, not a
        // deletion: the audit trail keeps who shared what with whom.
        verb = HttpVerb.Patch,
        url = "$base/portal-links/$id/revoke",
        module = RequestModule.ProjectUser,
    ).map { }

    // -- plumbing -----------------------------------------------------------

    private suspend fun exportBytes(url: String, body: JsonObject): ZillitResult<ByteArray> =
        binaryPost?.post(url, body)
            ?: ZillitResult.Failure(ZillitError.Validation("This installation cannot download exported files."))

    /**
     * The three lists, newest (or riskiest) first, at the service's page cap.
     *
     * 200 is the most the service returns in one page; past it a list
     * truncates, which is the same ceiling the web reads under. Without a
     * `per_page` the server's small default page left rows off the end of a
     * busy period with nothing on screen to say so.
     */
    private fun listQuery(sort: String): Map<String, Any?> =
        mapOf("per_page" to PER_PAGE, "sort" to sort, "order" to "desc")

    private companion object {
        const val PER_PAGE = 200
        const val LINKS_PER_PAGE = 100
    }
}

private inline fun String.ifNotBlank(block: (String) -> Unit) {
    if (isNotBlank()) block(this)
}

private fun List<String>.toJsonArray() = buildJsonArray { forEach { add(JsonPrimitive(it)) } }

private fun kotlinx.serialization.json.JsonObjectBuilder.putCompany(company: CompanyDetails) {
    put("company_name", JsonPrimitive(company.companyName))
    put("project_name", JsonPrimitive(company.projectName))
    put("company_address", JsonPrimitive(company.companyAddress))
}

private fun StatementUpload.toJson(): JsonObject = buildJsonObject {
    put("media", JsonPrimitive(media))
    put("bucket", JsonPrimitive(bucket))
    put("region", JsonPrimitive(region))
    put("name", JsonPrimitive(fileName))
    put("content_type", JsonPrimitive(contentType))
    put("content_subtype", JsonPrimitive(contentSubtype))
    put("caption", JsonPrimitive(""))
}

/**
 * The quick-add body, in the web's camelCase — see [QuickAddForm].
 *
 * The exceptions dialog posts its whole form; the workspace drawer posts a
 * subset and turns a blank effective date into null.
 */
internal fun QuickAddForm.toJson(fromWorkspace: Boolean): JsonObject = buildJsonObject {
    if (!fromWorkspace) {
        put("date", JsonPrimitive(date))
        put("invoiceNumber", JsonPrimitive(invoiceNumber))
    }
    put("amount", JsonPrimitive(amount))
    put("description", JsonPrimitive(description))
    put(
        "effectiveDate",
        if (fromWorkspace && effectiveDate.isBlank()) JsonNull else JsonPrimitive(effectiveDate),
    )
    put("vatType", JsonPrimitive(vatType))
    put("vatRate", vatRate?.let { JsonPrimitive(it.wholeOrDecimal()) } ?: JsonNull)
    put("nominal", JsonPrimitive(nominal))
    put("costCentre", JsonPrimitive(costCentre))
}

/** `20`, not `20.0` — a whole number goes on the wire as the web's number would. */
private fun Double.wholeOrDecimal(): Number = if (this % 1.0 == 0.0) toLong() else this

internal fun PortalLinkDraft.toJson(): JsonObject = buildJsonObject {
    put("recipient_name", JsonPrimitive(recipientName.trim()))
    put("recipient_email", JsonPrimitive(recipientEmail.trim()))
    put("org_type", JsonPrimitive(orgType.wire))
    // Sent even when blank: "all accounts" is a choice, and on an edit an
    // omitted key would keep whichever account the link had before.
    put("bank_account_id", JsonPrimitive(bankAccountId))
    put("period_id", JsonPrimitive(periodId))
    put("permissions", buildJsonArray { permissions.forEach { add(JsonPrimitive(it.wire)) } })
    put("expires_in", JsonPrimitive(expiry.wire))
    put("notify_on_view", JsonPrimitive(notifyOnView.wire))
}

internal fun fraudRulesJson(rules: Map<String, FraudRule>): JsonObject = buildJsonObject {
    rules.forEach { (key, rule) ->
        // A check with a threshold is an object; one without is a bare
        // boolean. Sending an object for a boolean rule stores a shape the
        // engine does not read, and the check silently stops running.
        val amount = rule.amount
        if (amount == null) {
            put(key, JsonPrimitive(rule.enabled))
        } else {
            put(
                key,
                buildJsonObject {
                    put("enabled", JsonPrimitive(rule.enabled))
                    put("amount", JsonPrimitive(amount.wholeOrDecimal()))
                },
            )
        }
    }
}
