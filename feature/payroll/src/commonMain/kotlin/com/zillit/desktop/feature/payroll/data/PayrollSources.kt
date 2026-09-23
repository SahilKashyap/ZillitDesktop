package com.zillit.desktop.feature.payroll.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.payroll.domain.ClaimLine
import com.zillit.desktop.feature.payroll.domain.ExportFormat
import com.zillit.desktop.feature.payroll.domain.Journal
import com.zillit.desktop.feature.payroll.domain.JournalCoding
import com.zillit.desktop.feature.payroll.domain.JournalLine
import com.zillit.desktop.feature.payroll.domain.JournalPosted
import com.zillit.desktop.feature.payroll.domain.JournalSubmission
import com.zillit.desktop.feature.payroll.domain.ManualClaim
import com.zillit.desktop.feature.payroll.domain.PayrollAdjustmentRepository
import com.zillit.desktop.feature.payroll.domain.PayrollDocuments
import com.zillit.desktop.feature.payroll.domain.PayrollJournalRepository
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.PendingClaim
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * The journal ledger — `/payroll/runs/journal-ledger`, the only run routes
 * that exist besides the exports.
 *
 * One endpoint saves and posts; `action` tells them apart. The coding is
 * stored per (timecard, src, group_key), and the payroll-account lines per
 * week under `run_lines`, because the run has one set of them.
 */
internal class PayrollJournalSource(private val apiClient: ApiClient, payrollBase: String) : PayrollJournalRepository {

    private val base = "$payrollBase/runs/journal-ledger"

    override suspend fun coding(timecardIds: List<String>, weekStarting: Long): ZillitResult<JournalCoding> =
        apiClient.requestOrNull(
            verb = HttpVerb.Post,
            url = "$base/fetch",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = codingFetchBody(timecardIds, weekStarting),
        ).map { it.toJournalCoding() }

    override suspend fun submit(submission: JournalSubmission): ZillitResult<JournalPosted> = apiClient.envelope(
        verb = HttpVerb.Post,
        url = base,
        module = RequestModule.ProjectUser,
        body = submission.toJson(),
    ).refusedOnStatusZero().map { envelope ->
        val data = envelope.data.obj()
        JournalPosted(journalDisplay = data?.text("journal_display", "journal_number"), message = envelope.message)
    }
}

/**
 * `{ timecard_ids, week_starting, posting_week }` — one number under both
 * names, both null together when there is no week (the web's
 * `getJournalLedger`).
 */
internal fun codingFetchBody(timecardIds: List<String>, weekStarting: Long): JsonObject {
    val week = weekStarting.takeIf { it > 0 }?.let(::JsonPrimitive) ?: JsonNull
    return buildJsonObject {
        put(
            "timecard_ids",
            buildJsonArray { timecardIds.filter { it.isNotBlank() }.forEach { add(JsonPrimitive(it)) } },
        )
        put("week_starting", week)
        put("posting_week", week)
    }
}

/** `{ timecards: [{id, lines}], run_lines }`, or a flat array of lines carrying `timecard_id`. */
internal fun JsonElement?.toJournalCoding(): JournalCoding {
    val body = obj()?.let { it.obj("data") ?: it }
    val grouped = body?.array("timecards")
    if (grouped != null && grouped.isNotEmpty()) {
        return JournalCoding(
            byTimecard = grouped.mapNotNull { it as? JsonObject }.mapNotNull { entry ->
                val id = entry.text("id", "_id", "timecard_id") ?: return@mapNotNull null
                id to entry.objects("lines").mapNotNull { it.toJournalLine() }
            }.toMap(),
            runLines = body.objects("run_lines").mapNotNull { it.toJournalLine() },
        )
    }
    val flat = (this as? JsonArray) ?: (body?.get("data") as? JsonArray)
    return JournalCoding(
        byTimecard = flat.orEmpty().mapNotNull { it as? JsonObject }
            .groupBy { it.text("timecard_id", "timecardId").orEmpty() }
            .filterKeys { it.isNotEmpty() }
            .mapValues { (_, lines) -> lines.mapNotNull { it.toJournalLine() } },
        runLines = body?.objects("run_lines").orEmpty().mapNotNull { it.toJournalLine() },
    )
}

/**
 * One saved line. Reads stay tolerant of the legacy single `amount` key,
 * which lines saved before the debit/credit split still carry — it belongs
 * on the side the line's `src` puts it (the web's `readSide`).
 */
internal fun JsonObject.toJournalLine(): JournalLine? {
    val src = text("src") ?: return null
    val groupKey = text("group_key") ?: return null
    val legacy = number("amount")
    val isCredit = src == Journal.SRC_ACCOUNT
    return JournalLine(
        id = text("id", "_id"),
        src = src,
        groupKey = groupKey,
        identifier = text("identifier").orEmpty(),
        label = text("label").orEmpty(),
        splitParentId = text("split_parent_id"),
        debit = number("debit") ?: legacy.takeUnless { isCredit },
        credit = number("credit") ?: legacy.takeIf { isCredit },
        nominalCode = text("nominal_code").orEmpty(),
        trackingCodes = this["tracking_codes"]?.takeUnless { it is JsonNull },
        tags = this["tags"]?.takeUnless { it is JsonNull },
        taxType = text("tax_type").orEmpty(),
        taxRate = number("tax_rate"),
        ledgerDescription = text("ledger_description").orEmpty(),
        effectiveDate = millis("effective_date"),
    )
}

/** The web's wire line (`buildJournalLedgerLines`): every key present, the side that does not apply null. */
internal fun JournalLine.toJson(): JsonObject = buildJsonObject {
    id?.let { put("id", JsonPrimitive(it)) }
    put("src", JsonPrimitive(src))
    put("group_key", JsonPrimitive(groupKey))
    put("identifier", JsonPrimitive(identifier))
    put("label", JsonPrimitive(label))
    put("split_parent_id", splitParentId?.let(::JsonPrimitive) ?: JsonNull)
    put("debit", debit?.let(::JsonPrimitive) ?: JsonNull)
    put("credit", credit?.let(::JsonPrimitive) ?: JsonNull)
    put("nominal_code", JsonPrimitive(nominalCode))
    put("tracking_codes", trackingCodes ?: JsonObject(emptyMap()))
    put("tags", tags ?: JsonArray(emptyList()))
    put("tax_type", JsonPrimitive(taxType))
    put("tax_rate", taxRate?.let(::JsonPrimitive) ?: JsonNull)
    put("ledger_description", JsonPrimitive(ledgerDescription))
    put("effective_date", effectiveDate?.let(::JsonPrimitive) ?: JsonNull)
}

/** `{ action, week_starting, posting_week, [effective_date], timecards: [{id, lines}], run_lines }`. */
internal fun JournalSubmission.toJson(): JsonObject = buildJsonObject {
    put("action", JsonPrimitive(if (post) "post" else "save"))
    put("week_starting", JsonPrimitive(weekStarting))
    put("posting_week", JsonPrimitive(weekStarting))
    if (post) put("effective_date", effectiveDate?.let(::JsonPrimitive) ?: JsonNull)
    put(
        "timecards",
        buildJsonArray {
            timecards.forEach { (id, lines) ->
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive(id))
                        put("lines", JsonArray(lines.map { it.toJson() }))
                    },
                )
            }
        },
    )
    put("run_lines", JsonArray(runLines.map { it.toJson() }))
}

/**
 * Claims and deductions — each its own row-level route under the timecard,
 * the backend's sanctioned contract: there is no update, an edit is a remove
 * and an add, and the server computes `actual_amount` and stamps the audit
 * fields, so neither is ever sent.
 */
internal class PayrollAdjustmentSource(
    private val apiClient: ApiClient,
    private val timecards: String,
    private val cashExpenses: String,
) : PayrollAdjustmentRepository {

    override suspend fun pendingClaims(userId: String): ZillitResult<List<PendingClaim>> =
        apiClient.requestOrNull(
            verb = HttpVerb.Get,
            url = "$cashExpenses/claims/pending-for-payroll",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            queryParameters = mapOf("user_id" to userId),
        ).map { data -> data.rows("payroll_batches").mapNotNull { it.toPendingClaim() } }

    override suspend fun attachBatch(timecardId: String, batchId: String): ZillitResult<PayrollTimecard?> =
        write("$timecardId/attach-claim", buildJsonObject { put("cash_expense_batch_id", JsonPrimitive(batchId)) })

    override suspend fun attachManual(timecardId: String, claim: ManualClaim): ZillitResult<PayrollTimecard?> =
        write("$timecardId/attach-claim", manualClaimBody(claim))

    // By the attached row's own id where it has one, else by the batch it came from.
    override suspend fun detachClaim(timecardId: String, claim: ClaimLine): ZillitResult<PayrollTimecard?> = write(
        "$timecardId/detach-claim",
        buildJsonObject {
            if (claim.id != null) {
                put("attached_claim_id", JsonPrimitive(claim.id))
            } else {
                put("cash_expense_batch_id", claim.cashExpenseBatchId?.let(::JsonPrimitive) ?: JsonNull)
            }
        },
    )

    override suspend fun addDeduction(timecardId: String, deduction: ManualClaim): ZillitResult<PayrollTimecard?> =
        write("$timecardId/add-deduction", deductionBody(deduction))

    override suspend fun removeDeduction(timecardId: String, deductionId: String): ZillitResult<PayrollTimecard?> =
        write("$timecardId/remove-deduction", buildJsonObject { put("deduction_id", JsonPrimitive(deductionId)) })

    private suspend fun write(path: String, body: JsonObject): ZillitResult<PayrollTimecard?> = apiClient.envelope(
        verb = HttpVerb.Post,
        url = "$timecards/$path",
        module = RequestModule.ProjectUser,
        body = body,
    ).refusedOnStatusZero().map { envelope -> envelope.data.obj()?.toTimecard() }
}

/** `{ claim_name, claim_amount, claim_currency, nominal_code }` — the web's manual line. */
internal fun manualClaimBody(claim: ManualClaim) = buildJsonObject {
    put("claim_name", JsonPrimitive(claim.name.trim()))
    put("claim_amount", JsonPrimitive(claim.amount))
    put("claim_currency", claim.currency?.let(::JsonPrimitive) ?: JsonNull)
    put("nominal_code", JsonPrimitive(claim.nominalCode?.trim().orEmpty()))
}

/** `{ label, rate_type: "flat", rate_amount, nominal_code }` — new rows are always flat. */
internal fun deductionBody(deduction: ManualClaim) = buildJsonObject {
    put("label", JsonPrimitive(deduction.name.trim()))
    put("rate_type", JsonPrimitive("flat"))
    put("rate_amount", JsonPrimitive(deduction.amount))
    put("nominal_code", JsonPrimitive(deduction.nominalCode?.trim().orEmpty()))
}

private fun JsonObject.toPendingClaim(): PendingClaim? {
    val id = identifier() ?: return null
    return PendingClaim(
        id = id,
        reference = text("batch_reference"),
        expenseType = text("expense_type"),
        claimCount = number("claim_count")?.toInt() ?: 0,
        postedAt = millis("posted_at"),
        amount = number("reimbursement_amount") ?: amount("total_gross"),
        currency = text("currency"),
    )
}

/**
 * A POST whose answer is a file. The envelope client cannot read one, so the
 * host supplies the signed raw call — the same seam the cost report and bank
 * reconciliation exports use.
 */
interface PayrollBinaryTransport {
    suspend fun post(url: String, body: JsonObject): ZillitResult<ByteArray>
    suspend fun get(url: String): ZillitResult<ByteArray>
}

/**
 * The payroll service's rendered files. Each answers a JSON envelope instead
 * when it declines ("No timecards in this run"), which the transport turns
 * into a failure carrying the server's message rather than a file.
 */
class PayrollDocumentsImpl(config: AppConfig, private val transport: PayrollBinaryTransport) : PayrollDocuments {

    private val payroll = "${config.baseUrl(ZillitService.Payroll)}/api/v2/payroll"

    override suspend fun payslip(weekStarting: Long, userId: String): ZillitResult<ByteArray> =
        transport.post("$payroll/runs/payslip", payslipBody(weekStarting, userId))

    override suspend fun runSummary(weekStarting: Long, format: ExportFormat): ZillitResult<ByteArray> =
        transport.post("$payroll/runs/export-summary", runSummaryBody(weekStarting, format))

    override suspend fun weekWorkbook(weekStarting: Long): ZillitResult<ByteArray> =
        transport.get("$payroll/timecards/weekly/payroll-processing/$weekStarting/csv")

    override suspend fun outstandingWorkbook(): ZillitResult<ByteArray> =
        transport.get("$payroll/timecards/weekly/payroll-processing/outstanding/csv")
}

internal fun payslipBody(weekStarting: Long, userId: String) = buildJsonObject {
    put("week_starting", JsonPrimitive(weekStarting))
    put("user_id", JsonPrimitive(userId))
}

internal fun runSummaryBody(weekStarting: Long, format: ExportFormat) = buildJsonObject {
    put("week_starting", JsonPrimitive(weekStarting))
    put("format", JsonPrimitive(format.wire))
}
