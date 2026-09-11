package com.zillit.desktop.feature.accounthub.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.accounthub.domain.AgreementDocument
import com.zillit.desktop.feature.accounthub.domain.BudgetImportMeta
import com.zillit.desktop.feature.accounthub.domain.BudgetUpload
import com.zillit.desktop.feature.accounthub.domain.CoaImportMode
import com.zillit.desktop.feature.accounthub.domain.ParsedBudget
import com.zillit.desktop.feature.accounthub.domain.ParsedCode
import com.zillit.desktop.feature.accounthub.domain.ParsedSection
import com.zillit.desktop.feature.accounthub.domain.ParsedUncoded
import com.zillit.desktop.feature.accounthub.domain.BibleQuery
import com.zillit.desktop.feature.accounthub.domain.BibleReport
import com.zillit.desktop.feature.accounthub.domain.BudgetLine
import com.zillit.desktop.feature.accounthub.domain.BudgetVersion
import com.zillit.desktop.feature.accounthub.domain.PeriodLock
import com.zillit.desktop.feature.accounthub.domain.TrialBalance
import com.zillit.desktop.feature.accounthub.domain.TrialBalanceQuery
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The console's read-only surfaces: the two reports, the close boundary and
 * the budget.
 *
 * Its own class because they are its own concern — none of them is the account
 * hub's data. The trial balance, the bible and the lock belong to the
 * cost-report service, and the budget to the hub's own `/budgets` route rather
 * than its project settings. The repository delegates rather than growing a
 * fourth service's worth of calls inline.
 */
internal class HubReportSource(
    private val apiClient: ApiClient,
    config: AppConfig,
) {

    private val hubBase = "${config.baseUrl(ZillitService.AccountHub)}/api/v2/account-hub"

    /** The cost-report service, which owns the two reports and the lock. */
    private val costReportBase = "${config.apiV2(ZillitService.CostReport).trimEnd('/')}/cost-reports"

    suspend fun trialBalance(query: TrialBalanceQuery): ZillitResult<TrialBalance> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$costReportBase/trial-balance",
            serializer = ValueDto.serializer(ListSerializer(TrialBalanceRowDto.serializer())),
            module = RequestModule.ProjectUser,
            queryParameters = buildMap {
                put("period_start", query.periodStartMillis.toString())
                put("period_end", query.periodEndMillis.toString())
                query.accountType.takeIf { it.isNotBlank() }?.let { put("account_type", it) }
                query.currency.takeIf { it.isNotBlank() }?.let { put("currency", it) }
                // Sent even when false: leaving it out lets the server's own
                // default decide, and the two have disagreed before.
                put("zero_accounts", query.includeZeroAccounts.toString())
            },
        ).map { row -> TrialBalance(row.value.orEmpty().map { it.toDomain() }) }

    suspend fun bibleReport(query: BibleQuery): ZillitResult<BibleReport> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$costReportBase/bible",
        serializer = ValueDto.serializer(BibleReportDto.serializer()),
        module = RequestModule.ProjectUser,
        queryParameters = buildMap {
            put("period_start", query.periodStartMillis.toString())
            put("period_end", query.periodEndMillis.toString())
            query.source.takeIf { it.isNotBlank() }?.let { put("source", it) }
            query.accountType.takeIf { it.isNotBlank() }?.let { put("account_type", it) }
            query.currency.takeIf { it.isNotBlank() }?.let { put("currency", it) }
            // Only when false: the server defaults to true, and restating it
            // would be this client asserting a default it does not own.
            if (!query.includeOpenPurchaseOrders) put("include_open_pos", "false")
        },
    ).map { it.value?.toDomain() ?: BibleReport() }

    suspend fun periodLock(): ZillitResult<PeriodLock> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$costReportBase/lock-period",
        serializer = ValueDto.serializer(PeriodLockDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { it.value?.toDomain() ?: PeriodLock() }

    suspend fun closePeriod(asOfMillis: Long): ZillitResult<PeriodLock> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$costReportBase/lock-period",
        serializer = ValueDto.serializer(PeriodLockDto.serializer()),
        module = RequestModule.ProjectUser,
        body = buildJsonObject { put("as_of", JsonPrimitive(asOfMillis)) },
    ).map { it.value?.toDomain() ?: PeriodLock() }

    suspend fun budgetVersions(): ZillitResult<List<BudgetVersion>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$hubBase/budgets",
        serializer = ValueDto.serializer(ListSerializer(BudgetVersionDto.serializer())),
        module = RequestModule.ProjectUser,
    ).map { row -> row.value.orEmpty().map { it.toDomain() }.filter { it.id.isNotBlank() } }

    suspend fun budgetLines(versionId: String): ZillitResult<List<BudgetLine>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$hubBase/budgets/$versionId/lines",
            serializer = ValueDto.serializer(ListSerializer(BudgetLineDto.serializer())),
            module = RequestModule.ProjectUser,
        ).map { row -> row.value.orEmpty().map { it.toDomain() }.filter { it.id.isNotBlank() } }

    // -- budget import ------------------------------------------------------

    suspend fun dryRunBudgetImport(
        document: AgreementDocument,
    ): ZillitResult<Pair<ParsedBudget, BudgetUpload>> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$hubBase/budgets/import:dry-run",
        serializer = ValueDto.serializer(BudgetDryRunDto.serializer()),
        module = RequestModule.ProjectUser,
        body = buildJsonObject { put("attachment", document.toImportJson()) },
    ).map { row ->
        val answer = row.value
        val parsed = answer?.parsed?.toDomain() ?: ParsedBudget()
        val upload = BudgetUpload(
            uploadId = answer?.upload?.id ?: answer?.uploadId.orEmpty(),
            fileName = answer?.upload?.fileName?.takeIf { it.isNotBlank() }
                ?: answer?.attachment?.toDomain()?.name
                ?: document.name,
            detectedFormat = answer?.upload?.detectedFormat ?: answer?.detectedFormat.orEmpty(),
            // The server's own pointer where it echoes one, so the commit
            // names the file the dry run actually read.
            document = answer?.attachment?.toDomain()?.takeIf { it.media.isNotBlank() } ?: document,
        )
        parsed to upload
    }

    suspend fun commitBudgetImport(
        upload: BudgetUpload,
        parsed: ParsedBudget,
        meta: BudgetImportMeta,
        mode: CoaImportMode,
    ): ZillitResult<BudgetVersion?> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$hubBase/budgets/import:commit",
        serializer = ValueDto.serializer(BudgetImportResultDto.serializer()),
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            // Both identifiers: the audit-row id an older backend wants, and
            // the document pointer a newer one locates the upload by. Each
            // ignores the other.
            put("upload_id", upload.uploadId.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)
            put("attachment", upload.document?.toImportJson() ?: JsonNull)
            put("structure", parsed.toJson())
            put(
                "budget_meta",
                buildJsonObject {
                    put("version", JsonPrimitive(meta.version.trim()))
                    put("label", JsonPrimitive(meta.label.trim()))
                    put("description", JsonPrimitive(meta.description.trim()))
                },
            )
            // Always explicit. The server defaults to append, and letting it
            // decide would make the choice on screen a suggestion.
            put("coa_mode", JsonPrimitive(mode.wire))
        },
    ).map { it.value?.budget?.toDomain() }
}

/** The stored file, as the import routes name it. */
private fun AgreementDocument.toImportJson(): JsonElement = buildJsonObject {
    put("media", JsonPrimitive(media))
    put("bucket", JsonPrimitive(bucket))
    put("region", JsonPrimitive(region))
    put("name", JsonPrimitive(name))
    put("content_type", JsonPrimitive(contentType))
    put("content_subtype", JsonPrimitive(contentSubtype))
}

/**
 * The reviewed parse, in the shape the commit takes.
 *
 * Sent back whole rather than as a diff: the server's contract is the tree it
 * handed over, edits included, and a partial body would leave it merging two
 * ideas of the same import.
 */
private fun ParsedBudget.toJson(): JsonElement = buildJsonObject {
    put("currency", JsonPrimitive(currency))
    put("sections", buildJsonArray { sections.forEach { add(it.toJson()) } })
    put("headers", buildJsonArray { headers.forEach { add(it.toJson(header = true)) } })
    put("nominals", buildJsonArray { nominals.forEach { add(it.toJson(header = false)) } })
    put("uncodedItems", buildJsonArray { uncoded.forEach { add(it.toJson()) } })
    put("warnings", buildJsonArray { warnings.forEach { add(JsonPrimitive(it)) } })
    serverTotal?.let { put("grandTotal", JsonPrimitive(it)) }
}

private fun ParsedSection.toJson(): JsonElement = buildJsonObject {
    put("section_id", JsonPrimitive(id))
    put("name", JsonPrimitive(name))
}

private fun ParsedCode.toJson(header: Boolean): JsonElement = buildJsonObject {
    put("code", JsonPrimitive(code))
    put("name", JsonPrimitive(name))
    put("amount", JsonPrimitive(amount))
    if (header) put("section_id", JsonPrimitive(sectionId)) else put("parent_code", JsonPrimitive(parentCode))
}

private fun ParsedUncoded.toJson(): JsonElement = buildJsonObject {
    put("name", JsonPrimitive(name))
    put("amount", JsonPrimitive(amount))
}
