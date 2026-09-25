package com.zillit.desktop.feature.cardexpenses.data

import com.zillit.desktop.feature.cardexpenses.domain.CardActivation
import com.zillit.desktop.feature.cardexpenses.domain.CardExportRow
import com.zillit.desktop.feature.cardexpenses.domain.CardProvider
import com.zillit.desktop.feature.cardexpenses.domain.ExportFormat
import com.zillit.desktop.feature.cardexpenses.domain.ProcessLine
import com.zillit.desktop.feature.cardexpenses.domain.ProcessSubmission
import com.zillit.desktop.feature.cardexpenses.domain.RequestCap
import com.zillit.desktop.feature.cardexpenses.domain.TaxLineWire
import com.zillit.desktop.feature.cardexpenses.domain.round2
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * The write bodies with rules of their own, kept apart from the repository so
 * each can be pinned by a test without a server.
 */

/**
 * `POST /receipts/:id/save-process` — the web's `buildPayload` plus the
 * action's extras (`ProcessReceiptModal.jsx:371-460`).
 *
 * Coded lines go first, gross, each with its id so a split re-opens as a
 * split; the server-owned lines follow verbatim. The ledger date and the
 * header corrections go only when there is something to say, so an older
 * receipt keeps its own.
 */
internal fun ProcessSubmission.body(): JsonObject = buildJsonObject {
    put(
        "line_items",
        buildJsonArray {
            lines.forEachIndexed { index, line -> add(line.wire(index)) }
            val autos = fixedLines.filter { it.countsInTotal }
            autos.forEach { add(it.raw) }
            taxLine?.let { add(it.wire(lines.size + autos.size)) }
        },
    )
    put("net_amount", JsonPrimitive(round2(net)))
    put("tax_amount", JsonPrimitive(round2(tax)))
    put("gross_amount", JsonPrimitive(round2(gross)))
    description?.trim()?.takeIf { it.isNotEmpty() }?.let { put("description", JsonPrimitive(it)) }
    nominalCode?.trim()?.takeIf { it.isNotEmpty() }?.let { put("nominal_code", JsonPrimitive(it)) }
    effectiveDate?.let { put("effective_date", JsonPrimitive(it)) }
    put("user_id", JsonPrimitive(userId))
    status?.let { put("status", JsonPrimitive(it)) }
    // camelCase on purpose: these two are the web's own spelling on this route.
    topUpMethod?.let { put("topUpMethod", JsonPrimitive(it.wire)) }
    topUpAmount?.let { put("topUpAmount", JsonPrimitive(round2(it))) }
    escalationReason?.trim()?.takeIf { it.isNotEmpty() }?.let { put("escalation_reason", JsonPrimitive(it)) }
}

/**
 * One coded line on the wire (`buildPayload`, `ProcessReceiptModal.jsx:382-404`):
 * `amount` is gross, `unit_price` net per unit, the split parent by id so a
 * saved split re-opens as a split. Expenditure type and rental dates, which
 * this editor does not show, are carried from the line as it arrived.
 */
internal fun ProcessLine.wire(sortOrder: Int): JsonObject = buildJsonObject {
    val original = raw ?: JsonObject(emptyMap())
    id?.let { put("id", JsonPrimitive(it)) }
    put("description", JsonPrimitive(description.trim()))
    put("quantity", JsonPrimitive(quantity))
    put("unit_price", JsonPrimitive(round2(if (quantity > 0) net / quantity else net)))
    put("amount", JsonPrimitive(gross))
    put("account", account.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
    put("tax_type", taxType.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)
    put("tax_rate", taxRate?.let(::JsonPrimitive) ?: JsonNull)
    put("tax_amount", JsonPrimitive(tax))
    put("expenditure_type", original.keep("expenditure_type"))
    put("rental_start", original.keep("rental_start"))
    put("rental_end", original.keep("rental_end"))
    put("split_parent_id", splitParentId?.let(::JsonPrimitive) ?: JsonNull)
    put("sort_order", JsonPrimitive(sortOrder))
    put("tracking_codes", codesJson(trackingCodes))
    put("tags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
}

/**
 * The reclaimable-tax row as an `is_tax` line (`ProcessReceiptModal.jsx:411-428`):
 * one unit of the effective amount, no tax of its own, after every other line.
 */
internal fun TaxLineWire.wire(sortOrder: Int): JsonObject = buildJsonObject {
    put("is_tax", JsonPrimitive(true))
    put("description", JsonPrimitive(""))
    put("quantity", JsonPrimitive(1))
    put("unit_price", JsonPrimitive(round2(amount)))
    put("amount", JsonPrimitive(round2(amount)))
    put("account", account.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
    put("tax_type", JsonNull)
    put("tax_rate", JsonNull)
    put("tax_amount", JsonPrimitive(0))
    put("expenditure_type", JsonNull)
    put("rental_start", JsonNull)
    put("rental_end", JsonNull)
    put("split_parent_id", JsonNull)
    put("sort_order", JsonPrimitive(sortOrder))
    put("tracking_codes", codesJson(trackingCodes))
    put("tags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } })
}

private fun codesJson(codes: Map<String, String>): JsonObject =
    JsonObject(codes.mapValues { (_, code) -> JsonPrimitive(code) })

private fun JsonObject.keep(key: String): JsonElement = this[key] ?: JsonNull

/**
 * `POST /cards/:id/activate`.
 *
 * The provider — and the bank and company it binds — goes only for a card
 * requested without one; the company pin also when this card has one to
 * give. Everything else the server already holds.
 */
internal fun CardActivation.body(): JsonObject = buildJsonObject {
    val digits = cardNumber.filter(Char::isDigit)
    put("card_type", JsonPrimitive(cardType.wire))
    put("last_four", JsonPrimitive(digits.takeLast(LAST_FOUR)))
    put("full_card_number", JsonPrimitive(digits))
    providerId?.takeIf { it.isNotBlank() }?.let { provider ->
        put("card_provider_id", JsonPrimitive(provider))
        put("card_issuer", JsonPrimitive(bankId.orEmpty()))
    }
    companyId?.takeIf { it.isNotBlank() }?.let { put("company_id", JsonPrimitive(it)) }
}

/** All four keys, numbers where numbers are due (`requestCapPayload`). */
internal fun RequestCap.body(): JsonObject = buildJsonObject {
    put("enabled", JsonPrimitive(enabled))
    put("basis", JsonPrimitive(basis.wire))
    put("max_amount", JsonPrimitive(maxAmount.coerceAtLeast(0.0)))
    put("salary_multiplier", JsonPrimitive(salaryMultiplier.coerceAtLeast(0.0)))
}

/**
 * A provider row as the settings store keeps it (`sanitizeCardProviders`):
 * the name trimmed and every optional code null when empty, never dropped.
 */
internal fun CardProvider.body(): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("name", JsonPrimitive(name.trim()))
    put("bank_id", bankId.orNull())
    put("company_id", companyId.orNull())
    put("custodian_account", custodianAccount.orNull())
    put("float_min", floatMin.orNull())
    put("float_max", floatMax.orNull())
}

private fun String.orNull(): JsonElement = trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull

/**
 * The export bodies (`cardExpenses.js:247-266`). The project and company
 * names go blank: the server fills them from the production's settings.
 */
internal fun cardExportBody(format: ExportFormat, rows: List<CardExportRow>): JsonObject = buildJsonObject {
    putExportHeader(format)
    put(
        "rows",
        buildJsonArray {
            rows.forEach { row ->
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive(row.id))
                        put("last4", JsonPrimitive(row.last4))
                        put("holder", JsonPrimitive(row.holder))
                        put("department", JsonPrimitive(row.department))
                        put("issuer", JsonPrimitive(row.issuer))
                        put("status", JsonPrimitive(row.status))
                        put("currency", JsonPrimitive(row.currency))
                        put("limit", JsonPrimitive(row.limit))
                        put("balance", row.balance?.let(::JsonPrimitive) ?: JsonNull)
                    },
                )
            }
        },
    )
}

internal fun transactionExportBody(format: ExportFormat): JsonObject = buildJsonObject { putExportHeader(format) }

private fun kotlinx.serialization.json.JsonObjectBuilder.putExportHeader(format: ExportFormat) {
    put("format", JsonPrimitive(format.wire))
    put("projectName", JsonPrimitive(""))
    put("companyName", JsonPrimitive(""))
}

private const val LAST_FOUR = 4
