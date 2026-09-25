package com.zillit.desktop.feature.invoices.data

import com.zillit.desktop.feature.invoices.domain.CarriedFields
import com.zillit.desktop.feature.invoices.domain.CodedLine
import com.zillit.desktop.feature.invoices.domain.Company
import com.zillit.desktop.feature.invoices.domain.EntryCoding
import com.zillit.desktop.feature.invoices.domain.EntryHeader
import com.zillit.desktop.feature.invoices.domain.EntryTotals
import com.zillit.desktop.feature.invoices.domain.InvoiceFormat
import com.zillit.desktop.feature.invoices.domain.InvoiceProjectSettings
import com.zillit.desktop.feature.invoices.domain.PeriodLock
import com.zillit.desktop.feature.invoices.domain.PoDeliveryAddress
import com.zillit.desktop.feature.invoices.domain.QueryMessage
import com.zillit.desktop.feature.invoices.domain.QueryThread
import com.zillit.desktop.feature.invoices.domain.QuickEntry
import com.zillit.desktop.feature.invoices.domain.TaxLine
import com.zillit.desktop.feature.invoices.domain.TaxType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// -- coded lines ------------------------------------------------------------------

/**
 * An invoice's saved `line_items` — an array, or the same array JSON-encoded
 * into a string. The persisted reclaimable-tax line (`is_tax`) is split off;
 * the web keeps it out of the editable rows too.
 */
internal fun parseCodedLines(obj: JsonObject): Pair<List<CodedLine>, TaxLine?> {
    val rows = obj.arrayField("line_items").mapNotNull { it as? JsonObject }
    val tax = rows.firstOrNull { it.isTaxLine() }?.let { line ->
        TaxLine(
            account = line.text("account"),
            amount = line.number("total") ?: line.number("unit_price") ?: 0.0,
            overridden = true,
            trackingCodes = parseTrackingCodes(line["tracking_codes"]),
            tags = parseTags(line["tags"]),
        )
    }
    val lines = rows.filterNot { it.isTaxLine() }.mapIndexed { index, line -> parseCodedLine(line, index) }
    return lines to tax
}

/**
 * One saved line; an id-less row gets a stable one from its position. Its
 * Layers, tags, custom fields and rental dates are read onto the line itself,
 * so a save carries them back whether or not the row had an id (ZL parity H3).
 */
internal fun parseCodedLine(line: JsonObject, index: Int): CodedLine {
    val amount = line.number("total") ?: line.number("amount") ?: 0.0
    return CodedLine(
        id = line.text("id").ifBlank { "line-$index" },
        description = line.text("description"),
        account = line.text("account", "nominal_code"),
        amount = amount,
        quantity = line.number("quantity") ?: 1.0,
        unitPrice = line.number("unit_price", "unitPrice") ?: amount,
        taxRate = parseTaxRate(line["tax_rate"] ?: line["taxRate"]),
        taxType = line.text("tax_type", "taxType"),
        expenditureType = line.text("expenditure_type", "expenditureType"),
        splitParentId = line.text("split_parent_id", "splitParentId").ifBlank { null },
        trackingCodes = parseTrackingCodes(line["tracking_codes"]),
        tags = parseTags(line["tags"]),
        carried = parseCarried(line),
    )
}

/** `tracking_codes` — set id → code, as an object or the same object JSON-encoded; non-text values are skipped. */
internal fun parseTrackingCodes(raw: JsonElement?): Map<String, String> {
    val obj = raw as? JsonObject ?: (raw as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        ?.takeIf { it.startsWith("{") }
        ?.let { runCatching { invoicesJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        ?: return emptyMap()
    return obj.mapNotNull { (set, value) ->
        (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.takeIf { it.isNotBlank() }?.let { set to it }
    }.toMap()
}

/** `tags` — the strings of the array; anything else is none. */
internal fun parseTags(raw: JsonElement?): List<String> =
    (raw as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

/** What a line carries but this client never edits, kept as the JSON it was read as. */
internal fun parseCarried(line: JsonObject): CarriedFields {
    val custom = line["custom_fields"] as? JsonArray ?: JsonArray(emptyList())
    return CarriedFields(
        customFieldsJson = custom.toString(),
        rentalStartJson = (line["rental_start"] ?: JsonNull).toString(),
        rentalEndJson = (line["rental_end"] ?: JsonNull).toString(),
        customFields = customFieldValues(custom),
    )
}

/**
 * `custom_fields` as name → value — each entry is a field, or a group of them
 * under `fields` (`EntryDetailModal.jsx:1033-1054`). The first value for a
 * name wins.
 */
private fun customFieldValues(custom: JsonArray): List<Pair<String, String>> {
    val seen = LinkedHashMap<String, String>()
    custom.mapNotNull { it as? JsonObject }.forEach { entry ->
        val fields = (entry["fields"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: listOf(entry)
        fields.forEach { field ->
            val name = field.text("name")
            if (name.isNotBlank() && name !in seen) seen[name] = field.text("value")
        }
    }
    return seen.toList()
}

/** A PO's `delivery_address`: an object with its parts, or one plain string. */
internal fun parseDeliveryAddress(raw: JsonElement?): PoDeliveryAddress? = when (raw) {
    is JsonObject -> PoDeliveryAddress(
        name = raw.text("name"),
        lines = listOf("line1", "line2", "city", "state", "postalCode")
            .map { raw.text(it) }.filter { it.isNotBlank() }.joinToString(", "),
        email = raw.text("email"),
        phone = raw.text("phone").takeIf { it.isNotBlank() }
            ?.let { "${raw.text("phoneCode")} $it".trim() }.orEmpty(),
    )
    is JsonPrimitive -> raw.takeUnless { it is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { PoDeliveryAddress(lines = it) }
    else -> null
}

/** `20`, `"20"` and `"20%"` are all 20 — the web's `parseTaxRate`; anything else is no rate. */
internal fun parseTaxRate(raw: JsonElement?): Double? {
    val primitive = raw as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    primitive.doubleOrNull?.let { return it }
    return LEADING_NUMBER.find(primitive.content.trim())?.value?.toDoubleOrNull()
}

private val LEADING_NUMBER = Regex("^-?\\d+(\\.\\d+)?")

private fun JsonObject.isTaxLine(): Boolean = flag("is_tax") == true || flag("isTax") == true

// -- the entry write ------------------------------------------------------------

/**
 * What Save, Post and Submit for Review write — `PATCH /invoices/:id`.
 *
 * The header is the web's `buildInvoiceHeaderPayload`: a blank date is left
 * out rather than cleared, the currency only goes when one was picked, and a
 * blank company, bank or episode is an explicit null. Every persist path
 * writes the whole header, so an edit survives whichever button is pressed
 * (ZL-20476).
 *
 * Each line is written in the web's shape (`serializeLineItems`,
 * `EntryDetailModal.jsx:898-915`): its Layers, tags, custom fields and rental
 * dates are the line's own, as read or as edited here. A line built without
 * them ([CodedLine.carried] null) falls back to the saved row with its id
 * ([savedLinesJson]), a new child to its parent's Layers and tags.
 *
 * [amounts] — only when the currency was changed — rewrites the invoice's own
 * net / tax / gross from the coded totals (`amountsPayload`, `invoicePayload.js:112`).
 */
@Suppress("LongParameterList") // One argument per part of the web's payload.
internal fun entryUpdateBody(
    header: EntryHeader,
    lines: List<CodedLine>?,
    tax: TaxLine? = null,
    taxAmount: Double = 0.0,
    savedLinesJson: String = "",
    chart: Set<String> = emptySet(),
    status: String? = null,
    amounts: EntryTotals? = null,
): JsonObject = buildJsonObject {
    put("invoice_number", JsonPrimitive(header.invoiceNumber))
    InvoiceFormat.parseDateInput(header.invoiceDate)?.let { put("invoice_date", JsonPrimitive(it)) }
    InvoiceFormat.parseDateInput(header.dueDate)?.let { put("due_date", JsonPrimitive(it)) }
    InvoiceFormat.parseDateInput(header.effectiveDate)?.let { put("effective_date", JsonPrimitive(it)) }
    put("pay_method", JsonPrimitive(header.payWire))
    header.currency.trim().takeIf { it.isNotEmpty() }?.let { put("currency", JsonPrimitive(it)) }
    put("company_id", header.companyId.nullIfBlank())
    put("bank_id", header.bankId.nullIfBlank())
    put("episode", header.episode.nullIfBlank())
    amounts?.let {
        put("net_amount", JsonPrimitive(EntryCoding.round2(it.net)))
        put("tax_amount", JsonPrimitive(EntryCoding.round2(it.tax)))
        put("gross_amount", JsonPrimitive(EntryCoding.round2(it.gross)))
    }
    status?.let { put("status", JsonPrimitive(it)) }
    if (lines != null) {
        val saved = savedLines(savedLinesJson)
        put(
            "line_items",
            buildJsonArray {
                lines.forEach { add(lineWire(it, saved, chart)) }
                if (tax != null) add(taxLineWire(tax, taxAmount, chart))
            },
        )
    }
}

private fun savedLines(json: String): List<JsonObject> =
    runCatching { invoicesJson.parseToJsonElement(json) as? JsonArray }.getOrNull()
        ?.mapNotNull { it as? JsonObject }
        .orEmpty()

private fun lineWire(line: CodedLine, saved: List<JsonObject>, chart: Set<String>): JsonObject {
    val carried = line.carried
    val own = if (carried == null) saved.firstOrNull { it.text("id") == line.id && !it.isTaxLine() } else null
    // A line built without its extras: a child takes its parent's saved Layers and tags.
    val inherited = own ?: line.splitParentId?.takeIf { carried == null }
        ?.let { parent -> saved.firstOrNull { it.text("id") == parent } }
    return buildJsonObject {
        put("id", JsonPrimitive(line.id))
        put("description", JsonPrimitive(line.description))
        put("quantity", JsonPrimitive(line.quantity))
        put("unit_price", JsonPrimitive(line.unitPrice))
        put("total", JsonPrimitive(line.amount))
        put("account", JsonPrimitive(EntryCoding.wrapNominal(line.account, chart)))
        put("expenditure_type", JsonPrimitive(line.expenditureType))
        put("tax_type", line.taxType.nullIfBlank())
        put("tax_rate", line.taxRate?.let(::JsonPrimitive) ?: JsonNull)
        put("rental_start", carried?.rentalStartJson?.let(::jsonOrNull) ?: own?.get("rental_start") ?: JsonNull)
        put("rental_end", carried?.rentalEndJson?.let(::jsonOrNull) ?: own?.get("rental_end") ?: JsonNull)
        put("split_parent_id", line.splitParentId.nullIfBlank())
        put(
            "tracking_codes",
            if (carried != null || line.trackingCodes.isNotEmpty()) {
                codesJson(line.trackingCodes)
            } else {
                inherited?.get("tracking_codes") as? JsonObject ?: JsonObject(emptyMap())
            },
        )
        put(
            "tags",
            if (carried != null || line.tags.isNotEmpty()) {
                tagsJson(line.tags)
            } else {
                inherited?.get("tags") as? JsonArray ?: JsonArray(emptyList())
            },
        )
        put(
            "custom_fields",
            carried?.customFieldsJson?.let(::jsonOrNull) as? JsonArray
                ?: own?.get("custom_fields") as? JsonArray ?: JsonArray(emptyList()),
        )
    }
}

private fun codesJson(codes: Map<String, String>): JsonObject = JsonObject(codes.mapValues { JsonPrimitive(it.value) })

private fun tagsJson(tags: List<String>): JsonArray = JsonArray(tags.map { JsonPrimitive(it) })

private fun jsonOrNull(text: String): JsonElement? = runCatching { invoicesJson.parseToJsonElement(text) }.getOrNull()

/**
 * The consolidated reclaimable-tax line, in the web's shape, with the tax
 * row's own Layers and tags — read off the saved row when the view opened,
 * so they are what is on screen (`taxLineMeta`).
 */
private fun taxLineWire(tax: TaxLine, amount: Double, chart: Set<String>): JsonObject {
    return buildJsonObject {
        put("is_tax", JsonPrimitive(true))
        put("description", JsonPrimitive(""))
        put("quantity", JsonPrimitive(1))
        put("unit_price", JsonPrimitive(amount))
        put("total", JsonPrimitive(amount))
        put("account", JsonPrimitive(EntryCoding.wrapNominal(tax.account, chart)))
        put("expenditure_type", JsonPrimitive(""))
        put("tax_type", JsonNull)
        put("tax_rate", JsonNull)
        put("rental_start", JsonNull)
        put("rental_end", JsonNull)
        put("split_parent_id", JsonNull)
        put("tracking_codes", codesJson(tax.trackingCodes))
        put("tags", tagsJson(tax.tags))
        put("custom_fields", JsonArray(emptyList()))
    }
}

/**
 * Handing an invoice on — `{assigned_to, assignment_reason, updated_at}`, as
 * both `EntryPage.jsx:923` and `EntryDetailModal.jsx:352-356` send it.
 */
internal fun assignBody(userId: String, reason: String, nowMs: Long): JsonObject = buildJsonObject {
    put("assigned_to", JsonPrimitive(userId))
    put("assignment_reason", JsonPrimitive(reason))
    put("updated_at", JsonPrimitive(nowMs))
}

/** A bulk "Submit for Review": the status, and when (`EntryPage.jsx`). */
internal fun underReviewBody(nowMs: Long): JsonObject = buildJsonObject {
    put("status", JsonPrimitive("under_review"))
    put("updated_at", JsonPrimitive(nowMs))
}

// -- Quick Entry -------------------------------------------------------------------

/**
 * A single-code invoice straight to ready-to-pay — the web's `quickPost`: the
 * gross is net plus the picked rate, dated today, paid by BACs.
 */
internal fun quickEntryBody(entry: QuickEntry): JsonObject = buildJsonObject {
    val tax = entry.net * (entry.taxRate ?: 0.0) / PERCENT
    val reference = entry.reference.trim()
    put("reference", JsonPrimitive(reference))
    put("vendor_id", JsonPrimitive(entry.vendorId))
    put("description", JsonPrimitive("Quick entry — $reference"))
    put("net_amount", JsonPrimitive(entry.net))
    put("tax_amount", JsonPrimitive(tax))
    put("gross_amount", JsonPrimitive(entry.net + tax))
    put("vat_rate", entry.taxRate?.let(::JsonPrimitive) ?: JsonNull)
    put("nominal_code", JsonPrimitive(entry.nominal.trim()))
    put("cost_centre", JsonPrimitive(entry.costCentre.trim()))
    put("invoice_date", JsonPrimitive(entry.today))
    put("due_date", JsonPrimitive(entry.today))
    put("effective_date", entry.effectiveDate.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
    put("pay_method", JsonPrimitive("bacs"))
    put("status", JsonPrimitive("ready_to_pay"))
    put("tags", tagsJson(entry.tags))
}

// -- Production Setup, the close boundary and the chart ---------------------------

/**
 * The combined project-settings document — `data.settings`, bare or inside
 * the hub's `{ value }` wrapper. Companies, tax types, and the close boundary
 * as the document stores it.
 */
internal fun parseProjectSettings(data: JsonElement?): InvoiceProjectSettings {
    val root = (data as? JsonObject)?.let { (it["value"] as? JsonObject) ?: it }
    val settings = root?.get("settings") as? JsonObject ?: root ?: return InvoiceProjectSettings()
    val locked = normaliseLockedDate(settings["last_cr_locked_date"])
    return InvoiceProjectSettings(
        companies = settings.arrayField("companies").mapNotNull { row ->
            val company = row as? JsonObject ?: return@mapNotNull null
            val id = company.text("id", "_id").ifBlank { return@mapNotNull null }
            Company(id = id, name = company.text("name", "legal_name"), country = company.text("country"))
        },
        taxTypes = settings.arrayField("tax_types").mapNotNull { row ->
            val type = row as? JsonObject ?: return@mapNotNull null
            val identifier = type.text("identifier").ifBlank { return@mapNotNull null }
            TaxType(
                identifier = identifier,
                label = type.text("label", "name").ifBlank { identifier },
                rate = parseTaxRate(type["value"]),
                isRecoverable = type.flag("is_recoverable") == true,
                country = type.text("country", "country_code"),
            )
        },
        lock = PeriodLock(lockedThrough = locked, timeZone = settings.text("timezone")).takeIf { it.isSet },
        assetTags = settings.arrayField("asset_tags").mapNotNull {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf(String::isNotEmpty)
        },
    )
}

/** `GET /cost-reports/lock-period` — `lockedDate` on the read route, `last_cr_locked_date` on the write. */
internal fun parsePeriodLock(data: JsonElement?): PeriodLock? {
    val obj = (data as? JsonObject)?.let { (it["value"] as? JsonObject) ?: it } ?: return null
    val date = normaliseLockedDate(obj["lockedDate"]).ifBlank { normaliseLockedDate(obj["last_cr_locked_date"]) }
    return PeriodLock(lockedThrough = date, timeZone = obj.text("tz"))
}

/** `YYYY-MM-DD`, an ISO instant, or epoch ms (sliced in UTC, as the server writes it) → `YYYY-MM-DD`. */
internal fun normaliseLockedDate(raw: JsonElement?): String {
    val text = (raw as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull?.trim().orEmpty()
    if (text.isEmpty()) return ""
    ISO_DATE.find(text)?.let { return it.groupValues[1] }
    val millis = text.toDoubleOrNull()?.toLong()?.takeIf { it > 0 } ?: return ""
    return Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date.toString()
}

private val ISO_DATE = Regex("^(\\d{4}-\\d{2}-\\d{2})(?:$|T)")

/** Every active code on the chart — what decides whether a typed nominal is new. */
internal fun parseChartCodes(data: JsonElement?): Set<String> =
    rowsOf(data).filter { it.flag("is_active") != false }.map { it.text("code") }.filter { it.isNotBlank() }.toSet()

// -- queries -------------------------------------------------------------------------

/** `GET /account-hub/queries/entity/invoice/:id` — the thread, or nothing yet. */
internal fun parseQueryThread(data: JsonElement?): QueryThread {
    val obj = data as? JsonObject ?: return QueryThread()
    return QueryThread(
        id = obj.text("id", "_id"),
        messages = obj.arrayField("queries").mapNotNull { row ->
            val message = row as? JsonObject ?: return@mapNotNull null
            QueryMessage(
                text = message.text("query"),
                by = message.text("queried_by"),
                atMs = message.dateMs("queried_at"),
            )
        },
    )
}

/** The first message opens the thread on the record; later ones are added to it. */
internal fun queryOpenBody(invoiceId: String, text: String): JsonObject = buildJsonObject {
    put("entity_type", JsonPrimitive(QUERY_ENTITY))
    put("entity_id", JsonPrimitive(invoiceId))
    put("query", JsonPrimitive(text.trim()))
}

internal fun queryAddBody(text: String): JsonObject = buildJsonObject { put("query", JsonPrimitive(text.trim())) }

/** What the hub files an invoice's queries under. */
internal const val QUERY_ENTITY = "invoice"

private fun String?.nullIfBlank(): JsonElement = this?.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull

private const val PERCENT = 100.0
