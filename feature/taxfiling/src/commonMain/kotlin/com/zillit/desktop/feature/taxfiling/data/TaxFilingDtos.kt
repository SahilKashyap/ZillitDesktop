package com.zillit.desktop.feature.taxfiling.data

import com.zillit.desktop.feature.taxfiling.domain.BoxMapping
import com.zillit.desktop.feature.taxfiling.domain.FilingObligation
import com.zillit.desktop.feature.taxfiling.domain.TaxCompany
import com.zillit.desktop.feature.taxfiling.domain.TaxFiling
import com.zillit.desktop.feature.taxfiling.domain.TaxRegistration
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.domain.VatReturn
import com.zillit.desktop.feature.taxfiling.domain.DraftDiagnostics
import com.zillit.desktop.feature.taxfiling.domain.FiledReturn
import com.zillit.desktop.feature.taxfiling.domain.LedgerLine
import com.zillit.desktop.feature.taxfiling.domain.VatDraft
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * This service answers `{ status, message, data }` with the payload directly
 * under `data` — no `value` wrapper, unlike the account hub's slices next to
 * it. Reading for one here would find nothing and report an empty screen.
 */
@Serializable
data class TaxFilingDto(
    @SerialName("country") val country: String? = null,
    @SerialName("countryName") val countryName: String? = null,
    @SerialName("regime") val regime: String? = null,
    @SerialName("key") val key: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("subtitle") val subtitle: String? = null,
    @SerialName("description") val description: String? = null,
) {
    fun toDomain() = TaxFiling(
        country = country.orEmpty(),
        countryName = countryName.orEmpty(),
        regime = regime.orEmpty(),
        key = key.orEmpty(),
        title = title.orEmpty(),
        subtitle = subtitle.orEmpty(),
        description = description.orEmpty(),
    )
}

@Serializable
data class TaxCompanyDto(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
) {
    fun toDomain() = TaxCompany(id = id.orEmpty(), name = name.orEmpty())
}

@Serializable
data class TaxRegistrationDto(
    @SerialName("id") val id: String? = null,
    @SerialName("company_id") val companyId: String? = null,
    @SerialName("registration_number") val registrationNumber: String? = null,
    @SerialName("filing_frequency") val frequency: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("connected") val connected: Boolean? = null,
) {
    /** [companies] names the company; without a match the id stands in. */
    fun toDomain(companies: List<TaxCompany>): TaxRegistration {
        val company = companies.firstOrNull { it.id == companyId }
        return TaxRegistration(
            id = id.orEmpty(),
            companyId = companyId.orEmpty(),
            companyName = company?.name?.takeIf { it.isNotBlank() } ?: companyId.orEmpty(),
            registrationNumber = registrationNumber.orEmpty(),
            filingFrequency = frequency.orEmpty(),
            // Absent reads as active, which is what the web assumes and what
            // a registration with no status recorded against it is.
            status = status?.takeIf { it.isNotBlank() } ?: "active",
            connected = connected == true,
        )
    }
}

/**
 * An obligation, in either of the two spellings this service answers with.
 *
 * A row fresh from HMRC comes back camelCase; a row read from the service's
 * own table comes back snake_case. Reading one spelling only would leave the
 * period picker full of blanks after whichever call was not the one it
 * expected — the web reads both, and so does this.
 */
@Serializable
data class ObligationDto(
    @SerialName("periodKey") val periodKey: String? = null,
    @SerialName("period_key") val periodKeySnake: String? = null,
    @SerialName("start") val start: String? = null,
    @SerialName("period_start") val startSnake: String? = null,
    @SerialName("end") val end: String? = null,
    @SerialName("period_end") val endSnake: String? = null,
    @SerialName("due") val due: String? = null,
    @SerialName("received") val received: String? = null,
    @SerialName("status") val status: String? = null,
) {
    fun toDomain() = FilingObligation(
        periodKey = periodKey?.takeIf { it.isNotBlank() } ?: periodKeySnake.orEmpty(),
        start = start?.takeIf { it.isNotBlank() } ?: startSnake.orEmpty(),
        end = end?.takeIf { it.isNotBlank() } ?: endSnake.orEmpty(),
        due = due.orEmpty(),
        received = received.orEmpty(),
        status = status.orEmpty(),
    )
}

@Serializable
data class BoxMappingDto(
    @SerialName("box") val box: String? = null,
    @SerialName("codes") val codes: List<String>? = null,
    @SerialName("layers") val layers: Map<String, String>? = null,
    @SerialName("tags") val tags: List<String>? = null,
    @SerialName("date_from") val dateFrom: Long? = null,
    @SerialName("date_to") val dateTo: Long? = null,
    @SerialName("mark_zero") val markZero: Boolean? = null,
) {
    fun toDomain() = BoxMapping(
        box = box.orEmpty(),
        codes = codes.orEmpty().filter { it.isNotBlank() },
        layers = layers.orEmpty(),
        tags = tags.orEmpty().filter { it.isNotBlank() },
        fromMillis = dateFrom,
        toMillis = dateTo,
        markZero = markZero == true,
    )
}

/**
 * A built draft, keyed by HMRC's own field names.
 *
 * Read into boxes by field so the two computed ones can be checked rather than
 * taken on trust — see [VatReturn.computed].
 */
@Serializable
data class VatReturnDto(
    @SerialName("vatDueSales") val vatDueSales: Double? = null,
    @SerialName("vatDueAcquisitions") val vatDueAcquisitions: Double? = null,
    @SerialName("totalVatDue") val totalVatDue: Double? = null,
    @SerialName("vatReclaimedCurrPeriod") val vatReclaimed: Double? = null,
    @SerialName("netVatDue") val netVatDue: Double? = null,
    @SerialName("totalValueSalesExVAT") val salesExVat: Double? = null,
    @SerialName("totalValuePurchasesExVAT") val purchasesExVat: Double? = null,
    @SerialName("totalValueGoodsSuppliedExVAT") val goodsSuppliedExVat: Double? = null,
    @SerialName("totalAcquisitionsExVAT") val acquisitionsExVat: Double? = null,
    /** The server names the period it built for; it wins over the screen's. */
    @SerialName("periodKey") val periodKey: String? = null,
) {
    fun toDomain(): VatReturn = VatReturn(
        periodKey = periodKey.orEmpty(),
        values = buildMap {
            vatDueSales?.let { put(VatBox.DueOnSales, it) }
            vatDueAcquisitions?.let { put(VatBox.DueOnAcquisitions, it) }
            totalVatDue?.let { put(VatBox.TotalDue, it) }
            vatReclaimed?.let { put(VatBox.ReclaimedOnPurchases, it) }
            netVatDue?.let { put(VatBox.NetDue, it) }
            salesExVat?.let { put(VatBox.SalesExVat, it) }
            purchasesExVat?.let { put(VatBox.PurchasesExVat, it) }
            goodsSuppliedExVat?.let { put(VatBox.GoodsSuppliedExVat, it) }
            acquisitionsExVat?.let { put(VatBox.AcquisitionsExVat, it) }
        },
    )
}

/**
 * What `returns/draft` actually answers.
 *
 * The nine boxes are one level down under `payload`, beside the server's own
 * account of what it read. Parsing the envelope's `data` as the boxes finds
 * none of them and produces a silent return of zeroes, which is the worst
 * possible failure on this screen.
 */
@Serializable
data class DraftResponseDto(
    @SerialName("payload") val payload: VatReturnDto? = null,
    @SerialName("diagnostics") val diagnostics: DiagnosticsDto? = null,
) {
    fun toDomain() = VatDraft(
        vatReturn = payload?.toDomain() ?: VatReturn(),
        diagnostics = diagnostics?.toDomain() ?: DraftDiagnostics(),
    )
}

@Serializable
data class DiagnosticsDto(
    @SerialName("rowsInScope") val rowsInScope: Int? = null,
    @SerialName("nullCompanyRows") val nullCompanyRows: Int? = null,
) {
    fun toDomain() = DraftDiagnostics(rowsInScope = rowsInScope, nullCompanyRows = nullCompanyRows)
}

/**
 * A return this application filed, and HMRC's receipt for it.
 *
 * `payload` and `receipt` arrive as either an object or a JSON string — the
 * service stores them as text and does not always parse them on the way out —
 * so both are read as raw elements and decoded here.
 */
@Serializable
data class FiledReturnDto(
    @SerialName("period_key") val periodKey: String? = null,
    @SerialName("periodKey") val periodKeyCamel: String? = null,
    @SerialName("payload") val payload: JsonElement? = null,
    @SerialName("receipt") val receipt: JsonElement? = null,
) {
    fun toDomain(json: Json): FiledReturn {
        val boxes = payload.asObject(json)
        val stamp = receipt.asObject(json)
        return FiledReturn(
            periodKey = periodKey?.takeIf { it.isNotBlank() } ?: periodKeyCamel.orEmpty(),
            values = VatBox.entries.mapNotNull { box ->
                boxes?.get(box.field)?.jsonPrimitive?.doubleOrNull?.let { box to it }
            }.toMap(),
            reference = stamp?.text("reference")?.ifBlank { stamp.text("formBundleNumber") }
                .orEmpty(),
            processedAt = stamp?.text("processingDate").orEmpty(),
        )
    }
}

/** One ledger line behind a box. */
@Serializable
data class LedgerRowDto(
    @SerialName("box") val box: String? = null,
    @SerialName("account_code") val accountCode: String? = null,
    @SerialName("debit") val debit: Double? = null,
    @SerialName("credit") val credit: Double? = null,
    @SerialName("period_year") val periodYear: Int? = null,
    @SerialName("period_month") val periodMonth: Int? = null,
    @SerialName("tracking_codes") val tracking: Map<String, String>? = null,
    @SerialName("memo") val memo: String? = null,
) {
    fun toDomain() = LedgerLine(
        box = box.orEmpty(),
        accountCode = accountCode.orEmpty(),
        debit = debit ?: 0.0,
        credit = credit ?: 0.0,
        periodYear = periodYear,
        periodMonth = periodMonth,
        tracking = tracking.orEmpty(),
        memo = memo.orEmpty(),
    )
}

@Serializable
data class LedgerExportDto(@SerialName("rows") val rows: List<LedgerRowDto>? = null)

/** An object, a JSON string holding one, or neither. */
private fun JsonElement?.asObject(json: Json): JsonObject? = when {
    this == null || this is JsonNull -> null
    this is JsonObject -> this
    this is JsonPrimitive && isString ->
        runCatching { json.parseToJsonElement(content) as? JsonObject }.getOrNull()
    else -> null
}

private fun JsonObject.text(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

/** The consent page to open in the machine's browser. */
@Serializable
data class ConnectUrlDto(
    @SerialName("url") val url: String? = null,
    @SerialName("authorizeUrl") val authorizeUrl: String? = null,
) {
    val href: String get() = url?.takeIf { it.isNotBlank() } ?: authorizeUrl.orEmpty()
}
