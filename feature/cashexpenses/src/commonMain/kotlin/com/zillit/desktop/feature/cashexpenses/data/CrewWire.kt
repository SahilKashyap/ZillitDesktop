package com.zillit.desktop.feature.cashexpenses.data

import com.zillit.desktop.feature.cashexpenses.domain.FollowUp
import com.zillit.desktop.feature.cashexpenses.domain.NewClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.ReimbursementMethod
import com.zillit.desktop.feature.cashexpenses.domain.SettlementDetails
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * `POST /claims` — the web's submit payload, key for key
 * (`PCSubmitClaimPage.jsx:653-672`, `OOPSubmitPage.jsx:84-101`).
 *
 * The batch's currency is stamped on every claim; the vendor travels as
 * `description`; supplier and VAT are not sent — accounts extract the tax.
 * Optional coding goes as null rather than being left out, as the web sends it.
 */
internal fun NewClaimBatch.toSubmitJson(): JsonObject = buildJsonObject {
    put("expense_type", JsonPrimitive(expenseType.wire))
    departmentId?.takeIf { it.isNotBlank() }?.let { put("department_id", JsonPrimitive(it)) }
    floatId?.takeIf { it.isNotBlank() }?.let { put("float_request_id", JsonPrimitive(it)) }
    currency?.takeIf { it.isNotBlank() }?.let { put("currency", JsonPrimitive(it)) }
    settlementType?.let { put("settlement_type", JsonPrimitive(it)) }
    settlementDetails?.let { put("settlement_details", it.toJson()) }
    put("notes", notes?.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)
    put(
        "claims",
        buildJsonArray {
            receipts.forEach { receipt ->
                add(
                    buildJsonObject {
                        put("description", JsonPrimitive(receipt.description))
                        put("category", receipt.category.lowercase().takeIf { it.isNotBlank() }.orNull())
                        put("gross_amount", JsonPrimitive(receipt.amount.trim().toDoubleOrNull() ?: 0.0))
                        currency?.takeIf { it.isNotBlank() }?.let { put("currency", JsonPrimitive(it)) }
                        put("receipt_date", receipt.date?.let(::JsonPrimitive) ?: JsonNull)
                        put("cost_code", receipt.costCode.trim().takeIf { it.isNotEmpty() }.orNull())
                        put("episode", receipt.episode.trim().takeIf { it.isNotEmpty() }.orNull())
                        put(
                            "coded_description",
                            receipt.codedDescription.trim().takeIf { it.isNotEmpty() }.orNull(),
                        )
                        val attachment = receipt.attachment
                        when {
                            attachment != null -> put("attachment", attachment.toJson())
                            // An older draft that carries only a key.
                            !receipt.attachmentKey.isNullOrBlank() ->
                                put("receipt_url", JsonPrimitive(receipt.attachmentKey))
                            else -> put("attachment", JsonNull)
                        }
                    },
                )
            }
        },
    )
}

/** `settlement_details`, each key only where the web sends it. */
internal fun SettlementDetails.toJson(): JsonObject = buildJsonObject {
    if (includeFollowUp) put("follow_up", followUp.orNull())
    if (followUp == FollowUp.TOP_UP && topUpAmount != null) put("top_up_amount", JsonPrimitive(topUpAmount))
    paymentMethod?.let { put("payment_method", JsonPrimitive(it.wire)) }
    val bank = bankDetails
    if (paymentMethod == ReimbursementMethod.Bacs && bank != null) {
        put(
            "bank_details",
            buildJsonObject {
                put("account_name", JsonPrimitive(bank.accountName))
                put("sort_code", JsonPrimitive(bank.sortCode))
                put("account_number", JsonPrimitive(bank.accountNumber))
                // A titled row stays even with no value yet; an untitled one is dropped.
                val extras = bank.extras.filter { it.label.isNotBlank() }
                if (extras.isNotEmpty()) {
                    put(
                        "additional_details",
                        buildJsonArray {
                            extras.forEach { row ->
                                add(
                                    buildJsonObject {
                                        put("label", JsonPrimitive(row.label.trim()))
                                        put("value", JsonPrimitive(row.value.trim()))
                                        put("field_type", JsonPrimitive(row.fieldType.ifBlank { "text" }))
                                    },
                                )
                            }
                        },
                    )
                }
            },
        )
    }
}

private fun String?.orNull() = this?.let(::JsonPrimitive) ?: JsonNull
