package com.zillit.desktop.feature.taxfiling.data

import com.zillit.desktop.feature.taxfiling.domain.BoxMapping
import com.zillit.desktop.feature.taxfiling.domain.DraftDiagnostics
import com.zillit.desktop.feature.taxfiling.domain.FiledReturn
import com.zillit.desktop.feature.taxfiling.domain.FilingObligation
import com.zillit.desktop.feature.taxfiling.domain.LedgerLine
import com.zillit.desktop.feature.taxfiling.domain.TaxCompany
import com.zillit.desktop.feature.taxfiling.domain.TaxDates
import com.zillit.desktop.feature.taxfiling.domain.TaxFiling
import com.zillit.desktop.feature.taxfiling.domain.TaxRegistration
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.domain.VatDraft
import com.zillit.desktop.feature.taxfiling.domain.VatReturn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * This service answers `{ status, message, data }` with the payload directly
 * under `data` — no `value` wrapper, unlike the account hub's slices next to
 * it. Reading for one here would find nothing and report an empty screen.
 *
 * Its figures come out of Postgres `numeric` columns, which the Node driver
 * sends as strings, so every amount and count below is read as a raw element
 * and parsed whichever way it arrived — the web's `Number(...)`.
 */
@Serializable
data class TaxFilingDto(
    @SerialName("country") val country: String? = null,
    @SerialName("countryName") val countryName: String? = null,
    @SerialName("flag") val flag: String? = null,
    @SerialName("regime") val regime: String? = null,
    @SerialName("key") val key: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("subtitle") val subtitle: String? = null,
    @SerialName("description") val description: String? = null,
) {
    fun toDomain() = TaxFiling(
        country = country.orEmpty(),
        countryName = countryName.orEmpty(),
        flag = flag.orEmpty(),
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
    @SerialName("_id") val altId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
) {
    fun toDomain() = TaxCompany(
        id = id?.takeIf { it.isNotBlank() } ?: altId.orEmpty(),
        name = name.orEmpty(),
        countryCode = countryCode.orEmpty().trim().uppercase(),
    )
}

@Serializable
data class TaxRegistrationDto(
    @SerialName("id") val id: String? = null,
    @SerialName("company_id") val companyId: String? = null,
    @SerialName("registration_number") val registrationNumber: String? = null,
    @SerialName("filing_frequency") val frequency: String? = null,
    @SerialName("status") val status: String? = null,
    @SerialName("connected") val connected: JsonElement? = null,
) {
    /** Unnamed: the company's name is the companies list's to give. */
    fun toDomain(): TaxRegistration = TaxRegistration(
        id = id.orEmpty(),
        companyId = companyId.orEmpty(),
        registrationNumber = registrationNumber.orEmpty(),
        filingFrequency = frequency.orEmpty(),
        // Absent reads as active, which is what the web assumes and what a
        // registration with no status recorded against it is.
        status = status?.takeIf { it.isNotBlank() } ?: TaxRegistration.ACTIVE,
        connected = connected.asBoolean() == true,
    )
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

/**
 * One saved box, as `box-map` stores it.
 *
 * `box` is the slot id (`box1`); a bare number is tolerated, as the web does.
 * The window edges are epoch milliseconds, sent as numbers or numeric text.
 */
@Serializable
data class BoxMappingDto(
    @SerialName("box") val box: JsonElement? = null,
    @SerialName("codes") val codes: JsonElement? = null,
    @SerialName("layers") val layers: JsonElement? = null,
    @SerialName("tags") val tags: JsonElement? = null,
    @SerialName("date_from") val dateFrom: JsonElement? = null,
    @SerialName("date_to") val dateTo: JsonElement? = null,
    @SerialName("mark_zero") val markZero: JsonElement? = null,
) {
    /** Null for a row naming no box this client knows — boxes 3 and 5 included. */
    fun toDomain(): BoxMapping? {
        val slot = VatBox.bySlot(box.asText().orEmpty())?.takeUnless { it.computed } ?: return null
        return BoxMapping(
            box = slot.slot,
            codes = codes.asStringList(),
            layers = layers.asStringMap(),
            tags = tags.asStringList(),
            fromDate = TaxDates.toYmd(dateFrom.asDouble()?.toLong()),
            toDate = TaxDates.toYmd(dateTo.asDouble()?.toLong()),
            markZero = markZero.asBoolean() == true,
        )
    }
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
    @SerialName("payload") val payload: JsonElement? = null,
    @SerialName("diagnostics") val diagnostics: DiagnosticsDto? = null,
) {
    fun toDomain(json: Json) = VatDraft(
        vatReturn = payload.asObject(json)?.toVatReturn() ?: VatReturn(),
        diagnostics = diagnostics?.toDomain() ?: DraftDiagnostics(),
    )
}

@Serializable
data class DiagnosticsDto(
    @SerialName("rowsInScope") val rowsInScope: JsonElement? = null,
    @SerialName("nullCompanyRows") val nullCompanyRows: JsonElement? = null,
) {
    fun toDomain() = DraftDiagnostics(
        rowsInScope = rowsInScope.asDouble()?.toInt(),
        nullCompanyRows = nullCompanyRows.asDouble()?.toInt(),
    )
}

/**
 * The boxes by HMRC's field names, and the period the server built them for,
 * which wins over the screen's.
 */
internal fun JsonObject.toVatReturn(): VatReturn = VatReturn(
    periodKey = this["periodKey"].asText().orEmpty(),
    values = VatBox.entries.mapNotNull { box -> this[box.field].asDouble()?.let { box to it } }.toMap(),
)

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
            values = boxes?.toVatReturn()?.values.orEmpty(),
            reference = stamp?.get("reference").asText()?.takeIf { it.isNotBlank() }
                ?: stamp?.get("formBundleNumber").asText().orEmpty(),
            processedAt = stamp?.get("processingDate").asText().orEmpty(),
        )
    }
}

/** One ledger line behind a box. */
@Serializable
data class LedgerRowDto(
    @SerialName("box") val box: JsonElement? = null,
    @SerialName("account_code") val accountCode: JsonElement? = null,
    @SerialName("debit") val debit: JsonElement? = null,
    @SerialName("credit") val credit: JsonElement? = null,
    @SerialName("period_year") val periodYear: JsonElement? = null,
    @SerialName("period_month") val periodMonth: JsonElement? = null,
    @SerialName("tracking_codes") val tracking: JsonElement? = null,
    @SerialName("memo") val memo: JsonElement? = null,
) {
    fun toDomain() = LedgerLine(
        box = box.asText().orEmpty(),
        accountCode = accountCode.asText().orEmpty(),
        debit = debit.asDouble() ?: 0.0,
        credit = credit.asDouble() ?: 0.0,
        periodYear = periodYear.asDouble()?.toInt(),
        periodMonth = periodMonth.asDouble()?.toInt(),
        tracking = tracking.asStringMap(),
        memo = memo.asText().orEmpty(),
    )
}

@Serializable
data class LedgerExportDto(@SerialName("rows") val rows: List<LedgerRowDto>? = null)

/** The consent page to open in the machine's browser. */
@Serializable
data class ConnectUrlDto(
    @SerialName("url") val url: String? = null,
    @SerialName("authorizeUrl") val authorizeUrl: String? = null,
) {
    val href: String get() = url?.takeIf { it.isNotBlank() } ?: authorizeUrl.orEmpty()
}

// -- tolerant readers ---------------------------------------------------------

/** Text for a string or a number; null for null, an object or an array. */
internal fun JsonElement?.asText(): String? = when (this) {
    null, JsonNull -> null
    is JsonPrimitive -> content
    else -> null
}

/** A number sent as a number or as numeric text. */
internal fun JsonElement?.asDouble(): Double? = asText()?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }

/** `true`, `"true"` and `1` are all yes; `false`, `"false"` and `0` are no. */
internal fun JsonElement?.asBoolean(): Boolean? = when (asText()?.trim()?.lowercase()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> null
}

/** A list of strings, or the JSON text of one — an unparsed `jsonb` column. */
internal fun JsonElement?.asStringList(): List<String> = when (this) {
    is JsonArray -> mapNotNull { it.asText()?.trim()?.takeIf(String::isNotEmpty) }
    is JsonPrimitive -> if (isString) {
        runCatching { Json.parseToJsonElement(content) }.getOrNull()
            .takeIf { it is JsonArray }.asStringList()
    } else {
        emptyList()
    }
    else -> emptyList()
}

/** `{ set_id: code }`, skipping blanks; an object or the JSON text of one. */
internal fun JsonElement?.asStringMap(): Map<String, String> {
    val obj = when (this) {
        is JsonObject -> this
        is JsonPrimitive -> if (isString) asObject(Json) else null
        else -> null
    } ?: return emptyMap()
    return obj.mapNotNull { (key, value) -> value.asText()?.takeIf { it.isNotBlank() }?.let { key to it } }.toMap()
}

/** An object, a JSON string holding one, or neither. */
internal fun JsonElement?.asObject(json: Json): JsonObject? = when {
    this == null || this is JsonNull -> null
    this is JsonObject -> this
    this is JsonPrimitive && isString -> runCatching { json.parseToJsonElement(content) as? JsonObject }.getOrNull()
    else -> null
}
