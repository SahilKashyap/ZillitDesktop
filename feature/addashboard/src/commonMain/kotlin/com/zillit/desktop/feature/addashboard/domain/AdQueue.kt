package com.zillit.desktop.feature.addashboard.domain

/**
 * The three things the AD reads rather than writes: artiste queries, the
 * PO / Payroll queue, and an agency spreadsheet waiting to be committed.
 */

/** One message in a query thread. */
data class QueryMessage(
    val id: String = "",
    val text: String = "",
    val at: Long? = null,
    val authorName: String = "",
    /**
     * `artiste` or `production`.
     *
     * Not enough to tell *which* production user wrote it — every one of them
     * sends `production` — so "You" is decided by the author id, never by this.
     */
    val whoType: String = "",
    val whoId: String = "",
) {
    val fromArtiste: Boolean get() = whoType.equals("artiste", ignoreCase = true)

    /** Whether [userId] wrote this message, which is what marks a bubble "You". */
    fun isMine(userId: String): Boolean = userId.isNotBlank() && whoId == userId
}

/** The day a query is about, as the slim list carries it. */
data class QueryDay(
    val id: String = "",
    val shootDate: Long? = null,
)

/** A question an artiste raised about one of their days. */
data class ArtisteQuery(
    val id: String,
    val topic: String = "",
    val title: String = "",
    val priority: String = "",
    /** `active` until resolved — the web's wire value, not "open". */
    val status: String = "",
    val artisteId: String = "",
    val artisteName: String = "",
    val artisteRef: String = "",
    val day: QueryDay? = null,
    val updated: Long? = null,
    val lastMessage: String = "",
    val messages: List<QueryMessage> = emptyList(),
) {
    val resolved: Boolean get() = status.equals("resolved", ignoreCase = true)

    /**
     * The voucher code the thread is headed with.
     *
     * Built from the UTC calendar date of `shoot_date`, never through the
     * record's `timezone` field: that is stamped from the caller's header and
     * would shift the day.
     */
    val voucherCode: String
        get() {
            val date = day?.shootDate ?: return ""
            val ref = artisteRef.ifBlank { "A" }
            return "VCH-$ref-${AdDates.formatCompactYmd(date)}"
        }
}

/** One line of the day breakdown shown beside a query. */
data class DayBreakdownLine(
    val label: String = "",
    val detail: String = "",
    val amount: Double = 0.0,
)

/**
 * The day behind a query, as its right-hand pane shows it.
 *
 * Deliberately not the voucher total: the web lists the engine's rate and
 * allowance lines only, so additional fees are absent and [total] is the
 * engine's gross.
 */
data class QueryDayContext(
    val shootDate: Long? = null,
    val code: String = "",
    val statusLabel: String = "",
    val callTime: String = "",
    val wrapTime: String = "",
    val lines: List<DayBreakdownLine> = emptyList(),
    val total: Double = 0.0,
    val currency: String = "",
)

/** One agency purchase order a submitted day produced. */
data class AgencyPurchaseOrder(
    val poId: String = "",
    val poReference: String = "",
    val agency: String = "",
    val currency: String = "",
    val amount: Double = 0.0,
    val status: String = "",
    val shootDate: Long? = null,
    val dayNumber: Int? = null,
    val approvedBy: String = "",
) {
    val awaiting: Boolean
        get() = listOf("await", "pending", "countersign").any {
            status.contains(it, ignoreCase = true)
        }
}

/** One direct-PAYE payroll run the server grouped from the days. */
data class PayrollQueueRun(
    val label: String = "",
    val period: String = "",
    val amount: Double = 0.0,
    val currency: String = "",
    val status: String = "",
    val artisteCount: Int? = null,
    val bacsDate: String = "",
)

/** One artiste held back from payroll — onboarding incomplete, right to work unresolved. */
data class PendingPayrollItem(
    val artisteId: String = "",
    val name: String = "",
    val reason: String = "",
    /** `on_hold` or `blocked`. */
    val status: String = "",
) {
    val blocked: Boolean get() = status.equals("blocked", ignoreCase = true)
}

/**
 * The whole PO / Payroll queue, read-only and server-derived.
 *
 * An object of lists, which is why it must never be read as an array: a list
 * reader rewrites a non-array body to empty and every nested list disappears
 * without an error.
 */
data class PoQueue(
    val agencyPos: List<AgencyPurchaseOrder> = emptyList(),
    val payroll: List<PayrollQueueRun> = emptyList(),
    val pending: List<PendingPayrollItem> = emptyList(),
) {
    val isEmpty: Boolean get() = agencyPos.isEmpty() && payroll.isEmpty() && pending.isEmpty()
}

/** One row of an uploaded agency list, after the server has read the file. */
data class ImportRow(
    val index: Int,
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    val phone: String = "",
    val agencyName: String = "",
    /** Set when the server matched the agency name to a registered vendor. */
    val agencyId: String = "",
    val dateOfBirth: String = "",
    val address: String = "",
    val category: ArtisteCategory = ArtisteCategory.GeneralSa,
    val selected: Boolean = true,
) {
    val agencyMatched: Boolean get() = agencyId.isNotBlank()

    val name: String get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")

    /**
     * Why this row cannot be committed.
     *
     * Re-checked on the client even though the server parsed the file: a row
     * with no e-mail address can never pass the extras-portal code check, so
     * the AD fixes it here rather than discovering it a week later.
     */
    val problems: List<ImportProblem>
        get() = buildList {
            if (firstName.isBlank()) add(ImportProblem.MissingFirstName)
            if (lastName.isBlank()) add(ImportProblem.MissingLastName)
            if (!isValidEmail(email)) add(ImportProblem.InvalidEmail)
        }

    val valid: Boolean get() = problems.isEmpty()
}

/** What is wrong with an import row. */
enum class ImportProblem { MissingFirstName, MissingLastName, InvalidEmail }

/** What the server read out of the uploaded file. */
data class ImportPreview(
    val columns: List<String> = emptyList(),
    val rows: List<ImportRow> = emptyList(),
    val totalRows: Int = 0,
    val matchedAgencies: Int = 0,
) {
    val ready: List<ImportRow> get() = rows.filter { it.selected && it.valid }

    val needsReview: List<ImportRow> get() = rows.filterNot { it.valid }
}

/**
 * The one e-mail check the module uses.
 *
 * Deliberately loose — a local part, an `@`, a dotted domain — because the
 * address is verified by a code the server sends to it, not by this.
 */
internal fun isValidEmail(value: String?): Boolean {
    val text = value?.trim().orEmpty()
    if (text.isEmpty() || ' ' in text) return false
    val at = text.indexOf('@')
    if (at <= 0 || at != text.lastIndexOf('@')) return false
    val domain = text.substring(at + 1)
    val dot = domain.indexOf('.')
    return dot > 0 && dot < domain.length - 1
}
