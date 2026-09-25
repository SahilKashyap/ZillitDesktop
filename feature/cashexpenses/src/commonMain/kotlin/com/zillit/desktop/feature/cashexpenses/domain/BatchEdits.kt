package com.zillit.desktop.feature.cashexpenses.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlin.random.Random

/** The receipt facts the batch view edits in place — `BatchReceiptCard`'s fields. */
enum class ClaimField {
    /** `coded_description` — the card's "Receipt name…". */
    Name,

    /** `gross_amount`. */
    Amount,

    /** `category`, one of [ExpenseCategory]'s wire values. */
    Category,

    /** `cost_code` — the receipt coded to one nominal. */
    CostCode,

    /** `description` — labelled "Vendor" on the web's card. */
    Vendor,

    /** `episode` — television productions. */
    Episode,
}

/**
 * The open batch's receipts, as the web's full-page batch view edits them
 * (`PCPostLedgerPage.jsx:759-870`).
 *
 * The receipts are held edited in memory and sent back whole: Save, Verify,
 * Send for Approval, Forward to Accounts and Post each send every receipt of
 * the batch as it now stands — never one receipt's lines on their own, which
 * left the rest of the receipt to whatever the server does with keys it was
 * not sent.
 */
object BatchEdits {

    /** [claim] with one field changed; an amount that does not read as a number changes nothing. */
    fun edit(claim: Claim, field: ClaimField, value: String): Claim = when (field) {
        ClaimField.Name -> claim.copy(codedDescription = value)
        ClaimField.Amount -> value.trim().toDoubleOrNull()?.let { claim.copy(grossAmount = it) } ?: claim
        ClaimField.Category -> claim.copy(category = value.ifBlank { null })
        ClaimField.CostCode -> claim.copy(costCode = value)
        ClaimField.Vendor -> claim.copy(description = value)
        ClaimField.Episode -> claim.copy(episode = value)
    }

    /**
     * The lines the split editor opens with: the receipt's own, or — on a
     * receipt not yet split — one line seeded from its facts, as the web's
     * first "Split into lines" does (`seedClaimLine`): its description, its
     * cost code, its gross and its tax.
     */
    fun seedLines(claim: Claim, newId: () -> String): List<EditorLine> {
        val existing = LineItemEditor.fromWire(claim.lineItems)
        if (existing.isNotEmpty()) return existing
        val percent = LineItemEditor.normaliseTaxRate(claim.taxRate)
        val gross = claim.grossAmount
        val net = if (percent > 0) gross / (1 + percent / WHOLE_PERCENT) else gross
        return listOf(
            EditorLine(
                id = newId(),
                description = claim.description,
                account = claim.costCode.orEmpty(),
                unitPrice = round2(net),
                taxType = claim.taxType.orEmpty(),
                taxRatePercent = percent,
            ),
        )
    }

    /**
     * [claim] carrying [lines] from the split editor.
     *
     * The engine's deduction rows stay as they were — shown, never sent — and
     * the stored lines become exactly what was edited, so the next save sends
     * them.
     */
    fun withLines(claim: Claim, lines: List<EditorLine>, newId: () -> String): Claim {
        val wire = LineItemEditor.toWire(lines, newId).map(::normaliseTaxLine)
        val autos = claim.lineItems.filter { it.autoDeduction }
        return claim.copy(lineItems = wire + autos, rawLines = wire.map(::lineJson))
    }

    /**
     * The consolidated reclaimable-tax line as the web sends it: a pure-tax
     * line whose gross is its tax — `description ""`, one unit, no rate of its
     * own (`PCPostLedgerPage.jsx:855-867`).
     */
    fun normaliseTaxLine(line: ClaimLineItem): ClaimLineItem =
        if (!line.isTax) {
            line
        } else {
            line.copy(
                description = "",
                quantity = 1.0,
                unitPrice = line.total,
                taxRate = null,
                taxType = null,
                taxAmount = 0.0,
            )
        }

    /**
     * The receipts as a payload sends them: every cost code and line nominal
     * the chart does not hold goes out as `[[code]]` — the web's
     * `wrapNominal` — so the ledger creates it. What is held keeps the clean
     * code.
     */
    fun forWire(claims: List<Claim>, wrap: (String?) -> String): List<Claim> = claims.map { claim ->
        claim.copy(
            costCode = claim.costCode?.takeIf { it.isNotBlank() }?.let(wrap)?.ifBlank { null },
            rawLines = claim.rawLines.map { line -> line.wrapAccount(wrap) },
        )
    }

    private fun JsonObject.wrapAccount(wrap: (String?) -> String): JsonObject {
        val account = (this[ACCOUNT] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return this
        return JsonObject(this + (ACCOUNT to JsonPrimitive(wrap(account))))
    }

    /** One line as the save sends it — the web's `editorLinesToWire` keys. */
    fun lineJson(line: ClaimLineItem): JsonObject = buildJsonObject {
        line.id?.let { put("id", JsonPrimitive(it)) }
        put("description", JsonPrimitive(line.description))
        put("quantity", JsonPrimitive(line.quantity))
        put("unit_price", JsonPrimitive(line.unitPrice))
        // GROSS — see ClaimLineItem.total.
        put("total", JsonPrimitive(line.total))
        put("tax_rate", JsonPrimitive(line.taxRate ?: 0.0))
        line.account?.takeIf { it.isNotBlank() }?.let { put(ACCOUNT, JsonPrimitive(it)) }
        line.taxType?.takeIf { it.isNotBlank() }?.let { put("tax_type", JsonPrimitive(it)) }
        line.splitParentId?.let { put("split_parent_id", JsonPrimitive(it)) }
        if (line.isTax) put("is_tax", JsonPrimitive(true))
        line.taxAmount?.let { put("tax_amount", JsonPrimitive(it)) }
        line.trackingCodes?.let { put("tracking_codes", it) }
        line.tags?.let { put("tags", it) }
        line.rentalStart?.let { put("rental_start", it.asWireScalar()) }
        line.rentalEnd?.let { put("rental_end", it.asWireScalar()) }
        line.sortOrder?.let { put("sort_order", JsonPrimitive(it)) }
        line.expenditureType?.takeIf { it.isNotBlank() }?.let { put("expenditure_type", JsonPrimitive(it)) }
    }

    private fun String.asWireScalar(): JsonPrimitive = toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(this)

    /**
     * How long a batch has waited — the audit row's "In queue 3d": whole days,
     * else hours (at least one). Null for a batch with no date or one dated
     * ahead of [nowMillis].
     */
    fun ageInQueue(createdAt: Long?, nowMillis: Long): String? {
        val created = createdAt ?: return null
        val diff = nowMillis - created
        if (created <= 0 || diff < 0) return null
        val days = diff / DAY_MILLIS
        return if (days >= 1) "${days}d" else "${maxOf(1L, diff / HOUR_MILLIS)}h"
    }

    /**
     * A random v4 UUID — the id a new line needs so a split child's
     * `split_parent_id` survives the server's delete-and-reinsert, as the
     * web's editor uuid-ifies its ids.
     */
    fun newUuid(random: Random = Random.Default): String {
        val bytes = random.nextBytes(UUID_BYTES)
        bytes[VERSION_BYTE] = ((bytes[VERSION_BYTE].toInt() and LOW_NIBBLE) or VERSION_4).toByte()
        bytes[VARIANT_BYTE] = ((bytes[VARIANT_BYTE].toInt() and VARIANT_MASK) or VARIANT_RFC).toByte()
        val hex = bytes.joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX).padStart(2, '0') }
        return UUID_CUTS.zipWithNext { from, to -> hex.substring(from, to) }.joinToString("-")
    }

    private const val ACCOUNT = "account"
    private val UUID_CUTS = listOf(0, 8, 12, 16, 20, 32)
    private const val DAY_MILLIS = 86_400_000L
    private const val HOUR_MILLIS = 3_600_000L
    private const val UUID_BYTES = 16
    private const val VERSION_BYTE = 6
    private const val VARIANT_BYTE = 8
    private const val LOW_NIBBLE = 0x0F
    private const val VERSION_4 = 0x40
    private const val VARIANT_MASK = 0x3F
    private const val VARIANT_RFC = 0x80
    private const val BYTE_MASK = 0xFF
    private const val HEX = 16
}
