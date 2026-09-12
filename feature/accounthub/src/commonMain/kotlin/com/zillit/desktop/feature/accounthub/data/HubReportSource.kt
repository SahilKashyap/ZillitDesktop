package com.zillit.desktop.feature.accounthub.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
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
import com.zillit.desktop.feature.accounthub.domain.ClosingPackage
import com.zillit.desktop.feature.accounthub.domain.PeriodLock
import com.zillit.desktop.feature.accounthub.domain.TrialBalance
import com.zillit.desktop.feature.accounthub.domain.TrialBalanceQuery
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

    /**
     * The whole envelope rather than its `data`, as the bible reads it: a 200
     * with `status: 0` is a refusal the web's client throws on, and the page
     * says it could not load. Through `request` it would read as an empty
     * ledger — "No account balances" for a report that never ran.
     */
    suspend fun trialBalance(query: TrialBalanceQuery): ZillitResult<TrialBalance> =
        apiClient.envelope(
            verb = HttpVerb.Get,
            url = "$costReportBase/trial-balance",
            module = RequestModule.ProjectUser,
            queryParameters = buildMap {
                put("period_start", query.periodStartMillis.toString())
                put("period_end", query.periodEndMillis.toString())
                query.accountStart.takeIf { it.isNotBlank() }?.let { put("account_start", it.trim()) }
                query.accountEnd.takeIf { it.isNotBlank() }?.let { put("account_end", it.trim()) }
                query.accountType.takeIf { it.isNotBlank() }?.let { put("account_type", it) }
                query.companyId.takeIf { it.isNotBlank() }?.let { put("company_id", it) }
                query.currency.takeIf { it.isNotBlank() }?.let { put("currency", it) }
                // Sent even when false: leaving it out lets the server's own
                // default decide, and the two have disagreed before.
                put("zero_accounts", query.includeZeroAccounts.toString())
            },
        ).flatMap { envelope ->
            if (envelope.status == TRIAL_BALANCE_REFUSED) {
                ZillitResult.Failure(ZillitError.Http(status = HTTP_OK, serverMessage = envelope.message))
            } else {
                val rows = envelope.data?.rows(TrialBalanceRowDto.serializer()).orEmpty()
                ZillitResult.Success(TrialBalance(rows.map { it.toDomain() }))
            }
        }

    /**
     * The whole envelope rather than its `data`: a refusal can arrive as a 200
     * with `status: 0`, which the web's client throws on and shows as the run's
     * error. The query string and both body shapes are in BibleReportDtos.kt,
     * where they are tested.
     */
    suspend fun bibleReport(query: BibleQuery): ZillitResult<BibleReport> = apiClient.envelope(
        verb = HttpVerb.Get,
        url = "$costReportBase/bible",
        module = RequestModule.ProjectUser,
        queryParameters = query.toQueryParameters(),
    ).flatMap { it.toBibleReport() }

    /**
     * The close boundary, read the way the web's `useCrLock` reads it.
     *
     * Two readings of one row: the lock route, and `last_cr_locked_date` on the
     * combined project-settings document. The later date wins, because the lock
     * only moves forward — and the settings reading stands in when the route
     * fails, which the live route has done on a date stored as a string. Only
     * when neither answers is the route's failure returned.
     */
    suspend fun periodLock(): ZillitResult<PeriodLock> = coroutineScope {
        val route = async {
            apiClient.request(
                verb = HttpVerb.Get,
                url = "$costReportBase/lock-period",
                serializer = JsonElement.serializer(),
                module = RequestModule.ProjectUser,
            )
        }
        val settings = async {
            apiClient.request(
                verb = HttpVerb.Get,
                url = "$hubBase/project-settings",
                serializer = JsonElement.serializer(),
                module = RequestModule.ProjectUser,
            )
        }
        val answered = route.await()
        val merged = laterLock(
            (answered as? ZillitResult.Success)?.data?.toPeriodLock(),
            (settings.await() as? ZillitResult.Success)?.data?.settingsPeriodLock(),
        )
        when {
            merged != null -> ZillitResult.Success(merged)
            answered is ZillitResult.Failure -> answered
            else -> ZillitResult.Success(PeriodLock())
        }
    }

    suspend fun closePeriod(asOfMillis: Long): ZillitResult<PeriodLock> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$costReportBase/lock-period",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject { put("as_of", JsonPrimitive(asOfMillis)) },
    ).map { it.toPeriodLock() ?: PeriodLock() }

    /**
     * `POST /closing-package/publish { packages: [{ user_ids, emails, reports }] }`.
     *
     * Invalid packages — no recipient, or no report — are dropped before the
     * call, as the web's `validPackages` does; the server e-mails the rest.
     */
    suspend fun publishClosingPackage(packages: List<ClosingPackage>): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Post,
        url = "$costReportBase/closing-package/publish",
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put(
                "packages",
                buildJsonArray {
                    packages.filter { it.isValid }.forEach { pkg ->
                        add(
                            buildJsonObject {
                                put("user_ids", buildJsonArray { pkg.userIds.forEach { add(JsonPrimitive(it)) } })
                                put("emails", buildJsonArray { pkg.emails.forEach { add(JsonPrimitive(it)) } })
                                put("reports", buildJsonArray { pkg.reports.forEach { add(JsonPrimitive(it.wire)) } })
                            },
                        )
                    }
                },
            )
        },
    ).map { }

    suspend fun budgetVersions(): ZillitResult<List<BudgetVersion>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$hubBase/budgets",
        serializer = JsonElement.serializer(),
        module = RequestModule.ProjectUser,
    ).map { payload -> payload.rows(BudgetVersionDto.serializer()).map { it.toDomain() }.filter { it.id.isNotBlank() } }

    suspend fun budgetLines(versionId: String): ZillitResult<List<BudgetLine>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$hubBase/budgets/$versionId/lines",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
        ).map { payload ->
            payload.rows(BudgetLineDto.serializer()).map { it.toDomain() }.filter { it.id.isNotBlank() }
        }

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

/**
 * A list the route sends bare — `[…]` — or wrapped as `{ value: […] }` or
 * `{ data: […] }` on other deployments.
 *
 * Seen live on 2026-09-11: `/cost-reports/trial-balance` and `/budgets` both
 * answer with a bare array on develop while the web's client reads `value`
 * first and falls back to the body. Reading one shape only turned a full
 * ledger into "No account balances" and a saved budget into "No budget
 * versions yet", with a toast that blamed the server.
 */
internal fun <T> JsonElement.rows(element: KSerializer<T>): List<T> {
    val array = when (this) {
        is JsonArray -> this
        is JsonObject -> (this["value"] ?: this["data"] ?: this["rows"]) as? JsonArray
        else -> null
    } ?: return emptyList()
    return array.mapNotNull { row ->
        runCatching { accountHubJson.decodeFromJsonElement(element, row) }.getOrNull()
    }
}

/** The trial balance's business refusal over a 200 — the envelope's `status: 0`. */
private const val TRIAL_BALANCE_REFUSED = 0
