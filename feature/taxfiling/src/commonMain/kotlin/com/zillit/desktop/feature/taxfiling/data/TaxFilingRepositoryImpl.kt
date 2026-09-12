package com.zillit.desktop.feature.taxfiling.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.taxfiling.domain.BoxMapping
import com.zillit.desktop.feature.taxfiling.domain.CoaCode
import com.zillit.desktop.feature.taxfiling.domain.FiledReturn
import com.zillit.desktop.feature.taxfiling.domain.FilingObligation
import com.zillit.desktop.feature.taxfiling.domain.FraudSignals
import com.zillit.desktop.feature.taxfiling.domain.LayerSet
import com.zillit.desktop.feature.taxfiling.domain.LedgerLine
import com.zillit.desktop.feature.taxfiling.domain.RegistrationRequest
import com.zillit.desktop.feature.taxfiling.domain.SupportedFiling
import com.zillit.desktop.feature.taxfiling.domain.TaxCompany
import com.zillit.desktop.feature.taxfiling.domain.TaxDates
import com.zillit.desktop.feature.taxfiling.domain.TaxFiling
import com.zillit.desktop.feature.taxfiling.domain.TaxFilingRepository
import com.zillit.desktop.feature.taxfiling.domain.TaxRegistration
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.domain.VatDraft
import com.zillit.desktop.feature.taxfiling.domain.VatReturn
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The tax-filing service, and the account hub's picker lists.
 *
 * Only the United Kingdom's adapter exists today, so the country segment is
 * fixed here rather than threaded through every screen. It is a path segment
 * on the wire precisely so a second one needs no client release, and the
 * catalogue already answers with the country each filing belongs to.
 */
class TaxFilingRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    private val country: String = SupportedFiling.MtdVat.country,
) : TaxFilingRepository {

    private val base = "${config.baseUrl(ZillitService.TaxFiling)}/api/v2/tax-filing"
    private val hubBase = "${config.baseUrl(ZillitService.AccountHub)}/api/v2/account-hub"

    override suspend fun catalog(): ZillitResult<List<TaxFiling>> =
        apiClient.get("$base/catalog", ListSerializer(TaxFilingDto.serializer()))
            .map { rows -> rows.map { it.toDomain() } }

    override suspend fun companies(): ZillitResult<List<TaxCompany>> =
        apiClient.get("$base/registrations/companies", ListSerializer(TaxCompanyDto.serializer()))
            .map { rows -> rows.map { it.toDomain() }.filter { it.id.isNotBlank() } }

    override suspend fun registrations(): ZillitResult<List<TaxRegistration>> =
        apiClient.get("$base/registrations", ListSerializer(TaxRegistrationDto.serializer()))
            .map { rows -> rows.map { it.toDomain() }.filter { it.id.isNotBlank() } }

    override suspend fun createRegistration(request: RegistrationRequest): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Post,
        url = "$base/registrations",
        module = RequestModule.ProjectUser,
        body = registrationBody(request),
    ).map { }

    override suspend fun deleteRegistration(id: String): ZillitResult<Unit> = apiClient.envelope(
        verb = HttpVerb.Delete,
        url = "$base/registrations/$id",
        module = RequestModule.ProjectUser,
    ).map { }

    override suspend fun exportRegistration(id: String): ZillitResult<String> =
        apiClient.get("$base/registrations/$id/export", JsonElement.serializer())
            .map { pretty.encodeToString(JsonElement.serializer(), it) }

    override suspend fun connectUrl(registrationId: String): ZillitResult<String> = apiClient.get(
        url = "$base/$country/oauth/authorize",
        serializer = ConnectUrlDto.serializer(),
        query = mapOf("registrationId" to registrationId),
    ).map { it.href }

    /**
     * The company's saved boxes.
     *
     * `data.rows`, as the web reads it — the rows sit one level down, and a
     * reader expecting the list at `data` refuses the whole answer, so the
     * mapping would never load. A bare list is accepted too.
     */
    override suspend fun boxMap(companyId: String): ZillitResult<List<BoxMapping>> = apiClient.get(
        url = "$base/box-map",
        serializer = JsonElement.serializer(),
        query = mapOf("companyId" to companyId),
    ).map { data -> decodeRows(data, BoxMappingDto.serializer()).mapNotNull { it.toDomain() } }

    override suspend fun saveBoxMap(companyId: String, rows: List<BoxMapping>): ZillitResult<Unit> =
        apiClient.envelope(
            // PUT, not PATCH: the whole map is replaced, so a box left out is a
            // box unmapped rather than one left alone.
            verb = HttpVerb.Put,
            url = "$base/box-map",
            module = RequestModule.ProjectUser,
            queryParameters = mapOf("companyId" to companyId),
            body = boxMapBody(rows),
        ).map { }

    override suspend fun obligations(registrationId: String): ZillitResult<List<FilingObligation>> =
        apiClient.get(
            url = "$base/$country/registrations/$registrationId/obligations",
            serializer = ListSerializer(ObligationDto.serializer()),
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

    override suspend fun buildDraft(registrationId: String, periodKey: String): ZillitResult<VatDraft> =
        apiClient.request(
            verb = HttpVerb.Post,
            url = "$base/$country/registrations/$registrationId/returns/draft",
            // The envelope, not the boxes: they are a level down under `payload`,
            // and reading the wrapper as the return files nine silent zeroes.
            serializer = DraftResponseDto.serializer(),
            module = RequestModule.ProjectUser,
            body = buildJsonObject { put("periodKey", JsonPrimitive(periodKey)) },
        ).map { it.toDomain(lenient) }

    override suspend fun filedReturns(registrationId: String): ZillitResult<List<FiledReturn>> =
        apiClient.get(
            url = "$base/$country/registrations/$registrationId/returns",
            serializer = ListSerializer(FiledReturnDto.serializer()),
        ).map { rows -> rows.map { it.toDomain(lenient) } }

    override suspend fun ledgerLines(registrationId: String, periodKey: String): ZillitResult<List<LedgerLine>> =
        apiClient.request(
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

    override suspend fun coaCodes(): ZillitResult<List<CoaCode>> = apiClient.get(
        url = "$hubBase/chart-of-accounts",
        serializer = JsonElement.serializer(),
        query = mapOf("active_only" to "true"),
    ).map { data -> decodeRows(data, CoaRowDto.serializer()).postableCodes() }

    override suspend fun layerSets(): ZillitResult<List<LayerSet>> = apiClient.get(
        url = "$hubBase/tracking-sets",
        serializer = JsonElement.serializer(),
        query = mapOf("active_only" to "true", "include_nodes" to "true"),
    ).map { data -> decodeRows(data, TrackingSetDto.serializer()).mapNotNull { it.toDomain() } }

    override suspend fun assetTags(): ZillitResult<List<String>> =
        apiClient.get("$hubBase/project-settings/asset-tags", JsonElement.serializer()).map { data ->
            val list = (data as? JsonObject)?.get("value") ?: data
            list.asStringList().distinct()
        }

}

/** Every read here is a production user's GET. */
private suspend fun <T> ApiClient.get(
    url: String,
    serializer: KSerializer<T>,
    query: Map<String, Any?> = emptyMap(),
): ZillitResult<T> = request(
    verb = HttpVerb.Get,
    url = url,
    serializer = serializer,
    module = RequestModule.ProjectUser,
    queryParameters = query,
)

/** For the payload and receipt this service stores as text, not objects. */
private val lenient = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

/** Two-space indent, as the web's `JSON.stringify(data, null, 2)` writes the export. */
@OptIn(ExperimentalSerializationApi::class)
private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

/** A list answer's rows, each decoded on its own — one malformed row does not cost the rest. */
private fun <T> decodeRows(data: JsonElement, serializer: KSerializer<T>): List<T> =
    rowsOf(data).mapNotNull { row -> runCatching { lenient.decodeFromJsonElement(serializer, row) }.getOrNull() }

/** The rows of a list answer — a bare array, or one under `rows`, `value` or `data`. */
internal fun rowsOf(data: JsonElement): JsonArray = when (data) {
    is JsonArray -> data
    is JsonObject -> listOf("rows", "value", "data").firstNotNullOfOrNull { data[it] as? JsonArray }
    else -> null
} ?: JsonArray(emptyList())

/**
 * A registration, as the web's register dialog sends it.
 *
 * The country and regime ride along explicitly — the service keys a VAT number
 * to the authority it was registered with — and a blank registration date is
 * sent as null rather than as an empty string the server would try to parse.
 */
internal fun registrationBody(request: RegistrationRequest): JsonObject = buildJsonObject {
    put("company_id", JsonPrimitive(request.companyId))
    put("country_code", JsonPrimitive(request.countryCode))
    put("regime", JsonPrimitive(request.regime))
    put("registration_number", JsonPrimitive(request.registrationNumber.filter { it.isDigit() }))
    put("registration_date", request.registrationDate?.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)
    put("filing_frequency", JsonPrimitive(request.filingFrequency))
}

/**
 * The whole map, minus the rows that select nothing.
 *
 * The PUT replaces everything, so an empty row and an absent one mean the same
 * thing — and sending it would store a mapping nobody made. Codes are trimmed,
 * and the window edges go as UTC-midnight milliseconds, as the web's `ymdToMs`.
 */
internal fun boxMapBody(rows: List<BoxMapping>): JsonObject = buildJsonObject {
    val cleaned = rows
        .map { row -> row.copy(codes = row.codes.map(String::trim).filter(String::isNotEmpty)) }
        .filter { it.isConfigured }
        .sortedBy { VatBox.bySlot(it.box)?.number ?: Int.MAX_VALUE }
    put("rows", buildJsonArray { cleaned.forEach { add(it.toJson()) } })
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
internal fun submitBody(periodKey: String, vatReturn: VatReturn, signals: FraudSignals): JsonObject =
    buildJsonObject {
        put("periodKey", JsonPrimitive(vatReturn.periodKey.ifBlank { periodKey }))
        // Computed, not as held: boxes 3 and 5 are the two HMRC checks against
        // the rest, and filing a figure that disagrees with the arithmetic is a
        // rejected return.
        put("payload", vatReturn.computed().toJson(periodKey))
        put("fraudData", signals.toJson())
    }

private fun BoxMapping.toJson(): JsonElement = buildJsonObject {
    put("box", JsonPrimitive(box))
    put("codes", buildJsonArray { codes.forEach { add(JsonPrimitive(it)) } })
    put("layers", buildJsonObject { layers.forEach { (set, code) -> put(set, JsonPrimitive(code)) } })
    put("tags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
    put("date_from", TaxDates.toMillis(fromDate)?.let(::JsonPrimitive) ?: JsonNull)
    put("date_to", TaxDates.toMillis(toDate)?.let(::JsonPrimitive) ?: JsonNull)
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
