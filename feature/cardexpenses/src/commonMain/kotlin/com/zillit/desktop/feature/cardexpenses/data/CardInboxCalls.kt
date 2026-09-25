package com.zillit.desktop.feature.cardexpenses.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.applyMessageElements
import com.zillit.desktop.core.common.currencyCode
import com.zillit.desktop.core.common.toAmount
import com.zillit.desktop.core.common.toAmountOrNull
import com.zillit.desktop.core.common.toEpochMillisOrNull
import com.zillit.desktop.core.common.withoutUnfilledPlaceholders
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.localization.LabelKind
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.ApiEnvelope
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.cardexpenses.domain.CrewSubmission
import com.zillit.desktop.feature.cardexpenses.domain.ImportedRow
import com.zillit.desktop.feature.cardexpenses.domain.InboxWrite
import com.zillit.desktop.feature.cardexpenses.domain.MatchCandidate
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptDetail
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptDetailApproval
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptDetailLine
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptMedia
import com.zillit.desktop.feature.cardexpenses.domain.ServerOutcome
import com.zillit.desktop.feature.cardexpenses.domain.StatementImportInfo
import com.zillit.desktop.feature.cardexpenses.domain.StatementImportResult
import com.zillit.desktop.feature.cardexpenses.domain.StatementSummary
import com.zillit.desktop.feature.cardexpenses.domain.StoredStatement
import com.zillit.desktop.feature.cardexpenses.domain.round2
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull

/**
 * The reconciliation surfaces' routes — Import Statement, Receipt Inbox, All
 * Transactions, the manual match and the receipt detail.
 *
 * Every write here answers with the **server's own message**, as the web's
 * `showApiSuccess` does, and a `status: 0` inside a 200 is a refusal: the
 * envelope client passes it through as success, and a match that "succeeded"
 * having done nothing is the one outcome an accountant cannot see.
 */
internal class CardInboxCalls(private val apiClient: ApiClient, config: AppConfig) {

    private val base = "${config.baseUrl(ZillitService.CardExpenses)}/api/v2/card-expenses"

    /**
     * `GET /receipts/:id/detail` — the row plus its lines, approvals and
     * document model (`ReceiptInboxPage.jsx:324-336`).
     */
    suspend fun receiptDetail(receiptId: String): ZillitResult<ReceiptDetail> =
        call(HttpVerb.Get, "$base/receipts/$receiptId/detail").andThen { envelope ->
            val body = envelope.data.unwrapped() as? JsonObject
            val receipt = body?.let {
                runCatching { HttpClientFactory.json.decodeFromJsonElement(ReceiptDto.serializer(), it) }
                    .getOrNull()?.toDomain()
            }
            if (body == null || receipt == null) {
                return@andThen ZillitResult.Failure(ZillitError.Unknown("receipt detail carried no id"))
            }
            ZillitResult.Success(
                ReceiptDetail(
                    receipt = receipt,
                    lines = body["line_items"].objects().filterNot { it.isTaxLine() }.map { it.toDetailLine() },
                    approvals = body["approvals"].objects().mapNotNull { it.toApproval() },
                    media = body.media() ?: receipt.attachmentKey?.takeIf(String::isNotBlank)?.let(::ReceiptMedia),
                    transactionCurrency = body["transaction_currency"].currencyCode()?.takeIf(String::isNotBlank),
                ),
            )
        }

    /**
     * `GET /receipts/:id/match-candidates` — `merchant_name`, `transaction_date`,
     * `card_last_four` and a fractional `match_score` (`ManualMatchModal.jsx:165-195`).
     */
    suspend fun matchCandidates(receiptId: String): ZillitResult<List<MatchCandidate>> =
        call(HttpVerb.Get, "$base/receipts/$receiptId/match-candidates").andThen { envelope ->
            ZillitResult.Success(envelope.data.objects().mapNotNull { it.toCandidate() })
        }

    suspend fun write(write: InboxWrite): ZillitResult<String?> {
        val (verb, path, body) = write.route()
        return call(verb, "$base$path", body).andThen { ZillitResult.Success(it.notice()) }
    }

    /**
     * `POST /transactions/bulk-delete {transactionIds}`.
     *
     * The envelope answers `status: 1` whether or not anything matched — ids
     * the server does not recognise are skipped — so the count is what
     * happened. An **absent** count is not a no-op (`AllTransactionsPage.jsx:31-34`):
     * a response that stopped carrying it would otherwise turn every good
     * delete into a warning.
     */
    suspend fun bulkDelete(transactionIds: List<String>): ZillitResult<ServerOutcome<Int>> =
        call(HttpVerb.Post, "$base/transactions/bulk-delete", idsBody(transactionIds)).andThen { envelope ->
            val body = envelope.data.unwrapped() as? JsonObject
            val raw = body?.get("deletedTransactions")
            val count = if (raw == null || raw is JsonNull) 1 else raw.number()?.toInt() ?: 0
            ZillitResult.Success(ServerOutcome(count, envelope.notice()))
        }

    /**
     * `POST /imports/import-statement {attachment, currency?}` — the whole
     * attachment model, and the currency only when there is one to state
     * (`cardExpenses.js:129-133`): an empty string would be a claim about the
     * statement rather than the absence of one.
     */
    suspend fun importStatement(
        attachment: StoredStatement,
        currency: String?,
    ): ZillitResult<ServerOutcome<StatementImportResult>> = call(
        HttpVerb.Post,
        "$base/imports/import-statement",
        importBody(attachment, currency),
    ).andThen { envelope ->
        val body = envelope.data.unwrapped() as? JsonObject ?: JsonObject(emptyMap())
        ZillitResult.Success(ServerOutcome(body.toImportResult(), envelope.notice()))
    }

    /** `POST /transactions/submit-to-users {transactionIds}` — every selected id, as the web sends. */
    suspend fun submitToCrew(transactionIds: List<String>): ZillitResult<ServerOutcome<CrewSubmission>> =
        call(HttpVerb.Post, "$base/transactions/submit-to-users", idsBody(transactionIds)).andThen { envelope ->
            val body = envelope.data.unwrapped() as? JsonObject ?: JsonObject(emptyMap())
            val submission = CrewSubmission(
                submitted = body["submitted"].number()?.toInt() ?: 0,
                skipped = body["skipped"].number()?.toInt() ?: 0,
                totalAmount = body["totalAmount"].number() ?: 0.0,
                notified = body["notified"].objects().mapNotNull { row ->
                    val user = row.text("userId") ?: row.text("user_id") ?: return@mapNotNull null
                    user to (row["count"].number()?.toInt() ?: 0)
                },
            )
            ZillitResult.Success(ServerOutcome(submission, envelope.notice()))
        }

    // -- plumbing ----------------------------------------------------------

    private suspend fun call(verb: HttpVerb, url: String, body: JsonObject? = null): ZillitResult<ApiEnvelope> =
        when (val outcome = apiClient.envelope(verb, url, RequestModule.ProjectUser, body)) {
            is ZillitResult.Failure -> outcome
            is ZillitResult.Success -> if (outcome.data.status == 0) {
                ZillitResult.Failure(
                    ZillitError.Http(
                        status = REFUSED,
                        serverMessage = outcome.data.message,
                        messageElements = outcome.data.messageElements.orEmpty(),
                    ),
                )
            } else {
                outcome
            }
        }

    private inline fun <T> ZillitResult<ApiEnvelope>.andThen(
        block: (ApiEnvelope) -> ZillitResult<T>,
    ): ZillitResult<T> = when (this) {
        is ZillitResult.Success -> block(data)
        is ZillitResult.Failure -> this
    }

    private companion object {
        /** A 200 whose envelope said no — reported as the server's refusal. */
        const val REFUSED = 200
    }
}

/** The route, verb and body each [InboxWrite] goes to — the web's `cardExpenses.js`. */
internal fun InboxWrite.route(): Triple<HttpVerb, String, JsonObject?> = when (this) {
    is InboxWrite.ConfirmMatch -> Triple(HttpVerb.Post, "/receipts/$receiptId/confirm-match", null)
    is InboxWrite.ManualMatch -> Triple(
        HttpVerb.Post,
        "/receipts/$receiptId/manual-match",
        buildJsonObject { put("transactionId", JsonPrimitive(transactionId)) },
    )

    is InboxWrite.FlagReceiptPersonal -> Triple(HttpVerb.Post, "/receipts/$receiptId/flag-personal", null)
    is InboxWrite.FlagTransactionPersonal ->
        Triple(HttpVerb.Post, "/transactions/$transactionId/flag-personal", null)

    is InboxWrite.DismissDuplicate -> Triple(HttpVerb.Post, "/receipts/$receiptId/dismiss-duplicate", null)
    is InboxWrite.DismissPersonal -> Triple(HttpVerb.Post, "/receipts/$receiptId/dismiss-personal", null)
    // `rerunMatch()` is called with no statement, which JSON-encodes to `{}`.
    InboxWrite.RerunMatch -> Triple(HttpVerb.Post, "/receipts/rerun-match", JsonObject(emptyMap()))
    is InboxWrite.DeleteTransaction -> Triple(HttpVerb.Delete, "/transactions/$transactionId", null)
}

/** `{media, bucket, region, name, content_type, content_subtype}` plus the currency when stated. */
internal fun importBody(attachment: StoredStatement, currency: String?): JsonObject = buildJsonObject {
    put(
        "attachment",
        buildJsonObject {
            put("media", JsonPrimitive(attachment.media))
            attachment.bucket?.let { put("bucket", JsonPrimitive(it)) }
            attachment.region?.let { put("region", JsonPrimitive(it)) }
            put("name", JsonPrimitive(attachment.name))
            put("content_type", JsonPrimitive(attachment.contentType))
            put("content_subtype", JsonPrimitive(attachment.contentSubtype))
        },
    )
    putIfPresent("currency", currency)
}

private fun idsBody(ids: List<String>): JsonObject = buildJsonObject {
    put("transactionIds", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } })
}

/** The server's success sentence: its message key, translated and filled in. */
internal fun ApiEnvelope.notice(): String? = message?.takeIf { it.isNotBlank() }?.let { key ->
    applyMessageElements(
        text = key.localisedMessage(),
        elements = messageElements.orEmpty(),
        translate = { replacer -> Labels.current.exact(replacer, LabelKind.Messages) },
    ).withoutUnfilledPlaceholders()
}

// -- readers -------------------------------------------------------------------

/** `{import, rows, summary, processed}` (`ImportStatementPage.jsx:224-226`). */
internal fun JsonObject.toImportResult(): StatementImportResult {
    val record = this["import"].unwrapped() as? JsonObject ?: JsonObject(emptyMap())
    val summary = this["summary"].unwrapped() as? JsonObject ?: JsonObject(emptyMap())
    val processed = this["processed"].unwrapped() as? JsonObject
    return StatementImportResult(
        info = StatementImportInfo(
            id = record.text("id"),
            fileName = record.text("filename") ?: record.text("file_name") ?: record.text("name"),
            issuer = record.text("issuer"),
            accountName = record.text("account_name"),
            accountNumber = record.text("account_number"),
            sortCode = record.text("sort_code"),
            currency = record["currency"].currencyCode()?.takeIf(String::isNotBlank),
        ),
        summary = StatementSummary(
            totalRows = summary["total_rows"].number()?.toInt() ?: 0,
            newCount = summary["new_count"].number()?.toInt() ?: 0,
            duplicateCount = summary["duplicate_count"].number()?.toInt() ?: 0,
            declinedCount = summary["declined_count"].number()?.toInt() ?: 0,
            periodStart = summary.text("period_start").toEpochMillisOrNull(),
            periodEnd = summary.text("period_end").toEpochMillisOrNull(),
        ),
        rowsProcessed = processed?.get("rowsProcessed").number()?.toInt(),
        rows = this["rows"].objects().mapNotNull { it.toImportedRow() },
    )
}

/**
 * One imported row, read with the web table's fallbacks (`TransactionTable.jsx`):
 * `merchant_raw` before `merchant` before `description`, `user_id` or
 * `userId`, `card_last_four` or `cardLastFour`.
 */
private fun JsonObject.toImportedRow(): ImportedRow? {
    val id = text("id") ?: return null
    return ImportedRow(
        id = id,
        rowIndex = this["row_index"].number()?.toInt(),
        date = (text("transaction_date") ?: text("date")).toEpochMillisOrNull(),
        merchant = text("merchant_raw") ?: text("merchant") ?: text("description").orEmpty(),
        holderId = text("user_id") ?: text("userId"),
        cardLastFour = (text("card_last_four") ?: text("cardLastFour"))?.takeIf { it != NO_CARD },
        amount = text("amount").toAmount(),
        currency = this["currency"].currencyCode()?.takeIf(String::isNotBlank),
        status = text("status")?.lowercase() ?: ImportedRow.NEW,
    )
}

private fun JsonObject.toCandidate(): MatchCandidate? {
    val id = text("id") ?: return null
    return MatchCandidate(
        id = id,
        merchant = text("merchant_name") ?: text("merchant").orEmpty(),
        date = (text("transaction_date") ?: text("date")).toEpochMillisOrNull(),
        cardLastFour = (text("card_last_four") ?: text("cardLastFour"))?.takeIf { it != NO_CARD },
        amount = text("amount").toAmount(),
        currency = this["currency"].currencyCode()?.takeIf(String::isNotBlank),
        // The web rounds `match_score * 100` and shows 0% for none.
        scorePercent = text("match_score").asPercent() ?: 0,
    )
}

/** Gross less tax is the net; a split child has no tax of its own (`ReceiptDetailModal.jsx:366-388`). */
private fun JsonObject.toDetailLine(): ReceiptDetailLine {
    val gross = text("amount").toAmountOrNull() ?: 0.0
    val tax = text("tax_amount").toAmountOrNull() ?: 0.0
    val tags = (this["tags"].unwrapped() as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content?.takeIf(String::isNotBlank) }
        .orEmpty()
    return ReceiptDetailLine(
        code = text("account"),
        description = text("description"),
        net = round2(gross - tax),
        tax = tax,
        tags = tags,
        splitChild = text("split_parent_id") != null,
    )
}

private fun JsonObject.isTaxLine(): Boolean = this["is_tax"].isTrue() || this["isTax"].isTrue()

private fun JsonObject.toApproval(): ReceiptDetailApproval? {
    val user = text("user_id") ?: return null
    val tier = this["tier_number"].number()?.toInt() ?: 0
    return ReceiptDetailApproval(userId = user, tierNumber = tier, override = tier == 0 || this["override"].isTrue())
}

/** `receipt_attachment` when it is a model, else the first of `attachments` (`pickAttachment`). */
private fun JsonObject.media(): ReceiptMedia? {
    val model = (this["receipt_attachment"].unwrapped() as? JsonObject)
        ?: (this["attachments"].unwrapped() as? JsonArray)?.firstOrNull() as? JsonObject
        ?: return null
    val key = listOf("media", "key", "url", "path").firstNotNullOfOrNull { model.text(it) } ?: return null
    return ReceiptMedia(
        key = key,
        name = model.text("name") ?: model.text("file_name"),
        bucket = model.text("bucket"),
        region = model.text("region"),
        contentType = listOfNotNull(model.text("content_type"), model.text("content_subtype")).joinToString("/"),
    )
}

/** An element as itself, or the element a JSON string holds. */
private fun JsonElement?.unwrapped(): JsonElement? = when {
    this == null || this is JsonNull -> null
    this is JsonPrimitive && isString -> content.trim().takeIf { it.startsWith("{") || it.startsWith("[") }
        ?.let { raw -> runCatching { HttpClientFactory.json.parseToJsonElement(raw) }.getOrNull() }
        ?: this

    else -> this
}

private fun JsonElement?.objects(): List<JsonObject> =
    (unwrapped() as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonElement?.number(): Double? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.doubleOrNull ?: it.content.toAmountOrNull() }

private fun JsonElement?.isTrue(): Boolean {
    val primitive = (this as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return false
    primitive.booleanOrNull?.let { return it }
    primitive.doubleOrNull?.let { return it != 0.0 }
    return primitive.content.trim().lowercase() == "true"
}

/** The importer's placeholder for "no card on this line". */
private const val NO_CARD = "0000"
