// One reader per wire shape; the leniency is the point.
@file:Suppress("TooManyFunctions")

package com.zillit.desktop.feature.costreport.data

import com.zillit.desktop.feature.costreport.domain.BudgetVersion
import com.zillit.desktop.feature.costreport.domain.CoaLevel
import com.zillit.desktop.feature.costreport.domain.CoaRow
import com.zillit.desktop.feature.costreport.domain.CostLine
import com.zillit.desktop.feature.costreport.domain.CrCompany
import com.zillit.desktop.feature.costreport.domain.CrCurrency
import com.zillit.desktop.feature.costreport.domain.CurrencyOptions
import com.zillit.desktop.feature.costreport.domain.LedgerItem
import com.zillit.desktop.feature.costreport.domain.LedgerResult
import com.zillit.desktop.feature.costreport.domain.LedgerType
import com.zillit.desktop.feature.costreport.domain.LiveReport
import com.zillit.desktop.feature.costreport.domain.SnapshotCadence
import com.zillit.desktop.feature.costreport.domain.SnapshotDetail
import com.zillit.desktop.feature.costreport.domain.SnapshotHeader
import com.zillit.desktop.feature.costreport.domain.SnapshotTotals
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

// -- lenient readers ---------------------------------------------------------

private fun JsonObject.field(vararg names: String): JsonElement? =
    names.firstNotNullOfOrNull { name -> this[name]?.takeIf { it !is JsonNull } }

internal fun JsonObject.str(vararg names: String): String? =
    (field(*names) as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

/** A number, or a number the server wrote as a string (`"1234.5"`, `"1,234.5"`). */
internal fun JsonObject.num(vararg names: String): Double? = numberOf(field(*names))

internal fun numberOf(element: JsonElement?): Double? {
    val primitive = element as? JsonPrimitive ?: return null
    if (primitive.isString) return primitive.content.trim().replace(",", "").toDoubleOrNull()
    return primitive.doubleOrNull ?: primitive.longOrNull?.toDouble()
}

internal fun JsonObject.epoch(vararg names: String): Long? = epochOf(field(*names))

/**
 * A timestamp as this backend family writes them: epoch ms (number or
 * numeric string), an ISO instant, an ISO local date-time, or `YYYY-MM-DD`.
 */
internal fun epochOf(element: JsonElement?, zone: TimeZone = TimeZone.currentSystemDefault()): Long? {
    val primitive = element as? JsonPrimitive ?: return null
    if (!primitive.isString) return primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
    val text = primitive.content.trim()
    if (text.isEmpty()) return null
    return text.toDoubleOrNull()?.toLong() ?: parseIsoMillis(text, zone)
}

private fun parseIsoMillis(text: String, zone: TimeZone): Long? =
    runCatching { Instant.parse(text).toEpochMilliseconds() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(text).toInstant(zone).toEpochMilliseconds() }.getOrNull()
        ?: runCatching { LocalDate.parse(text.take(DATE_LENGTH)).atStartOfDayIn(zone).toEpochMilliseconds() }
            .getOrNull()

private const val DATE_LENGTH = 10

private fun JsonObject.flag(vararg names: String): Boolean? {
    val e = field(*names) as? JsonPrimitive ?: return null
    return e.booleanOrNull ?: e.longOrNull?.let { it != 0L } ?: e.content.let { it == "1" || it == "true" }
}

/** A list however it was wrapped: bare, under `items`/`rows`/`value`/`data`, or the `{}` an empty list ships as. */
internal fun rowsOf(element: JsonElement?): List<JsonObject> {
    val array: JsonArray? = when (element) {
        is JsonArray -> element
        is JsonObject -> LIST_KEYS.firstNotNullOfOrNull { element[it] as? JsonArray }
        else -> null
    }
    return array.orEmpty().mapNotNull { it as? JsonObject }
}

private val LIST_KEYS = listOf("items", "rows", "value", "data", "lines")

// -- reference data ----------------------------------------------------------

internal fun parseCoaRows(data: JsonElement?): List<CoaRow> = rowsOf(data).mapNotNull { row ->
    val id = row.str("id", "_id") ?: return@mapNotNull null
    CoaRow(
        id = id,
        code = row.str("code").orEmpty(),
        name = row.str("name").orEmpty(),
        level = CoaLevel.from(row.str("line_type")),
        headId = row.str("head_id"),
        secId = row.str("sec_id"),
        catId = row.str("cat_id"),
        sortOrder = row.num("sort_order")?.toInt(),
        isActive = row.flag("is_active") ?: true,
    )
}

internal fun parseBudgets(data: JsonElement?): List<BudgetVersion> = rowsOf(data).mapNotNull { row ->
    val id = row.str("id", "_id") ?: return@mapNotNull null
    BudgetVersion(
        id = id,
        version = row.str("version").orEmpty(),
        label = row.str("label").orEmpty(),
        status = row.str("status").orEmpty().uppercase(),
    )
}

/** `data.value` is the array; a bare array is tolerated. */
internal fun parseCompanies(data: JsonElement?): List<CrCompany> {
    val rows = rowsOf((data as? JsonObject)?.get("value") ?: data)
    return rows.mapNotNull { row ->
        val id = row.str("id", "_id") ?: return@mapNotNull null
        CrCompany(id, row.str("name").orEmpty())
    }
}

/** `data.value: { currencies: [{code,name,symbol,exr}], default }`, or a bare array of codes/objects. */
internal fun parseCurrencyOptions(data: JsonElement?): CurrencyOptions {
    val value = (data as? JsonObject)?.get("value") ?: data
    return when (value) {
        is JsonArray -> CurrencyOptions(currencies = parseCurrencyList(value))
        is JsonObject -> CurrencyOptions(
            currencies = parseCurrencyList(value["currencies"]),
            defaultCode = value.str("default", "default_currency"),
        )
        else -> CurrencyOptions()
    }
}

internal fun parseCurrencyCatalogue(data: JsonElement?): List<CrCurrency> = parseCurrencyList(data)

private fun parseCurrencyList(element: JsonElement?): List<CrCurrency> =
    (element as? JsonArray).orEmpty().mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> item.content.trim().takeIf { it.isNotEmpty() }?.let { CrCurrency(code = it) }
            is JsonObject -> item.str("code")?.let {
                CrCurrency(code = it, name = item.str("name").orEmpty(), symbol = item.str("symbol").orEmpty())
            }
            else -> null
        }
    }

// -- live ----------------------------------------------------------------------

internal fun parseCostLine(row: JsonObject): CostLine = CostLine(
    account = row.str("account", "code"),
    name = row.str("name"),
    department = row.str("department"),
    budget = row.num("budget") ?: 0.0,
    atp = row.num("atp") ?: 0.0,
    atd = row.num("atd") ?: 0.0,
    po = row.num("po_commits", "po") ?: 0.0,
    card = row.num("card_commits", "card") ?: 0.0,
    cash = row.num("cash_commits", "cash") ?: 0.0,
    pr = row.num("payroll_commits", "pr") ?: 0.0,
    etc = row.num("etc"),
    efc = row.num("efc"),
    variance = row.num("variance"),
    level = row.str("level"),
)

internal fun parseLiveReport(data: JsonElement?): LiveReport {
    val obj = data as? JsonObject
    val lines = rowsOf(obj?.get("lines") ?: data).map(::parseCostLine)
    return LiveReport(
        lines = lines,
        currency = obj?.str("currency"),
        defaultCurrency = obj?.str("default_currency", "defaultCurrency"),
        budgetVersionId = (obj?.get("budget_version") as? JsonObject)?.str("id") ?: obj?.str("budget_version_id"),
        generatedAtMs = obj?.epoch("generated_at"),
    )
}

// -- snapshots -----------------------------------------------------------------

internal fun parseSnapshotHeader(row: JsonObject): SnapshotHeader? {
    val id = row.str("id", "_id") ?: return null
    return SnapshotHeader(
        id = id,
        cadence = SnapshotCadence.from(row.str("cadence")),
        reference = row.str("reference").orEmpty(),
        name = row.str("name").orEmpty(),
        period = row.str("period").orEmpty(),
        postNote = row.str("post_note", "note").orEmpty(),
        periodStartMs = row.epoch("period_start"),
        periodEndMs = row.epoch("period_end"),
        status = row.str("status").orEmpty(),
        currency = row.str("currency"),
        budgetVersionId = row.str("budget_version_id"),
        companyId = row.str("company_id"),
        totalBudget = row.num("total_budget"),
        totalActual = row.num("total_actual"),
        totalCommitted = row.num("total_committed"),
        totalVariance = row.num("total_variance"),
        generatedAtMs = row.epoch("generated_at"),
        generatedBy = row.str("generated_by"),
        publishedAtMs = row.epoch("published_at"),
        publishedBy = row.str("published_by"),
    )
}

/** Newest first by `published_at || generated_at`, whatever order the server used. */
internal fun parseSnapshotList(data: JsonElement?): List<SnapshotHeader> =
    rowsOf(data).mapNotNull(::parseSnapshotHeader).sortedByDescending { it.postedAtMs ?: 0L }

internal fun parseSnapshotTotals(element: JsonElement?): SnapshotTotals {
    val obj = element as? JsonObject ?: return SnapshotTotals()
    return SnapshotTotals(
        budget = obj.num("budget") ?: 0.0,
        atp = obj.num("atp") ?: 0.0,
        atd = obj.num("atd") ?: 0.0,
        po = obj.num("po_commits", "po") ?: 0.0,
        card = obj.num("card_commits", "card") ?: 0.0,
        cash = obj.num("cash_commits", "cash") ?: 0.0,
        pr = obj.num("payroll_commits", "pr") ?: 0.0,
        etc = obj.num("etc") ?: 0.0,
        efc = obj.num("efc") ?: 0.0,
        variance = obj.num("variance"),
    )
}

internal fun parseSnapshotDetail(data: JsonElement?): SnapshotDetail? {
    val obj = data as? JsonObject ?: return null
    val header = parseSnapshotHeader(obj) ?: return null
    return SnapshotDetail(
        header = header,
        totals = parseSnapshotTotals(obj["totals"]),
        lines = rowsOf(obj["lines"]).map(::parseCostLine),
    )
}

// -- ledger --------------------------------------------------------------------

internal fun parseLedgerItem(row: JsonObject): LedgerItem = LedgerItem(
    src = row.str("src", "source").orEmpty().uppercase(),
    type = when (row.str("type")?.lowercase()) {
        "actuals" -> LedgerType.Actuals
        "commits" -> LedgerType.Commits
        else -> null
    },
    account = row.str("account"),
    department = row.str("department"),
    effDateMs = row.epoch("eff_date", "date"),
    invoiceNumber = row.str("invoice_number").orEmpty(),
    poNumber = row.str("po_number").orEmpty(),
    vendor = row.str("vendor", "crew", "supplier").orEmpty(),
    description = row.str("description").orEmpty(),
    currency = row.str("currency"),
    amount = row.num("amount") ?: 0.0,
    compCode = row.str("comp_code").orEmpty(),
)

internal fun parseLedger(data: JsonElement?, requestedCode: String): LedgerResult {
    val obj = data as? JsonObject
    val byType = obj?.get("by_type") as? JsonObject
    val actuals = byType?.get("actuals") as? JsonObject
    val commits = byType?.get("commits") as? JsonObject
    return LedgerResult(
        code = obj?.str("code") ?: requestedCode,
        name = obj?.str("name"),
        currency = obj?.str("currency"),
        defaultCurrency = obj?.str("default_currency"),
        total = obj?.num("total") ?: 0.0,
        count = obj?.num("count")?.toInt() ?: 0,
        actualsTotal = actuals?.num("total"),
        actualsCount = actuals?.num("count")?.toInt(),
        commitsTotal = commits?.num("total"),
        commitsCount = commits?.num("count")?.toInt(),
        items = rowsOf(obj?.get("line_items") ?: obj?.get("items")).map(::parseLedgerItem),
    )
}
