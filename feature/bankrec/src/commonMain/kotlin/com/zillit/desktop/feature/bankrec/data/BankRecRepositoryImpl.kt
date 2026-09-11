package com.zillit.desktop.feature.bankrec.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.bankrec.domain.BankAccountRef
import com.zillit.desktop.feature.bankrec.domain.BankException
import com.zillit.desktop.feature.bankrec.domain.BankPeriod
import com.zillit.desktop.feature.bankrec.domain.BankRecRepository
import com.zillit.desktop.feature.bankrec.domain.ExceptionStatus
import com.zillit.desktop.feature.bankrec.domain.FraudAlert
import com.zillit.desktop.feature.bankrec.domain.FraudAuditEntry
import com.zillit.desktop.feature.bankrec.domain.FraudRule
import com.zillit.desktop.feature.bankrec.domain.FxPosting
import com.zillit.desktop.feature.bankrec.domain.FxVariance
import com.zillit.desktop.feature.bankrec.domain.LedgerEntryKind
import com.zillit.desktop.feature.bankrec.domain.PortalLink
import com.zillit.desktop.feature.bankrec.domain.PortalLinkDraft
import com.zillit.desktop.feature.bankrec.domain.ProjectRates
import com.zillit.desktop.feature.bankrec.domain.QuickAddForm
import com.zillit.desktop.feature.bankrec.domain.RulesSettings
import com.zillit.desktop.feature.bankrec.domain.StatementUpload
import com.zillit.desktop.feature.bankrec.domain.WorkspaceData
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The bank reconciliation service.
 *
 * Everything is on `bankreconciliationapi` except the production's bank
 * accounts, which belong to the account hub — see [BankRecRepository].
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

    override suspend fun period(id: String): ZillitResult<BankPeriod> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/periods/$id",
        serializer = PeriodDto.serializer(),
        module = RequestModule.ProjectUser,
    ).map { it.toDomain() }

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
        serializer = ValueDto.serializer(ProjectCurrenciesDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { it.value?.toDomain() ?: ProjectRates() }

    override suspend fun updatePeriodNote(id: String, note: String): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Patch,
            url = "$base/periods/$id/note",
            module = RequestModule.ProjectUser,
            body = buildJsonObject { put("note", JsonPrimitive(note)) },
        ).map { }

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
            body = buildJsonObject {
                put("period_ids", buildJsonArray { periodIds.forEach { add(JsonPrimitive(it)) } })
            },
        ).map { }

    // -- the workspace ------------------------------------------------------

    override suspend fun workspace(periodId: String): ZillitResult<WorkspaceData> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/workspace-data",
        serializer = WorkspaceDto.serializer(),
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("period_id" to periodId),
    ).map { data ->
        WorkspaceData(
            transactions = data.transactions.orEmpty().map { it.toDomain() },
            // Called `invoices` on the wire, but the list also carries quick
            // entries and posted FX variances.
            ledger = data.invoices.orEmpty().map { it.toDomain() },
        )
    }

    override suspend fun matchTransaction(
        id: String,
        entityId: String,
        kind: LedgerEntryKind,
    ): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Post,
        url = "$base/$id/match",
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put("matched_to_id", JsonPrimitive(entityId))
            put("matched_to_type", JsonPrimitive(kind.wire))
        },
    ).map { }

    override suspend fun rerunAutoMatch(periodId: String): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Post,
        url = "$base/rerun-match/$periodId",
        module = RequestModule.ProjectUser,
    ).map { }

    override suspend fun importStatement(
        attachment: StatementUpload,
        bankAccountId: String?,
        periodId: String?,
    ): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Post,
        url = "$base/import-statement",
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put("attachment", attachment.toJson())
            bankAccountId?.takeIf { it.isNotBlank() }?.let { put("bank_account_id", JsonPrimitive(it)) }
            periodId?.takeIf { it.isNotBlank() }?.let { put("period_id", JsonPrimitive(it)) }
        },
    ).map { }

    // -- exceptions ---------------------------------------------------------

    override suspend fun exceptions(periodId: String?): ZillitResult<List<BankException>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$base/exceptions",
            serializer = ListSerializer(ExceptionDto.serializer()),
            module = RequestModule.ProjectUser,
            queryParameters = periodQuery(periodId),
        ).map { rows -> rows.map { it.toDomain() }.filter { it.id.isNotBlank() } }

    override suspend fun setExceptionStatus(
        id: String,
        status: ExceptionStatus,
        notes: String,
    ): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Patch,
        url = "$base/exceptions/$id/status",
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put("status", JsonPrimitive(status.wire))
            put("notes", JsonPrimitive(notes))
        },
    ).map { }

    override suspend fun quickAddException(id: String, form: QuickAddForm): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = "$base/exceptions/$id/quick-add",
            module = RequestModule.ProjectUser,
            body = form.toJson(),
        ).map { }

    // -- fraud --------------------------------------------------------------

    override suspend fun fraudAlerts(periodId: String?): ZillitResult<List<FraudAlert>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$base/fraud-alerts",
            serializer = ListSerializer(FraudAlertDto.serializer()),
            module = RequestModule.ProjectUser,
            queryParameters = periodQuery(periodId),
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

    override suspend fun fraudAuditLog(periodId: String?): ZillitResult<List<FraudAuditEntry>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$base/fraud-alerts/audit-logs",
            serializer = ListSerializer(FraudAuditDto.serializer()),
            module = RequestModule.ProjectUser,
            queryParameters = periodQuery(periodId),
        ).map { rows -> rows.map { it.toDomain() } }

    // -- FX -----------------------------------------------------------------

    override suspend fun fxVariances(periodId: String?): ZillitResult<List<FxVariance>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$base/fx-variances",
            serializer = ListSerializer(FxVarianceDto.serializer()),
            module = RequestModule.ProjectUser,
            queryParameters = periodQuery(periodId),
        ).map { rows -> rows.map { it.toDomain() }.filter { it.id.isNotBlank() } }

    override suspend fun postFxVariance(id: String, posting: FxPosting): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = "$base/fx-variances/$id/post",
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put("nominal_code", JsonPrimitive(posting.nominalCode))
                put("cost_centre", JsonPrimitive(posting.costCentre))
            },
        ).map { }

    override suspend fun postAllFxVariances(periodId: String): ZillitResult<Unit> =
        apiClient.envelope(
            verb = HttpVerb.Post,
            url = "$base/fx-variances/post-all",
            module = RequestModule.ProjectUser,
            // camelCase, alone among this service's bodies. The web sends
            // `{ periodId }` here and `period_id` everywhere else.
            body = buildJsonObject { put("periodId", JsonPrimitive(periodId)) },
        ).map { }

    // -- rules --------------------------------------------------------------

    override suspend fun rulesSettings(): ZillitResult<RulesSettings> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/rules-settings",
        serializer = RulesSettingsDto.serializer(),
        module = RequestModule.ProjectUser,
    ).map { it.toDomain() }

    override suspend fun saveAutoMatchRules(rules: Map<String, Boolean>): ZillitResult<Unit> =
        apiClient.envelope(
            // PUT, and only the half being saved. Sending both halves would
            // carry the other section's unsaved edits with this one's save.
            verb = HttpVerb.Put,
            url = "$base/rules-settings",
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put(
                    "auto_match_rules",
                    buildJsonObject { rules.forEach { (key, on) -> put(key, JsonPrimitive(on)) } },
                )
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

    override suspend fun portalLinks(): ZillitResult<List<PortalLink>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/portal-links",
        serializer = ListSerializer(PortalLinkDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { rows -> rows.map { it.toDomain() }.filter { it.id.isNotBlank() } }

    override suspend fun createPortalLink(draft: PortalLinkDraft): ZillitResult<PortalLink> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "$base/portal-links",
            serializer = PortalLinkDto.serializer(),
            module = RequestModule.ProjectUser,
            body = draft.toJson(),
        ).map { it.toDomain() }

    override suspend fun updatePortalLink(
        id: String,
        draft: PortalLinkDraft,
    ): ZillitResult<PortalLink> = apiClient.request(
        verb = HttpVerb.Patch,
        url = "$base/portal-links/$id",
        serializer = PortalLinkDto.serializer(),
        module = RequestModule.ProjectUser,
        body = draft.toJson(),
    ).map { it.toDomain() }

    override suspend fun revokePortalLink(id: String): ZillitResult<Unit> = apiClient.envelope(
        // A PATCH with no body — revoking is a state change on the link, not a
        // deletion: the audit trail keeps who shared what with whom.
        verb = HttpVerb.Patch,
        url = "$base/portal-links/$id/revoke",
        module = RequestModule.ProjectUser,
    ).map { }

    /** Every list route takes the same optional period filter. */
    private fun periodQuery(periodId: String?): Map<String, String> =
        periodId?.takeIf { it.isNotBlank() }?.let { mapOf("period_id" to it) } ?: emptyMap()
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

private fun QuickAddForm.toJson(): JsonObject = buildJsonObject {
    put("nominal_code", JsonPrimitive(nominalCode))
    put("cost_centre", JsonPrimitive(costCentre))
    put("description", JsonPrimitive(description))
    if (taxTypeId.isNotBlank()) put("tax_type_id", JsonPrimitive(taxTypeId))
    taxRate?.let { put("tax_rate", JsonPrimitive(it)) }
}

private fun PortalLinkDraft.toJson(): JsonObject = buildJsonObject {
    put("recipient_name", JsonPrimitive(recipientName.trim()))
    put("recipient_email", JsonPrimitive(recipientEmail.trim()))
    put("org_type", JsonPrimitive(orgType.wire))
    if (bankAccountId.isNotBlank()) put("bank_account_id", JsonPrimitive(bankAccountId))
    put("period_id", JsonPrimitive(periodId))
    put(
        "permissions",
        buildJsonArray { permissions.forEach { add(JsonPrimitive(it.wire)) } },
    )
    put("expires_in", JsonPrimitive(expiry.wire))
    put("notify_on_view", JsonPrimitive(notifyOnView.wire))
}

private fun fraudRulesJson(rules: Map<String, FraudRule>): JsonObject = buildJsonObject {
    rules.forEach { (key, rule) ->
        // A check with a threshold is an object; one without is a bare
        // boolean. Sending an object for a boolean rule stores a shape the
        // engine does not read, and the check silently stops running.
        if (rule.amount == null) {
            put(key, JsonPrimitive(rule.enabled))
        } else {
            put(
                key,
                buildJsonObject {
                    put("enabled", JsonPrimitive(rule.enabled))
                    put("amount", JsonPrimitive(rule.amount))
                },
            )
        }
    }
}
