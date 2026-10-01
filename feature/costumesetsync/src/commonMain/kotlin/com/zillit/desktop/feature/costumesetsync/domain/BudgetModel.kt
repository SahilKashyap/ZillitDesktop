package com.zillit.desktop.feature.costumesetsync.domain

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.abs
import kotlin.math.roundToLong

/** One account the line form can fill in from a code. */
data class BudgetAccount(val code: String, val name: String)

/** A heading over a block of lines: a scene, a character, a department. */
data class BudgetGroup(val key: String, val title: String, val lines: List<Rec>)

/** A block of one account's lines in the sheet. */
data class AccountBlock(val code: String, val name: String, val lines: List<Rec>)

/** A department row of the top sheet. */
data class TopRow(val key: String, val code: String, val title: String, val lines: List<Rec>)

/** The add / edit form. Numbers stay strings while typed, so a half-typed "6." is not lost. */
data class BudgetForm(
    val category: String = "PURCHASE",
    val amount: String = "",
    val description: String = "",
    val date: String = "",
    val characterId: String = "",
    val sceneId: String = "",
    val vendorId: String = "",
    val accountCode: String = "",
    val accountName: String = "",
    val payee: String = "",
    val quantity: String = "",
    val unit: String = "",
    val multiplier: String = "1",
    val rate: String = "",
    val currency: String = "",
)

/**
 * The budget's model: the account-code chart, per-currency sums, the add/edit form and the
 * groupings the sheet and the printed top sheet read in — the web's `lib/budget.js`
 * (itself a port of the reference's `budgetAccounts.ts`, `BudgetSheet.tsx`, `BudgetDocument.tsx`).
 */
object BudgetModel {
    /** The wardrobe accounts of a UK feature budget (Movie Magic chart, 30-000 WARDROBE). */
    val WARDROBE_ACCOUNTS = listOf(
        BudgetAccount("30-001", "COSTUME DESIGNER"), BudgetAccount("30-002", "COSTUME SUPERVISOR"),
        BudgetAccount("30-003", "ASSISTANT COSTUME DESIGNER"), BudgetAccount("30-004", "COSTUME STANDBYS"),
        BudgetAccount("30-005", "COSTUME ASSISTANTS"), BudgetAccount("30-020", "CROWD COSTUME SUPERVISOR"),
        BudgetAccount("30-026", "COSTUME DAILIES"), BudgetAccount("30-040", "WARDROBE CLEANING"),
        BudgetAccount("30-080", "WARDROBE CONSUMABLES"), BudgetAccount("30-090", "WARDROBE PURCHASES & RENTALS"),
        BudgetAccount("30-093", "WARDROBE LOSS & DAMAGE"),
    )

    /** Department heads of a UK feature chart of accounts ("30-000 - WARDROBE"). */
    val DEPARTMENTS: Map<String, String> = mapOf(
        "11" to "STORY RIGHTS & CONTINUITY", "12" to "PRODUCERS", "13" to "DIRECTOR", "14" to "CAST", "15" to "ATL TRAVEL & LIVING",
        "19" to "ATL - FRINGES", "20" to "PRODUCTION STAFF", "21" to "SUPPORTING ARTISTS", "22" to "SET DESIGN", "23" to "SET CONSTRUCTION",
        "25" to "SET OPERATIONS", "26" to "SPECIAL EFFECTS", "27" to "SET DRESSING", "28" to "PROPERTY", "29" to "ACTION VEHICLES/ANIMALS",
        "30" to "WARDROBE", "31" to "HAIR & MAKEUP", "32" to "LIGHTING", "33" to "CAMERA", "34" to "PRODUCTION SOUND", "35" to "TRANSPORTATION",
        "36" to "LOCATIONS", "37" to "DAILIES & DATA MANAGEMENT", "38" to "BTL TRAVEL & LIVING", "39" to "OVERTIME", "40" to "OVERSEAS UNIT",
        "42" to "STAGES / OFFICES / STORES", "43" to "SECOND UNIT", "44" to "VISUAL EFFECTS PRODUCTION", "50" to "POST PRODUCTION MANAGEMENT",
        "51" to "EDITING", "52" to "PICTURE POST PRODUCTION", "53" to "SOUND POST PRODUCTION", "54" to "VFX", "55" to "MUSIC",
        "56" to "CLIPS & CLEARANCES", "57" to "DELIVERABLES", "64" to "GENERAL EXPENSES", "65" to "PUBLICITY", "66" to "FINANCE & LEGAL",
        "67" to "INSURANCE", "70" to "RESIDUALS", "71" to "FINANCE FEE", "73" to "BRIDGE FEE", "74" to "BOND FEE", "75" to "CONTINGENCY",
    )

    val BUDGET_UNITS = listOf("Weeks", "Week", "Days", "Day", "Hours", "Allow", "Fee", "Flat", "CAP", "Each", "Set", "%")
    val CURRENCIES = listOf("GBP", "USD", "EUR", "INR", "BGN", "AED", "CAD", "AUD")
    const val OTHER = "OTHER"

    /** The key a line lands under when it is tagged to no scene / character. */
    const val NONE = "__none__"

    /** The Budget tabs; `?tab=` may name one (the whole-budget share link uses `full`). */
    val BUDGET_TABS = listOf("all", "scenes", "characters", "accounts", "full")

    /** The categories a sheet upload offers when `/meta` has none. */
    val EXPENSE_CATEGORY_FALLBACK = listOf("PURCHASE", "RENTAL", "LAUNDRY", "TAILORING", "ACCESSORIES", "DAMAGE", "OTHER")

    private val CODE_SPLIT = Regex("[-.\\s]")

    /** `"30-001"` → head `30`, title `30-000 - WARDROBE`; null for no code. A code off the chart is its own department. */
    fun departmentOf(code: String?): Pair<String, String>? {
        val c = code.orEmpty().trim()
        if (c.isEmpty()) return null
        val parts = c.split(CODE_SPLIT)
        val head = parts[0]
        val zeros = (parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: "000").map { '0' }.joinToString("")
        return head to "$head-$zeros${DEPARTMENTS[head]?.let { " - $it" }.orEmpty()}"
    }

    /** A line's own currency, or the production's when it has none. */
    fun lineCurrency(line: Rec, fallback: String): String = line.str("currency").ifEmpty { fallback }

    /** Money as the reference's `fmtMoney` prints a budget; an empty amount reads 0. */
    fun fmtAmount(n: Double?, currency: String): String = fmtMoney(n ?: 0.0, currency)

    /** Lines summed per currency, so pounds and rupees are never added together: "£58,450 + ₹2,000". */
    fun sumByCurrency(lines: List<Rec>, fallback: String): String {
        val by = LinkedHashMap<String, Double>()
        lines.forEach { l -> by[lineCurrency(l, fallback)] = (by[lineCurrency(l, fallback)] ?: 0.0) + l.double("amount") }
        if (by.isEmpty()) return fmtAmount(0.0, fallback)
        return by.entries.joinToString(" + ") { fmtAmount(it.value, it.key) }
    }

    /** A line's name: its description, else its account, payee or category. */
    fun lineTitle(e: Rec, fallback: String): String =
        e.str("description").trim().ifEmpty {
            e.first("account_name", "payee", "account_code").ifEmpty { humanize(e.str("category")).ifEmpty { fallback } }
        }

    // -- the form ------------------------------------------------------------------------------

    fun blankForm(currency: String, today: String): BudgetForm = BudgetForm(date = today, currency = currency)

    fun toForm(e: Rec, currency: String): BudgetForm = BudgetForm(
        category = e.str("category"),
        amount = if (e.has("amount")) numText(e.double("amount")) else "",
        description = e.str("description"),
        date = DayKeys.of(e.long("date")),
        characterId = e.str("character_id"),
        sceneId = e.str("scene_id"),
        vendorId = e.str("vendor_id"),
        accountCode = e.str("account_code"),
        accountName = e.str("account_name"),
        payee = e.str("payee"),
        quantity = if (e.has("quantity")) numText(e.double("quantity")) else "",
        unit = e.str("unit"),
        multiplier = if (e.has("multiplier")) numText(e.double("multiplier")) else "1",
        rate = if (e.has("rate")) numText(e.double("rate")) else "",
        currency = e.str("currency").ifEmpty { currency },
    )

    /** The form an Add opens on: whatever it last held, with only the line's own figures cleared. */
    fun reopenForAdd(prev: BudgetForm, currency: String): BudgetForm =
        prev.copy(amount = "", description = "", quantity = "", rate = "", multiplier = "1", currency = prev.currency.ifEmpty { currency })

    /** After an add, the next line usually sits in the same account for the same person: those stay. */
    fun formAfterAdd(f: BudgetForm, currency: String, today: String): BudgetForm = blankForm(currency, today).copy(
        accountCode = f.accountCode, accountName = f.accountName, payee = f.payee, sceneId = f.sceneId,
        characterId = f.characterId, category = f.category, currency = f.currency,
    )

    fun num(text: String): Double? = text.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }

    /** Amt × X × Rate, once Amt and Rate are both filled in (X defaults to 1); otherwise null. */
    fun subtotalOf(f: BudgetForm): Double? {
        val q = num(f.quantity) ?: return null
        val r = num(f.rate) ?: return null
        return (q * (num(f.multiplier) ?: 1.0) * r * HUNDRED).roundToLong() / HUNDRED
    }

    /** The amount a line will save: the subtotal when it can be worked out, else what was typed. */
    fun amountOf(f: BudgetForm): Double? = subtotalOf(f) ?: num(f.amount)

    /**
     * The form → the service's body. A currency equal to the production's own is sent as '' —
     * the line then follows the production if its currency changes. A blank date is left OUT,
     * not sent as 0: the service dates a new line today and leaves an edited line's date alone.
     */
    fun toExpenseBody(f: BudgetForm, productionCurrency: String): JsonObject {
        val q = num(f.quantity)
        return buildJsonObject {
            put("category", JsonPrimitive(f.category))
            put("amount", JsonPrimitive(amountOf(f) ?: 0.0))
            put("description", JsonPrimitive(f.description.trim()))
            if (f.date.isNotEmpty()) put("date", JsonPrimitive(DayKeys.toMs(f.date)))
            put("character_id", f.characterId.ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull)
            put("scene_id", f.sceneId.ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull)
            put("vendor_id", f.vendorId.ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull)
            put("account_code", JsonPrimitive(f.accountCode))
            put("account_name", JsonPrimitive(f.accountName))
            put("payee", JsonPrimitive(f.payee))
            put("unit", JsonPrimitive(f.unit))
            put("quantity", q?.let(::JsonPrimitive) ?: JsonNull)
            put("multiplier", if (q != null) JsonPrimitive(num(f.multiplier) ?: 1.0) else JsonNull)
            put("rate", num(f.rate)?.let(::JsonPrimitive) ?: JsonNull)
            put("currency", JsonPrimitive(if (f.currency == productionCurrency) "" else f.currency))
        }
    }

    /**
     * One ticked line of an uploaded sheet → an `/expenses/import` line. A row the sheet gave
     * no date is sent WITHOUT one, so the service saves it with today's date; 0 would be stored as "no date".
     */
    fun toImportLine(r: Rec, edited: ImportEdit, productionCurrency: String): JsonObject = buildJsonObject {
        put("category", JsonPrimitive(edited.category))
        put("amount", JsonPrimitive(r.double("amount")))
        put("description", JsonPrimitive(edited.description.trim()))
        if (r.long("date") != 0L) put("date", JsonPrimitive(r.long("date")))
        fun id(key: String) = r.str(key).ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull
        put("scene_id", id("scene_id"))
        put("character_id", id("character_id"))
        put("vendor_id", id("vendor_id"))
        put("costume_id", id("costume_id"))
        put("account_code", JsonPrimitive(r.str("account_code")))
        put("account_name", JsonPrimitive(r.str("account_name")))
        put("payee", JsonPrimitive(r.str("payee")))
        put("quantity", if (r.has("quantity")) JsonPrimitive(r.double("quantity")) else JsonNull)
        put("unit", JsonPrimitive(r.str("unit")))
        put("multiplier", if (r.has("multiplier")) JsonPrimitive(r.double("multiplier")) else JsonNull)
        put("rate", if (r.has("rate")) JsonPrimitive(r.double("rate")) else JsonNull)
        put("currency", JsonPrimitive(r.str("currency").ifEmpty { productionCurrency }))
    }

    /** What the user may change on an uploaded row before importing. */
    data class ImportEdit(val description: String, val category: String)

    // -- groupings -----------------------------------------------------------------------------

    private fun byCode(a: String, b: String): Int = when {
        a.isEmpty() && b.isEmpty() -> 0
        a.isEmpty() -> 1
        b.isEmpty() -> -1
        else -> naturalCompare(a, b)
    }

    /** `localeCompare(…, {numeric: true})`: digit runs compare as numbers. */
    fun naturalCompare(a: String, b: String): Int {
        val ra = Regex("\\d+|\\D+").findAll(a).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]
            val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                (x.toBigInteger().compareTo(y.toBigInteger())).takeIf { it != 0 } ?: x.length.compareTo(y.length)
            } else {
                x.compareTo(y, ignoreCase = true)
            }
            if (c != 0) return c
        }
        return ra.size.compareTo(rb.size)
    }

    /** A group's lines by account code (uncoded ones last, under their category), one block per account. */
    fun accountsOf(lines: List<Rec>): List<AccountBlock> {
        val acc = LinkedHashMap<String, AccountBlock>()
        lines.forEach { l ->
            val code = l.str("account_code").trim()
            val key = code.ifEmpty { "cat:${l.str("category")}" }
            val cur = acc[key] ?: AccountBlock(code, l.str("account_name").ifEmpty { if (code.isEmpty()) humanize(l.str("category")) else "" }, emptyList())
            val name = cur.name.ifEmpty { l.str("account_name") }
            acc[key] = cur.copy(name = name, lines = cur.lines + l)
        }
        return acc.values.sortedWith { a, b -> byCode(a.code, b.code).takeIf { it != 0 } ?: a.name.compareTo(b.name, ignoreCase = true) }
    }

    /** Within an account, lines in the order they were entered, grouped under "Name:" per payee (no payee first). */
    fun payeesOf(lines: List<Rec>): List<Pair<String, List<Rec>>> {
        val entered = lines.sortedBy { it.long("created").takeIf { c -> c != 0L } ?: it.long("date") }
        val out = LinkedHashMap<String, List<Rec>>()
        entered.forEach { l -> val k = l.str("payee").trim(); out[k] = (out[k] ?: emptyList()) + l }
        return out.entries.map { it.key to it.value }.sortedBy { if (it.first.isEmpty()) 0 else 1 }
    }

    /** Department by department (30-000 - WARDROBE), in chart order; uncoded last. */
    fun departmentGroups(lines: List<Rec>, noCodeTitle: String): List<BudgetGroup> {
        val by = LinkedHashMap<String, BudgetGroup>()
        lines.forEach { e ->
            val d = departmentOf(e.str("account_code"))
            val key = d?.first ?: NONE
            val g = by[key] ?: BudgetGroup(key, d?.second ?: noCodeTitle, emptyList())
            by[key] = g.copy(lines = g.lines + e)
        }
        return by.values.sortedWith { a, b ->
            when {
                a.key == NONE -> 1
                b.key == NONE -> -1
                else -> naturalCompare(a.key, b.key)
            }
        }
    }

    // -- the top sheet -------------------------------------------------------------------------

    /** A section of a feature budget's top sheet, by account head. */
    data class Section(val key: String, val from: Int, val to: Int, val atl: Boolean = false)

    val SECTIONS = listOf(
        Section("atl", 11, 19, atl = true), Section("production", 20, 49), Section("post", 50, 63), Section("other", 64, 69),
    )

    /** One row per department, in chart order; uncoded spend comes last, by category. */
    fun topSheetRows(expenses: List<Rec>): List<TopRow> {
        val by = LinkedHashMap<String, TopRow>()
        expenses.forEach { e ->
            val head = e.str("account_code").trim().split(CODE_SPLIT)[0]
            val key = head.ifEmpty { "cat:${e.str("category")}" }
            val row = by[key] ?: TopRow(
                key, if (head.isNotEmpty()) "$head-000" else "",
                if (head.isNotEmpty()) DEPARTMENTS[head] ?: "OTHER" else humanize(e.str("category")).uppercase(), emptyList(),
            )
            by[key] = row.copy(lines = row.lines + e)
        }
        return by.values.sortedWith { a, b ->
            when {
                a.code.isEmpty() -> 1
                b.code.isEmpty() -> -1
                else -> naturalCompare(a.code, b.code)
            }
        }
    }

    private fun headOf(row: TopRow): Int = row.code.substringBefore('-').toIntOrNull() ?: 0

    fun inSection(row: TopRow, s: Section): Boolean = row.code.isNotEmpty() && headOf(row) in s.from..s.to

    /** The top sheet as plain text, for a message: the same rows, in the same order, as the printed document. */
    fun topSheetText(
        projectName: String,
        expenses: List<Rec>,
        currency: String,
        sectionTitle: (String) -> String,
        grandTotal: String,
        budgetWord: String,
    ): String {
        val rows = topSheetRows(expenses)
        fun line(label: String, lines: List<Rec>) = "$label  ${sumByCurrency(lines, currency)}"
        val out = mutableListOf("${projectName.uppercase()} · ${budgetWord.ifEmpty { "BUDGET" }.uppercase()}", "")
        SECTIONS.forEach { s ->
            val inS = rows.filter { inSection(it, s) }
            if (inS.isEmpty()) return@forEach
            inS.forEach { out.add(line("${it.code} ${it.title}", it.lines)) }
            out.add(line(sectionTitle(s.key), inS.flatMap { it.lines }))
            out.add("")
        }
        rows.filter { r -> SECTIONS.none { inSection(r, it) } }.forEach { out.add(line(if (it.code.isNotEmpty()) "${it.code} ${it.title}" else it.title, it.lines)) }
        out.add("")
        out.add(line(grandTotal.ifEmpty { "GRAND TOTAL" }.uppercase(), expenses))
        return out.joinToString("\n")
    }

    // -- numbers -------------------------------------------------------------------------------

    private const val HUNDRED = 100.0

    /** A number as the form shows it: whole numbers without ".0". */
    fun numText(d: Double): String = if (d == d.roundToLong().toDouble()) d.roundToLong().toString() else d.toString()

    /** The sheet's `Intl.NumberFormat` with at most two decimals and grouping; empty for null. */
    fun fmtNum(d: Double?): String {
        if (d == null) return ""
        val cents = (abs(d) * HUNDRED).roundToLong()
        val whole = cents / HUNDRED.toLong()
        val frac = (cents % HUNDRED.toLong()).toString().padStart(2, '0').trimEnd('0')
        val grouped = whole.toString().reversed().chunked(3).joinToString(",").reversed()
        return (if (d < 0 && cents > 0) "-" else "") + grouped + if (frac.isNotEmpty()) ".$frac" else ""
    }
}
