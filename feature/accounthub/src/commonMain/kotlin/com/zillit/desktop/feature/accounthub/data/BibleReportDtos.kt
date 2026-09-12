package com.zillit.desktop.feature.accounthub.data

import com.zillit.desktop.core.common.CurrencyCodeSerializer
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.feature.accounthub.domain.BibleAccount
import com.zillit.desktop.feature.accounthub.domain.BibleEcho
import com.zillit.desktop.feature.accounthub.domain.BibleQuery
import com.zillit.desktop.feature.accounthub.domain.BibleReport
import com.zillit.desktop.feature.accounthub.domain.LedgerTransaction
import com.zillit.desktop.feature.accounthub.domain.PeriodLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlin.time.Instant

// -- the report -----------------------------------------------------------------

/**
 * One transaction row.
 *
 * Money and dates are read as primitives and parsed here rather than typed:
 * the finance services send `1750000000000` and `"1750000000000"`, `-50` and
 * `"-50.00"`, depending on the bucket, and one quoted amount in a typed field
 * fails the whole report — every account, not just the row.
 */
@Serializable
data class LedgerTransactionDto(
    @SerialName("src") val source: String? = null,
    @SerialName("eff_date") val effectiveDate: JsonPrimitive? = null,
    @SerialName("invoice_number") val invoiceNumber: String? = null,
    @SerialName("po_number") val purchaseOrderNumber: String? = null,
    @SerialName("vendor") val vendor: String? = null,
    @SerialName("description") val description: String? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @SerialName("amount") val amount: JsonPrimitive? = null,
) {
    fun toDomain(): LedgerTransaction = LedgerTransaction(
        source = source.orEmpty(),
        effectiveDateMillis = effectiveDate.millisOrNull(),
        invoiceNumber = invoiceNumber.orEmpty(),
        purchaseOrderNumber = purchaseOrderNumber.orEmpty(),
        party = vendor.orEmpty(),
        description = description.orEmpty(),
        originalCurrency = currency.orEmpty(),
        amount = amount.numberOrNull() ?: 0.0,
    )
}

@Serializable
data class BibleAccountDto(
    @SerialName("code") val code: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("total") val total: JsonPrimitive? = null,
    @SerialName("transactions") val transactions: List<LedgerTransactionDto>? = null,
) {
    fun toDomain(): BibleAccount = BibleAccount(
        code = code.orEmpty(),
        name = name.orEmpty(),
        total = total.numberOrNull() ?: 0.0,
        transactions = transactions.orEmpty().map { it.toDomain() },
    )
}

/** The filters the server applied, echoed beside the accounts. */
@Serializable
data class BibleFiltersDto(
    @SerialName("period_start") val periodStart: JsonPrimitive? = null,
    @SerialName("period_end") val periodEnd: JsonPrimitive? = null,
    @SerialName("account_start") val accountStart: String? = null,
    @SerialName("account_end") val accountEnd: String? = null,
    @SerialName("include_open_pos") val includeOpenPos: JsonPrimitive? = null,
) {
    fun toDomain(): BibleEcho = BibleEcho(
        periodStartMillis = periodStart.millisOrNull(),
        periodEndMillis = periodEnd.millisOrNull(),
        accountStart = accountStart.orEmpty(),
        accountEnd = accountEnd.orEmpty(),
        includeOpenPurchaseOrders = includeOpenPos?.let {
            it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull()
        },
    )
}

@Serializable
data class BibleReportDto(
    @SerialName("accounts") val accounts: List<BibleAccountDto>? = null,
    @SerialName("grand_total") val grandTotal: JsonPrimitive? = null,
    @SerialName("grandTotal") val grandTotalCamel: JsonPrimitive? = null,
    @SerialName("total") val total: JsonPrimitive? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("currency") val currency: String? = null,
    @Serializable(with = CurrencyCodeSerializer::class)
    @SerialName("default_currency") val defaultCurrency: String? = null,
    @SerialName("generatedAt") val generatedAt: JsonPrimitive? = null,
    @SerialName("generated_at") val generatedAtSnake: JsonPrimitive? = null,
    /**
     * Per-bucket failures, keyed by source. Reported, never swallowed.
     *
     * Read as an object of anything: a bucket's failure may be a message or a
     * whole error, and a typed map would fail the report on the second — which
     * is the one time the reader most needs to see what did come back.
     */
    @SerialName("errors") val errors: JsonObject? = null,
    @SerialName("filters") val filters: BibleFiltersDto? = null,
) {
    fun toDomain(): BibleReport {
        val accounts = accounts.orEmpty().map { it.toDomain() }
        return BibleReport(
            accounts = accounts,
            // The server's total where it gives one. Only summed here when it
            // does not: a bucket that failed is missing from the accounts, and
            // a client-side sum would quietly report a smaller book.
            grandTotal = grandTotal.numberOrNull()
                ?: grandTotalCamel.numberOrNull()
                ?: total.numberOrNull()
                ?: accounts.sumOf { it.total },
            currencyCode = currency?.takeIf { it.isNotBlank() } ?: defaultCurrency.orEmpty(),
            generatedAtMillis = generatedAt.millisOrNull() ?: generatedAtSnake.millisOrNull(),
            errors = errors.orEmpty().mapValues { (_, value) -> value.errorText() },
            echo = filters?.toDomain(),
        )
    }
}

/**
 * The report out of its envelope's `data`.
 *
 * The cost-report service sends the report bare — the web reads `raw.data` —
 * while the hub's own routes wrap theirs as `{ value: … }`. Both are read: the
 * desktop once decoded only the wrapped shape, and every run came back as "No
 * transactions found" with the ledger full.
 */
internal fun ApiEnvelope.toBibleReport(): ZillitResult<BibleReport> = when {
    status == REFUSED -> ZillitResult.Failure(ZillitError.Http(status = HTTP_OK, serverMessage = message))
    else -> data?.takeUnless { it is JsonNull }?.toBibleReport() ?: ZillitResult.Success(BibleReport())
}

/** A business refusal over a 200; `HTTP_OK` is the repository's. */
private const val REFUSED = 0

/** The report from a `data` already out of its envelope — bare, or wrapped in `value`. */
internal fun JsonElement.toBibleReport(): ZillitResult<BibleReport> {
    val body = unwrapValue() as? JsonObject ?: return ZillitResult.Success(BibleReport())
    return try {
        ZillitResult.Success(accountHubJson.decodeFromJsonElement(BibleReportDto.serializer(), body).toDomain())
    } catch (failure: SerializationException) {
        ZillitResult.Failure(ZillitError.Serialization(failure.message))
    } catch (failure: IllegalArgumentException) {
        ZillitResult.Failure(ZillitError.Serialization(failure.message))
    }
}

/**
 * The bible's query string — the web's `costReportApi.bible`.
 *
 * The multi-selects go comma-separated. The vendor goes bare: it is the one
 * single choice in the bar, and the web pins that on purpose because a joined
 * id would match nothing. `include_open_pos` is only sent to turn commitments
 * off — the server's default is on, and restating it would be this client
 * asserting a default it does not own.
 */
internal fun BibleQuery.toQueryParameters(): Map<String, String> = buildMap {
    put("period_start", periodStartMillis.toString())
    put("period_end", periodEndMillis.toString())
    accountStart.trim().takeIf { it.isNotEmpty() }?.let { put("account_start", it) }
    accountEnd.trim().takeIf { it.isNotEmpty() }?.let { put("account_end", it) }
    accountTypes.csvOrNull()?.let { put("account_type", it) }
    sources.csvOrNull()?.let { put("source", it) }
    companyIds.csvOrNull()?.let { put("company_id", it) }
    vendorId.trim().takeIf { it.isNotEmpty() }?.let { put("vendor_id", it) }
    taxes.csvOrNull()?.let { put("tax", it) }
    tags.csvOrNull()?.let { put("tags", it) }
    currency.trim().takeIf { it.isNotEmpty() }?.let { put("currency", it) }
    if (!includeOpenPurchaseOrders) put("include_open_pos", "false")
}

/**
 * The export's body — the web's `bibleExport` call.
 *
 * The read filters, plus the three lines the server prints in the file's
 * header: the production, the companies and the period in words. Keys with
 * nothing in them are left out, as the web's `undefined` drops them, rather
 * than sent as null for the renderer to print. Open POs always travels: the
 * file states it either way.
 */
internal fun BibleQuery.toExportBody(projectName: String, companyName: String, periodLabel: String): JsonObject =
    buildJsonObject {
        put("period_start", periodStartMillis)
        put("period_end", periodEndMillis)
        accountStart.trim().takeIf { it.isNotEmpty() }?.let { put("account_start", it) }
        accountEnd.trim().takeIf { it.isNotEmpty() }?.let { put("account_end", it) }
        accountTypes.csvOrNull()?.let { put("account_type", it) }
        sources.csvOrNull()?.let { put("source", it) }
        companyIds.csvOrNull()?.let { put("company_id", it) }
        vendorId.trim().takeIf { it.isNotEmpty() }?.let { put("vendor_id", it) }
        taxes.csvOrNull()?.let { put("tax", it) }
        tags.csvOrNull()?.let { put("tags", it) }
        currency.trim().takeIf { it.isNotEmpty() }?.let { put("currency", it) }
        put("include_open_pos", includeOpenPurchaseOrders)
        projectName.trim().takeIf { it.isNotEmpty() }?.let { put("project_name", it) }
        companyName.trim().takeIf { it.isNotEmpty() }?.let { put("company_name", it) }
        put("period_label", periodLabel)
    }

// -- the close boundary -----------------------------------------------------------

/**
 * A lock-period answer, bare or wrapped.
 *
 * Null when the body is not an object at all. The read route names the date
 * `lockedDate` and the write route `last_cr_locked_date`; both are read, and
 * whichever shape the date comes in is normalised.
 */
internal fun JsonElement.toPeriodLock(): PeriodLock? {
    val body = unwrapValue() as? JsonObject ?: return null
    val week = body["week"] as? JsonObject
    return PeriodLock(
        lockedThrough = normalizeLockedDate(body["lockedDate"])
            .ifBlank { normalizeLockedDate(body["last_cr_locked_date"]) },
        timeZone = (body["tz"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        weekStartDay = (week?.get("start_day_of_week") as? JsonPrimitive)?.intOrNull,
        weekEndDay = (week?.get("end_day_of_week") as? JsonPrimitive)?.intOrNull,
    )
}

/**
 * The boundary as the combined project-settings document carries it.
 *
 * The web's `useCrLock` reads both this and the lock route, because the two
 * are the same row stored two ways and the live lock route has failed outright
 * on a stored string date. Null when the document has no settings object.
 */
internal fun JsonElement.settingsPeriodLock(): PeriodLock? {
    val settings = (unwrapValue() as? JsonObject)?.get("settings") as? JsonObject ?: return null
    val week = settings["cost_report_week"] as? JsonObject
    return PeriodLock(
        lockedThrough = normalizeLockedDate(settings["last_cr_locked_date"]),
        timeZone = (settings["timezone"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        weekStartDay = (week?.get("start_day_of_week") as? JsonPrimitive)?.intOrNull,
        weekEndDay = (week?.get("end_day_of_week") as? JsonPrimitive)?.intOrNull,
    )
}

/**
 * The later of two readings of one boundary.
 *
 * The lock only ever moves forward, so the later date is the truth; the zone
 * and week come from whichever reading has them. `YYYY-MM-DD` compares as
 * text, and a blank date sorts first, which is exactly "not closed".
 */
internal fun laterLock(route: PeriodLock?, settings: PeriodLock?): PeriodLock? = when {
    route == null -> settings
    settings == null -> route
    else -> PeriodLock(
        lockedThrough = maxOf(route.lockedThrough, settings.lockedThrough),
        timeZone = route.timeZone.ifBlank { settings.timeZone },
        weekStartDay = route.weekStartDay ?: settings.weekStartDay,
        weekEndDay = route.weekEndDay ?: settings.weekEndDay,
    )
}

/**
 * `YYYY-MM-DD` from whichever shape the boundary arrives in — the web's
 * `normalizeLockedYmd`.
 *
 * A date string, a full ISO datetime (its date part), or epoch milliseconds as
 * a number or a numeric string, sliced in UTC because the reference server
 * writes midnight UTC. Blank for anything else.
 */
internal fun normalizeLockedDate(raw: JsonElement?): String {
    val text = (raw as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.trim().orEmpty()
    if (text.isEmpty()) return ""
    ISO_DATE_PREFIX.find(text)?.let { return it.groupValues[1] }
    val millis = text.toDoubleOrNull()?.toLong()?.takeIf { it > 0 } ?: return ""
    return Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date.toString()
}

private val ISO_DATE_PREFIX = Regex("^(\\d{4}-\\d{2}-\\d{2})(?:$|T)")

// -- readers ----------------------------------------------------------------------

/** `data` bare, or out of the `{ value: … }` wrapper the hub's own routes use. */
internal fun JsonElement.unwrapValue(): JsonElement =
    (this as? JsonObject)?.get("value")?.takeIf { it is JsonObject } ?: this

private fun JsonPrimitive?.numberOrNull(): Double? =
    this?.takeUnless { it is JsonNull }?.contentOrNull?.trim()?.toDoubleOrNull()

private fun JsonPrimitive?.millisOrNull(): Long? = numberOrNull()?.toLong()?.takeIf { it > 0 }

/** A bucket's failure as words: the message itself, an error's `message`, or the raw value. */
private fun JsonElement.errorText(): String = when (this) {
    is JsonPrimitive -> contentOrNull.orEmpty()
    is JsonObject -> ((this["message"] ?: this["error"]) as? JsonPrimitive)?.contentOrNull ?: toString()
    else -> toString()
}

private fun List<String>.csvOrNull(): String? =
    map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.joinToString(",")
