package com.zillit.desktop.feature.invoices.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.time.Instant

/**
 * A batch of approved invoices paid together — the web's Active Runs, on
 * `/invoices/active-runs`.
 *
 * A run is built from open items, authorised by someone with run access, and
 * then paid by one method; its total is the server's, not a client sum.
 */
data class PaymentRun(
    val id: String,
    val number: String = "",
    val name: String = "",
    val payMethod: PayMethod = PayMethod.Bacs,
    val total: Double = 0.0,
    val currency: String = "",
    val invoiceCount: Int = 0,
    val status: PaymentRunStatus = PaymentRunStatus.Draft,
    /** The tiers already signed — the run's `approval` array. */
    val approvals: List<RunSignOff> = emptyList(),
    /** Why, who and when, on a rejected run; blank otherwise. */
    val rejectionReason: String = "",
    val rejectedBy: String = "",
    val rejectedAtMs: Long? = null,
    /** Who built the run and when — the department approver's "Created" column and footer. */
    val createdBy: String = "",
    val createdAtMs: Long? = null,
    /** `status` as sent; what a status this client does not know is called. */
    val statusRaw: String = "",
) {
    /**
     * The status pill's words — the web prints the raw status capitalised,
     * "Pending" when there is none (`PaymentsPage.jsx:2106-2108`), so a known
     * one reads as its label and one this client has never seen still reads
     * as itself, never "Draft".
     */
    val statusLabel: String
        get() = when {
            status != PaymentRunStatus.Other -> status.label
            statusRaw.isBlank() -> PaymentRunStatus.Pending.label
            else -> statusRaw.trim().replaceFirstChar { it.uppercaseChar() }
        }
}

/** A run the server has just made: its id (blank when it sent none) and its toast. */
data class RunCreated(val id: String = "", val message: String? = null)

/** One signed tier of a run: which tier, and who signed it. */
data class RunSignOff(val tierNumber: Int, val userId: String)

/** A run with the invoices it pays — what `GET /active-runs/:id` answers. */
data class PaymentRunDetail(val run: PaymentRun, val invoices: List<Invoice> = emptyList())

/**
 * Whether the reader may sign the run now, and which tier that would be — the
 * web's `resolveRunApproval` (`lib/paymentRunApproval.js`).
 */
data class RunApprovalDecision(val canApprove: Boolean, val nextTier: Int?, val totalTiers: Int)

/**
 * Where a run stands. Only a pending one can be authorised or turned down.
 *
 * The web's `runStatusColor` knows pending, approved, waiting, paid, sent and
 * rejected. A missing status, or one it does not know, is [Other]: grey, and
 * never signable — `resolveRunApproval` insists on `status === "pending"` —
 * though a missing one still reads "Pending" ([PaymentRun.statusLabel]).
 */
enum class PaymentRunStatus(val wire: String, private val labelKey: String) {
    Draft("draft", S.ah_status_draft),
    Pending("pending", S.ah_run_status_pending),
    Approved("approved", S.ah_run_status_approved),
    Waiting("waiting", S.ah_run_detail_tier_waiting),
    Sent("sent", S.cs_sent),
    Rejected("rejected", S.ah_run_status_rejected),
    Paid("paid", S.desktop_paid),
    Other("", S.desktop_unknown),
    ;

    val label: String get() = str(labelKey)

    val isDecidable: Boolean get() = this == Pending || this == Draft

    companion object {
        fun from(wire: String?): PaymentRunStatus {
            val code = wire?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it != Other && it.wire == code } ?: Other
        }
    }
}

/**
 * One vendor's open items in one currency — what a single run pays.
 *
 * A run is single-vendor and single-currency on the web, so a vendor with
 * both GBP and USD invoices makes two runs, not one mixed batch.
 */
data class PaymentGroup(
    val vendorId: String,
    val vendorName: String,
    val currency: String,
    val invoices: List<Invoice>,
) {
    val ids: List<String> get() = invoices.map { it.id }
    val total: Double get() = invoices.sumOf { it.grossAmount }

    /** `vendor_id|CURRENCY` — the web's stable group key. */
    val key: String get() = "$vendorId|$currency"

    /** What the run is called on the ledger — the web's own wording. */
    val runName: String get() = "BACs Run — $vendorName ($currency)"
}

/**
 * One line of Open Items as the web draws it: a vendor-and-currency header
 * (tick for the whole group, count, total, open or shut), then — while it is
 * open — that group's invoices.
 */
sealed interface OpenItemRow {
    val key: String

    data class Header(val group: PaymentGroup, val open: Boolean) : OpenItemRow {
        override val key: String get() = "group:${group.key}"
    }

    data class Item(val invoice: Invoice) : OpenItemRow {
        override val key: String get() = invoice.id
    }
}

object PaymentRuns {
    /** Wire and faster payment share the Wires tab; they are paid the same way. */
    val WIRE_METHODS = setOf(PayMethod.Wire, PayMethod.Faster)

    fun groupByVendorCurrency(
        invoices: List<Invoice>,
        vendorName: (Invoice) -> String,
        defaultCurrency: String,
    ): List<PaymentGroup> = invoices
        .groupBy { it.vendorId.ifBlank { "unknown" } to currencyCode(it.currency, defaultCurrency) }
        .map { (key, rows) ->
            PaymentGroup(
                vendorId = key.first,
                vendorName = vendorName(rows.first()),
                currency = key.second,
                invoices = rows,
            )
        }
        .sortedWith(compareBy({ it.vendorName.lowercase() }, { it.currency }))

    /**
     * The next run number, continuing the sequence already on the server.
     *
     * The web reads the digits out of every existing number and adds one, so
     * `PR-007` and a hand-typed `Run 12` both count.
     */
    fun nextNumber(runs: List<PaymentRun>, offset: Int = 0): String {
        val highest = runs.mapNotNull { run -> run.number.filter { it.isDigit() }.toIntOrNull() }.maxOrNull() ?: 0
        return "PR-" + (highest + 1 + offset).toString().padStart(RUN_NUMBER_DIGITS, '0')
    }

    /**
     * Who may sign a run, and at which tier — `resolveRunApproval`, rule for
     * rule.
     *
     * The next tier is the lowest one nobody has signed yet. The reader may
     * sign it only while the run is pending and they are listed on that tier.
     * Separation of duties (ZL-20472): somebody who has already signed any
     * tier of this run may not sign again, even when the next tier lists them
     * too — or one person could clear every tier from the same open dialog.
     * A run with no chain configured has no next tier, so nobody signs it.
     */
    fun resolveApproval(
        chain: List<RunAuthLevel>,
        approvals: List<RunSignOff>,
        status: PaymentRunStatus,
        userId: String,
    ): RunApprovalDecision {
        val sorted = chain.sortedBy { it.tier }
        val next = sorted.firstOrNull { level -> approvals.none { it.tierNumber == level.tier } }
        val signedAlready = approvals.any { it.userId == userId }
        val canApprove = next != null &&
            status == PaymentRunStatus.Pending &&
            userId.isNotBlank() &&
            userId in next.userIds &&
            !signedAlready
        return RunApprovalDecision(canApprove = canApprove, nextTier = next?.tier, totalTiers = sorted.size)
    }

    /**
     * The web's `normalizeCurrencyCode`: trimmed, upper-cased, and the
     * project's own when blank — so `" gbp"` and `GBP` are one group.
     */
    fun currencyCode(code: String, defaultCurrency: String): String =
        code.trim().uppercase().ifBlank { defaultCurrency.trim().uppercase() }

    /** The Wires tab's two codes — `["wire", "faster"].includes(payMethodCode(...))`. */
    val WIRE_CODES: Set<String> = setOf(PayMethod.Wire.wire, PayMethod.Faster.wire)

    /**
     * A method's name on Open Items and in the Process sheet — the web's
     * `METHOD_LABELS`, then `payMethodLabel`, then the code humanised.
     */
    fun methodLabel(code: String, humanise: (String) -> String): String = when (code) {
        PayMethod.Bacs.wire -> str(S.desktop_inv_method_bacs)
        PayMethod.Wire.wire -> str(S.desktop_inv_method_wire_transfer)
        PayMethod.Faster.wire -> str(S.desktop_faster_payment)
        PayMethod.Cheque.wire -> str(S.ah_run_card_method_cheque)
        else -> PayMethod.entries.firstOrNull { it.wire == code }?.label ?: humanise(code)
    }

    /**
     * Open Items' PO chip: with an order linked (or a `po_id`), "N POs", the
     * first linked number or the typed one; otherwise — and when none of
     * those has a number — null, which reads "No PO" (`PaymentsPage.jsx:1767-1774`).
     */
    fun openItemPo(invoice: Invoice): String? = when {
        !invoice.hasMatchedPo -> null
        invoice.linkedPos.size > 1 -> str(S.desktop_po_count_pos, invoice.linkedPos.size)
        else -> invoice.linkedPos.firstOrNull()?.poNumber?.takeIf { it.isNotBlank() }
            ?: invoice.poNumber.takeIf { it.isNotBlank() }
    }

    /**
     * Open Items' Days column: `d = ceil((due − now) / day)`; `"{d}d"` while
     * d > 0, otherwise `"{|d|}d overdue"`, pink once the due moment has
     * passed; "—" without a due date (`PaymentsPage.jsx:1780-1795`).
     */
    fun openItemDays(dueMs: Long?, nowMs: Long): DueLabel {
        if (dueMs == null) return DueLabel("—", DueTone.Plain)
        val days = daysUntil(dueMs, nowMs)
        val tone = if (dueMs - nowMs < 0) DueTone.Overdue else DueTone.Plain
        return if (days > 0) {
            DueLabel(str(S.desktop_pc_age_days, days.toInt()), tone)
        } else {
            DueLabel(str(S.desktop_inv_n_days_overdue, abs(days).toInt()), tone)
        }
    }

    /**
     * The Wires tab's due line — the web's `getDueDays`: "Nd overdue" (pink),
     * "Due today" (amber) or "Nd left", from the same ceiling of days.
     */
    fun wireDue(dueMs: Long?, nowMs: Long): DueLabel {
        if (dueMs == null) return DueLabel("—", DueTone.Plain)
        val days = daysUntil(dueMs, nowMs)
        return when {
            days < 0 -> DueLabel(str(S.desktop_inv_n_days_overdue, abs(days).toInt()), DueTone.Overdue)
            days == 0L -> DueLabel(str(S.desktop_inv_due_today), DueTone.Today)
            else -> DueLabel(str(S.desktop_inv_n_days_left, days.toInt()), DueTone.Plain)
        }
    }

    private fun daysUntil(dueMs: Long, nowMs: Long): Long = ceil((dueMs - nowMs).toDouble() / DAY_MS).toLong()

    /**
     * The rejection banner's stamp — `DD Mon YYYY | h:mm AM/PM` in the local
     * zone, the web's `fmtDateTime` (`PaymentsPage.jsx:77-85`).
     */
    fun stamp(ms: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
        val hour = (t.hour % HALF_DAY).let { if (it == 0) HALF_DAY else it }
        val meridiem = if (t.hour < HALF_DAY) "AM" else "PM"
        val day = t.day.toString().padStart(2, '0')
        val minute = t.minute.toString().padStart(2, '0')
        return "$day ${str(SHORT_MONTHS[t.month.ordinal])} ${t.year} | $hour:$minute $meridiem"
    }

    private const val RUN_NUMBER_DIGITS = 3
    private const val DAY_MS = 86_400_000L
    private const val HALF_DAY = 12

    private val SHORT_MONTHS = listOf(
        S.desktop_month_short_jan, S.desktop_month_short_feb, S.desktop_month_short_mar,
        S.desktop_month_short_apr, S.desktop_month_short_may, S.desktop_month_short_jun,
        S.desktop_month_short_jul, S.desktop_month_short_aug, S.desktop_month_short_sep,
        S.desktop_month_short_oct, S.desktop_month_short_nov, S.desktop_month_short_dec,
    )
}

/** How a due line is coloured: plain, pink once overdue, amber on the day. */
enum class DueTone { Plain, Overdue, Today }

/** A due line's words and colour. */
data class DueLabel(val text: String, val tone: DueTone)

/** The four surfaces of the Payment Runs page — the web's tabs. */
enum class PaymentTab(val id: String, private val labelKey: String) {
    OpenItems("openItems", S.desktop_open_items),
    Wires("wires", S.desktop_wires),
    Cheques("cheques", S.desktop_cheques),
    Runs("runs", S.desktop_active_runs),
    ;

    val label: String get() = str(labelKey)

}
