package com.zillit.desktop.feature.taxfiling.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.taxfiling.domain.BoxMapping
import com.zillit.desktop.feature.taxfiling.domain.FiledReturn
import com.zillit.desktop.feature.taxfiling.domain.FilingObligation
import com.zillit.desktop.feature.taxfiling.domain.LedgerLine
import com.zillit.desktop.feature.taxfiling.domain.FraudSignals
import com.zillit.desktop.feature.taxfiling.domain.TaxCompany
import com.zillit.desktop.feature.taxfiling.domain.TaxFiling
import com.zillit.desktop.feature.taxfiling.domain.TaxFilingRepository
import com.zillit.desktop.feature.taxfiling.domain.TaxRegistration
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.domain.VatDraft
import com.zillit.desktop.feature.taxfiling.domain.VatReturn
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The tax-filing service.
 *
 * Only the United Kingdom's adapter exists today, so the country segment is
 * fixed here rather than threaded through every screen. It is a path segment
 * on the wire precisely so a second one needs no client release, and the
 * catalogue already answers with the country each filing belongs to.
 */
class TaxFilingRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    private val country: String = "GB",
) : TaxFilingRepository {

    private val base = "${config.baseUrl(ZillitService.TaxFiling)}/api/v2/tax-filing"

    /** For the payload and receipt this service stores as text, not objects. */
    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun catalog(): ZillitResult<List<TaxFiling>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/catalog",
        serializer = ListSerializer(TaxFilingDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { rows -> rows.map { it.toDomain() } }

    override suspend fun companies(): ZillitResult<List<TaxCompany>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/registrations/companies",
        serializer = ListSerializer(TaxCompanyDto.serializer()),
        module = RequestModule.ProjectUser,
    ).map { rows -> rows.map { it.toDomain() }.filter { it.id.isNotBlank() } }

    /**
     * The registrations, named.
     *
     * The companies are fetched alongside because a registration carries only
     * a company id, and a screen of bare ids is a screen nobody can read.
     */
    override suspend fun registrations(): ZillitResult<List<TaxRegistration>> {
        val companies = when (val loaded = companies()) {
            is ZillitResult.Success -> loaded.data
            // A failure to name the companies is not a failure to list the
            // registrations: the ids still identify them.
            is ZillitResult.Failure -> emptyList()
        }
        return apiClient.request(
            verb = HttpVerb.Get,
            url = "$base/registrations",
            serializer = ListSerializer(TaxRegistrationDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { rows -> rows.map { it.toDomain(companies) }.filter { it.id.isNotBlank() } }
    }

    override suspend fun createRegistration(
        companyId: String,
        registrationNumber: String,
        frequency: String,
    ): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Post,
        url = "$base/registrations",
        module = RequestModule.ProjectUser,
        body = buildJsonObject {
            put("company_id", JsonPrimitive(companyId))
            put("registration_number", JsonPrimitive(registrationNumber.trim()))
            put("filing_frequency", JsonPrimitive(frequency))
        },
    ).map { }

    override suspend fun deleteRegistration(id: String): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Delete,
        url = "$base/registrations/$id",
        module = RequestModule.ProjectUser,
    ).map { }

    override suspend fun connectUrl(registrationId: String): ZillitResult<String> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/$country/oauth/authorize",
        serializer = ConnectUrlDto.serializer(),
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("registrationId" to registrationId),
    ).map { it.href }

    override suspend fun boxMap(companyId: String): ZillitResult<List<BoxMapping>> = apiClient.request(
        verb = HttpVerb.Get,
        url = "$base/box-map",
        serializer = ListSerializer(BoxMappingDto.serializer()),
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("companyId" to companyId),
    ).map { rows -> rows.map { it.toDomain() } }

    override suspend fun saveBoxMap(
        companyId: String,
        rows: List<BoxMapping>,
    ): ZillitResult<Unit> = apiClient.envelope(
        // PUT, not PATCH: the whole map is replaced, so a box left out is a
        // box unmapped rather than one left alone.
        verb = HttpVerb.Put,
        url = "$base/box-map",
        module = RequestModule.ProjectUser,
        queryParameters = mapOf("companyId" to companyId),
        body = boxMapBody(rows),
    ).map { }

    override suspend fun obligations(registrationId: String): ZillitResult<List<FilingObligation>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$base/$country/registrations/$registrationId/obligations",
            serializer = ListSerializer(ObligationDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { rows -> rows.map { it.toDomain() } }

    override suspend fun syncObligations(
        registrationId: String,
        fromDate: String,
        toDate: String,
        signals: FraudSignals,
    ): ZillitResult<List<FilingObligation>> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/$country/registrations/$registrationId/obligations/sync",
        serializer = ListSerializer(ObligationDto.serializer()),
        module = RequestModule.ProjectUser,
        body = syncBody(fromDate, toDate, signals),
    ).map { rows -> rows.map { it.toDomain() } }

    override suspend fun buildDraft(
        registrationId: String,
        periodKey: String,
    ): ZillitResult<VatDraft> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/$country/registrations/$registrationId/returns/draft",
        // The envelope, not the boxes: they are a level down under `payload`,
        // and reading the wrapper as the return files nine silent zeroes.
        serializer = DraftResponseDto.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject { put("periodKey", JsonPrimitive(periodKey)) },
    ).map { it.toDomain() }

    override suspend fun filedReturns(registrationId: String): ZillitResult<List<FiledReturn>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "$base/$country/registrations/$registrationId/returns",
            serializer = ListSerializer(FiledReturnDto.serializer()),
            module = RequestModule.ProjectUser,
        ).map { rows -> rows.map { it.toDomain(lenient) } }

    override suspend fun ledgerLines(
        registrationId: String,
        periodKey: String,
    ): ZillitResult<List<LedgerLine>> = apiClient.request(
        verb = HttpVerb.Post,
        url = "$base/$country/registrations/$registrationId/returns/ledger-export",
        serializer = LedgerExportDto.serializer(),
        module = RequestModule.ProjectUser,
        body = buildJsonObject { put("periodKey", JsonPrimitive(periodKey)) },
    ).map { export -> export.rows.orEmpty().map { it.toDomain() } }

    override suspend fun submitReturn(
        registrationId: String,
        periodKey: String,
        vatReturn: VatReturn,
        signals: FraudSignals,
    ): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Post,
        url = "$base/$country/registrations/$registrationId/returns/submit",
        module = RequestModule.ProjectUser,
        body = submitBody(periodKey, vatReturn, signals),
    ).map { }
}

/**
 * The whole map, minus the rows that select nothing.
 *
 * The PUT replaces everything, so an empty row and an absent one mean the same
 * thing — and sending it would store a mapping nobody made.
 */
internal fun boxMapBody(rows: List<BoxMapping>): JsonObject = buildJsonObject {
    put("rows", buildJsonArray { rows.filter { it.isConfigured }.forEach { add(it.toJson()) } })
}

internal fun syncBody(fromDate: String, toDate: String, signals: FraudSignals): JsonObject =
    buildJsonObject {
        put("from", JsonPrimitive(fromDate))
        put("to", JsonPrimitive(toDate))
        put("fraudData", signals.toJson())
    }

/**
 * The submission.
 *
 * The draft's own period wins over the caller's: the figures were computed for
 * one period, and if the two ever disagree, filing them under the other files
 * the wrong quarter.
 */
internal fun submitBody(
    periodKey: String,
    vatReturn: VatReturn,
    signals: FraudSignals,
): JsonObject = buildJsonObject {
    put("periodKey", JsonPrimitive(vatReturn.periodKey.ifBlank { periodKey }))
    // Computed, not as held: boxes 3 and 5 are the two HMRC checks against the
    // rest, and filing a figure that disagrees with the arithmetic is a
    // rejected return.
    put("payload", vatReturn.computed().toJson(periodKey))
    put("fraudData", signals.toJson())
}

private fun BoxMapping.toJson(): JsonElement = buildJsonObject {
    put("box", JsonPrimitive(box))
    put("codes", buildJsonArray { codes.forEach { add(JsonPrimitive(it)) } })
    put("layers", buildJsonObject { layers.forEach { (set, code) -> put(set, JsonPrimitive(code)) } })
    put("tags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
    put("date_from", fromMillis?.let(::JsonPrimitive) ?: JsonNull)
    put("date_to", toMillis?.let(::JsonPrimitive) ?: JsonNull)
    put("mark_zero", JsonPrimitive(markZero))
}

/**
 * The return, by HMRC's field names.
 *
 * The figures go out as the server built them, boxes 6 to 9 included. Those
 * are whole pounds because the service computed them that way for HMRC, and a
 * client that re-rounds them invents a tie-breaking rule of its own — the
 * screen would then show one figure and the wire carry another, which is the
 * one discrepancy on this screen nobody could reconcile afterwards.
 *
 * Boxes 3 and 5 are the exception, recomputed by [VatReturn.computed] before
 * this is called: they are arithmetic on the rest rather than ledger sums, and
 * HMRC checks them.
 */
private fun VatReturn.toJson(fallbackPeriodKey: String): JsonElement = buildJsonObject {
    VatBox.entries.forEach { box -> put(box.field, JsonPrimitive(this@toJson[box] ?: 0.0)) }
    put("periodKey", JsonPrimitive(periodKey.ifBlank { fallbackPeriodKey }))
    // HMRC's declaration flag, and it is not decoration: the submission is
    // only a legal return because this says so, and a payload without it is
    // refused. It rides here rather than being assumed by the backend so the
    // client that showed the figures is the one that declares them.
    put("finalised", JsonPrimitive(true))
}

private fun FraudSignals.toJson(): JsonElement = buildJsonObject {
    put("deviceId", JsonPrimitive(deviceId))
    put("timezone", JsonPrimitive(timezone))
    put("userAgent", JsonPrimitive(userAgent))
    put("doNotTrack", JsonPrimitive(if (doNotTrack) "true" else "false"))
    put("screens", JsonPrimitive(screens))
    put("windowSize", JsonPrimitive(windowSize))
}
