package com.zillit.desktop.feature.cardexpenses.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.Instant

/**
 * What the accountant's process editor works on for one receipt.
 *
 * Read off `GET /receipts/:id/detail` — the queue's own rows carry none of it.
 * The web's `ProcessReceiptModal` is the reference: coded lines are edited, the
 * auto-deductions the server owns travel back **verbatim** (the card service
 * stores what it is sent and does not re-create them — dropping one deletes
 * it), and the consolidated reclaimable-tax line is not a line at all but
 * [taxLine], regenerated on every save (`ProcessReceiptModal.jsx:230-244`).
 */
data class ReceiptProcessing(
    /** Whether the detail has been read; the queue row alone has no lines. */
    val loaded: Boolean = false,
    val lines: List<ProcessLine> = emptyList(),
    /** The auto-deductions, sent back as they came. */
    val fixedLines: List<FixedLine> = emptyList(),
    /** `query`, `review`, `deduct` — the processing rules the receipt tripped. */
    val flags: Set<String> = emptySet(),
    /** The same rules whole — title, threshold, description — for the banners. */
    val rules: List<ProcessingFlag> = emptyList(),
    val cardLimit: Double? = null,
    val cardBalance: Double? = null,
    /** The holder asked for the spend to be topped back up. */
    val requestTopUp: Boolean = false,
    /** The ledger date an accountant set, epoch millis at UTC midnight. */
    val effectiveDate: Long? = null,
    val escalationReason: String? = null,
    /** Who handed the receipt up, and when — the escalation banner's byline. */
    val escalatedBy: String? = null,
    val escalatedAt: Long? = null,
    /** A persisted `is_tax` line, hydrated as a sticky override; null when none was saved. */
    val taxLine: TaxLineDraft? = null,
) {
    val needsQuery: Boolean get() = QUERY in flags
    val needsReview: Boolean get() = REVIEW in flags

    companion object {
        const val QUERY = "query"
        const val REVIEW = "review"
        const val DEDUCT = "deduct"
    }
}

/**
 * One processing rule a receipt tripped, as `processing_flags` carries it.
 *
 * Legacy rows hold the bare flag name; current ones the rule's title, its
 * threshold and a description, which is what the web's banner sentence is
 * built from (`ProcessReceiptModal.jsx:713-767`).
 */
data class ProcessingFlag(
    val flag: String,
    val title: String? = null,
    val description: String? = null,
    val thresholdValue: Double? = null,
    /** `percentage` for a deduction by percent; anything else is an amount. */
    val thresholdType: String? = null,
)

/**
 * One coded line in the editor — the web's `LineItemsEditor` line.
 *
 * The editor works in **net** ([net] is the line's amount); the wire's
 * `amount` is gross (net + tax at the line's rate), derived on save the way the
 * web's `buildPayload` does. A split child carries [splitParentId]; its parent
 * keeps its own amount and the children must add up to it. [raw] is the line as
 * it arrived, so what this editor does not touch — expenditure type, rental
 * dates — round-trips rather than being blanked.
 */
data class ProcessLine(
    val id: String? = null,
    val description: String = "",
    val account: String = "",
    val net: Double = 0.0,
    /** Percent, e.g. 20.0; null for no tax. */
    val taxRate: Double? = null,
    val quantity: Double = 1.0,
    val raw: JsonObject? = null,
    /** The Production Setup tax type's identifier, `other` for a typed rate, blank for none. */
    val taxType: String = "",
    val splitParentId: String? = null,
    /** Layers: `{ set_id: code }`, one pick per tracking set. */
    val trackingCodes: Map<String, String> = emptyMap(),
    /** Account tags from Production Setup. */
    val tags: List<String> = emptyList(),
) {
    val tax: Double get() = round2(net * (taxRate ?: 0.0) / PERCENT)

    val gross: Double get() = round2(net + tax)

    val isSplit: Boolean get() = splitParentId != null

    /** An untouched placeholder — no words and no money (`coa.js isBlankLine`); never blocks a post. */
    val blank: Boolean get() = description.isBlank() && net == 0.0

    /** Amount typed directly: the web pins quantity 1, unit price = amount. */
    fun withAmount(value: Double): ProcessLine = copy(net = value, quantity = 1.0)
}

/**
 * The consolidated reclaimable-tax row (`taxLineMeta`).
 *
 * Its amount follows the recoverable tax on the lines until somebody types
 * over it; then [overridden] holds, and a reset brings the derived figure
 * back. A persisted row hydrates overridden, so reopening never moves it.
 */
data class TaxLineDraft(
    val account: String = "",
    val amount: Double? = null,
    val overridden: Boolean = false,
    val trackingCodes: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
)

/**
 * A line the server owns: an auto-deduction.
 *
 * Counted in the totals (its gross and its own tax) and sent back verbatim.
 * A row with [countsInTotal] false is ignored by the figures and not re-sent.
 */
data class FixedLine(val raw: JsonObject, val gross: Double, val tax: Double, val countsInTotal: Boolean)

/** What happens to the card's balance when the receipt posts. */
enum class TopUpMethod(val wire: String, private val labelKey: String) {
    Restore("restore", S.desktop_card_restore_to_limit),
    Expense("expense", S.desktop_card_top_up_by_expense),
    None("none", S.desktop_card_no_top_up),
    ;

    val label: String get() = str(labelKey)
}

/** A Production Setup tax type — what a line's Tax select offers (`useProjectTaxTypes`). */
data class CardTaxType(
    val identifier: String,
    val label: String,
    /** Percent, e.g. 20.0; null when the type carries no rate. */
    val rate: Double? = null,
    val recoverable: Boolean = false,
    val country: String = "",
) {
    /** `VAT · 20%`, the web's option label. */
    val optionLabel: String
        get() = rate?.let { "$label · ${if (it == it.toLong().toDouble()) it.toLong() else it}%" } ?: label
}

/** One postable row of the Chart of Accounts, for the code typeahead. */
data class CardCoaAccount(val code: String, val name: String) {
    /** `4100 — Props`, the web's `coaLabel`. */
    val label: String get() = if (name.isBlank()) code else "$code — $name"
}

/** A Layers set and its pickable codes (`TrackingCodesPicker`). */
data class TrackingSet(val id: String, val name: String, val nodes: List<TrackingNode>)

data class TrackingNode(val code: String, val label: String)

/**
 * The cost report's close boundary — `useCrLock`.
 *
 * Everything dated on or before [lockedThrough] is read-only; the server
 * enforces it and the screens pre-cap their pickers and freeze their rows so
 * nobody is offered what will be refused.
 */
data class CardPeriodLock(
    /** `YYYY-MM-DD`; blank when the production has no lock. */
    val lockedThrough: String = "",
    /** The production's zone for turning a stamp into its day; blank is London. */
    val timeZone: String = "",
) {
    val isSet: Boolean get() = lockedThrough.isNotBlank()

    /** `isDateLocked`: a stamp whose day, in the production's zone, is on or before the lock. */
    fun isLocked(millis: Long?): Boolean {
        if (!isSet || millis == null || millis <= 0) return false
        val zone = runCatching { TimeZone.of(timeZone.ifBlank { DEFAULT_ZONE }) }.getOrDefault(TimeZone.UTC)
        val day = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date.toString()
        return day <= lockedThrough
    }

    /** `lockedMinDateInput`: the first day a date may take — the day after the lock, or null. */
    val firstOpenDay: String?
        get() = lockedThrough.takeIf { isSet }?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()?.plus(1, DateTimeUnit.DAY)?.toString()
        }

    /** `lockedDefaultDateInput`: `max(today, lock + 1)`, so a default never sits behind the cap. */
    fun defaultDay(today: String): String = firstOpenDay?.takeIf { it > today } ?: today

    private companion object {
        const val DEFAULT_ZONE = "Europe/London"
    }
}

/**
 * Everything the process pages read besides the receipts: the close boundary,
 * the tax types, the chart, the Layers sets and the account tags. Each part
 * degrades to empty on its own — a missing chart still leaves a typed code.
 */
data class ProcessRefs(
    val loaded: Boolean = false,
    val lock: CardPeriodLock = CardPeriodLock(),
    val taxTypes: List<CardTaxType> = emptyList(),
    /** The tax types answered; until then the reclaimable-tax swap is held off (`taxTypesReady`). */
    val taxTypesKnown: Boolean = false,
    val accounts: List<CardCoaAccount> = emptyList(),
    val trackingSets: List<TrackingSet> = emptyList(),
    val assetTags: List<String> = emptyList(),
) {
    fun accountLabel(code: String?): String {
        val trimmed = code?.trim().orEmpty()
        if (trimmed.isEmpty()) return "—"
        return accounts.firstOrNull { it.code == trimmed }?.label ?: trimmed
    }
}

/**
 * The arithmetic of the process editor, the web's to the letter
 * (`ProcessReceiptModal.jsx:314-362`). Every figure is gross unless it says
 * otherwise.
 *
 * The coded totals sum **leaf** lines — split children and childless parents,
 * never a split parent, whose children are its allocation. The reclaimable tax
 * is the recoverable tax on the parent lines; a typed override stands in for
 * it, and until the tax types are known ([taxTypesKnown] false) nothing is
 * swapped, so a hydrated override does not overshoot.
 */
data class ProcessFigures(
    val receiptAmount: Double,
    val lines: List<ProcessLine>,
    val fixedLines: List<FixedLine>,
    val cardLimit: Double?,
    val cardBalance: Double?,
    val taxLine: TaxLineDraft = TaxLineDraft(),
    val taxTypes: List<CardTaxType> = emptyList(),
    val taxTypesKnown: Boolean = false,
) {
    private val counted = fixedLines.filter { it.countsInTotal }

    private val leaves: List<ProcessLine>
        get() = lines.filter { line ->
            line.isSplit || lines.none { it.splitParentId != null && it.splitParentId == line.id }
        }

    private val editorNet: Double get() = leaves.sumOf { it.net }
    private val editorTax: Double get() = leaves.sumOf { it.net * (it.taxRate ?: 0.0) / PERCENT }
    private val autoGross: Double get() = counted.sumOf { it.gross }
    private val autoTax: Double get() = counted.sumOf { it.tax }

    /** The recoverable tax on the parent lines — the tax row's derived amount. */
    val reclaimableTax: Double
        get() {
            val recoverable = taxTypes.filter { it.recoverable }.map { it.identifier }.toSet()
            return round2(
                lines.filter { !it.isSplit && it.taxType in recoverable }
                    .sumOf { it.net * (it.taxRate ?: 0.0) / PERCENT },
            )
        }

    /** What the tax row posts: the typed override, else the derived sum. */
    val effectiveTax: Double
        get() = if (taxLine.overridden) round2(taxLine.amount ?: 0.0) else reclaimableTax

    private val taxSubBase: Double get() = if (taxTypesKnown) reclaimableTax else effectiveTax

    /** Whether the tax row goes on the wire: an amount, or any typed override (a typed 0 must persist). */
    val sendsTaxLine: Boolean get() = effectiveTax > 0 || taxLine.overridden

    val net: Double get() = round2(editorNet + (autoGross - autoTax))
    val tax: Double get() = round2(editorTax - taxSubBase + effectiveTax + autoTax)
    val gross: Double get() = round2(editorNet + editorTax - taxSubBase + effectiveTax + autoGross)

    /** The line grid's own footer — coded lines only, parents only (`LineItemsEditor.jsx:113-127`). */
    val codedNet: Double get() = round2(lines.filter { !it.isSplit }.sumOf { it.net })
    val codedTax: Double
        get() = round2(
            lines.filter { !it.isSplit }.sumOf { it.net * (it.taxRate ?: 0.0) / PERCENT } + effectiveTax - taxSubBase,
        )
    val codedGross: Double get() = round2(codedNet + codedTax)

    /**
     * Coded gross must reconcile to the receipt, to the penny, before a save
     * or a post — over **or** under. A receipt with no amount never blocks:
     * there is nothing authoritative to match (`money.js isAmountMismatch`).
     */
    val mismatch: Boolean
        get() = receiptAmount > 0 && abs(round2(gross - receiptAmount)) > PENNY

    /** What will actually be debited: the lines' total when it is lower. */
    val effectiveAmount: Double
        get() = if (gross > 0 && gross < receiptAmount) gross else receiptAmount

    private val limit: Double get() = cardLimit ?: 0.0
    private val balance: Double get() = cardBalance ?: limit

    val balanceAfter: Double get() = (balance - effectiveAmount).coerceAtLeast(0.0)

    fun topUpAmount(method: TopUpMethod): Double = when (method) {
        TopUpMethod.Restore -> (limit - balance + effectiveAmount).coerceAtLeast(0.0)
        TopUpMethod.Expense -> effectiveAmount
        TopUpMethod.None -> 0.0
    }

    /** A top-up that would carry the card past its limit — refused, as the web refuses it. */
    fun topUpOverfills(method: TopUpMethod): Boolean {
        val topUp = topUpAmount(method)
        return topUp > 0 && (balance - effectiveAmount) + topUp > limit
    }

    /** The largest top-up the card can take after this expense. */
    val topUpHeadroom: Double get() = limit - (balance - effectiveAmount)

    /**
     * One-based numbers of the lines that would reach the ledger without a
     * nominal — the tax row too, as the last number, when it is sent
     * (`missingNominalLines`). A blank placeholder line never counts.
     */
    val linesMissingNominal: List<Int>
        get() = checkedRows().mapIndexedNotNull { index, (_, missing) -> (index + 1).takeIf { missing } }

    /** The ids of the rows [linesMissingNominal] names; the tax row as [TAX_ROW]. */
    val idsMissingNominal: Set<String>
        get() = checkedRows().filter { it.second }.mapNotNull { it.first }.toSet()

    private fun checkedRows(): List<Pair<String?, Boolean>> {
        val coded = lines.map { line -> line.id to (!line.blank && line.account.isBlank()) }
        if (!sendsTaxLine) return coded
        val taxBlank = effectiveTax == 0.0
        return coded + (TAX_ROW to (!taxBlank && taxLine.account.isBlank()))
    }

    companion object {
        /** The tax row's key in the missing-nominal set (`TAX_LINE_KEY`). */
        const val TAX_ROW = "__tax__"
    }
}

/**
 * The line grid's edits — the web's `LineItemsEditor` and `lib/lineItemSplit`,
 * kept free of the screen so each rule can be pinned on its own.
 */
object ProcessLines {

    /**
     * Changes one line.
     *
     * A parent's code, Layers, tags and tax reach its children that still held
     * the old value (or nothing) — they inherited them when they were cut; a
     * child someone customised keeps its own. A parent whose money moved
     * re-cuts its children so they still add up to it.
     */
    fun update(lines: List<ProcessLine>, id: String, change: (ProcessLine) -> ProcessLine): List<ProcessLine> {
        val before = lines.firstOrNull { it.id == id } ?: return lines
        val after = change(before)
        var next = lines.map { if (it.id == id) after else it }
        if (!after.isSplit) {
            next = next.map { child -> if (child.splitParentId == id) cascade(before, after, child) else child }
            val moneyMoved = after.net != before.net ||
                after.taxRate != before.taxRate ||
                after.taxType != before.taxType
            if (moneyMoved) next = rescale(next, id)
        }
        return next
    }

    private fun cascade(before: ProcessLine, after: ProcessLine, child: ProcessLine): ProcessLine {
        var out = child
        if (after.account != before.account && (child.account.isBlank() || child.account == before.account)) {
            out = out.copy(account = after.account)
        }
        if (after.trackingCodes != before.trackingCodes &&
            (child.trackingCodes.isEmpty() || child.trackingCodes == before.trackingCodes)
        ) {
            out = out.copy(trackingCodes = after.trackingCodes)
        }
        if (after.tags != before.tags && (child.tags.isEmpty() || child.tags == before.tags)) {
            out = out.copy(tags = after.tags)
        }
        // A child's tax is never its own — its cell is a static dash — so it
        // always follows the parent.
        if (after.taxType != before.taxType || after.taxRate != before.taxRate) {
            out = out.copy(taxType = after.taxType, taxRate = after.taxRate)
        }
        return out
    }

    /** A child's own amount changed: its siblings share what is left, the last taking the rounding. */
    fun redistribute(lines: List<ProcessLine>, id: String, amount: Double): List<ProcessLine> {
        val target = lines.firstOrNull { it.id == id } ?: return lines
        val parentId = target.splitParentId ?: return lines
        val parent = lines.firstOrNull { it.id == parentId } ?: return lines
        val siblings = lines.filter { it.splitParentId == parentId && it.id != id }
        val remaining = parent.net - amount
        val per = if (siblings.isEmpty()) 0.0 else round2(remaining / siblings.size)
        val last = if (siblings.isEmpty()) 0.0 else round2(remaining - per * (siblings.size - 1))
        var index = 0
        return lines.map { line ->
            when {
                line.id == id -> line.withAmount(amount)
                line.splitParentId == parentId -> {
                    val isLast = index == siblings.size - 1
                    index++
                    line.withAmount(if (isLast) last else per)
                }

                else -> line
            }
        }
    }

    /** Re-cuts a parent's children to its amount, keeping their shares. */
    fun rescale(lines: List<ProcessLine>, parentId: String): List<ProcessLine> {
        val parent = lines.firstOrNull { it.id == parentId } ?: return lines
        val children = lines.filter { it.splitParentId == parentId }
        if (children.isEmpty()) return lines
        val current = children.sumOf { it.net }
        val shares = if (current > 0) children.map { it.net / current } else children.map { 1.0 / children.size }
        val amounts = shares.map { round2(parent.net * it) }.toMutableList()
        amounts[amounts.lastIndex] = round2(parent.net - amounts.dropLast(1).sum())
        var index = 0
        return lines.map { if (it.splitParentId == parentId) it.withAmount(amounts[index++]) else it }
    }

    /**
     * Splits the selected line — or its parent, when a child is selected.
     *
     * The first split cuts the parent in two halves; each later one adds a
     * sibling and evens them all out, the last taking the rounding. Returns the
     * lines and the new selection.
     */
    @Suppress("ReturnCount") // One refusal per missing piece, as the web's engine returns early.
    fun split(lines: List<ProcessLine>, selectedId: String?, newId: () -> String): Pair<List<ProcessLine>, String?> {
        val source = lines.firstOrNull { it.id != null && it.id == selectedId } ?: return lines to selectedId
        val parentId = source.splitParentId ?: source.id ?: return lines to selectedId
        val parent = lines.firstOrNull { it.id == parentId } ?: return lines to selectedId
        val existing = lines.filter { it.splitParentId == parentId }
        fun child(amount: Double) = ProcessLine(
            id = newId(),
            description = parent.description,
            account = parent.account,
            taxRate = parent.taxRate,
            taxType = parent.taxType,
            splitParentId = parentId,
            trackingCodes = parent.trackingCodes,
            tags = parent.tags,
        ).withAmount(amount)
        val at = lines.indexOfFirst { it.id == source.id } + 1
        if (existing.isEmpty()) {
            val half = round2(parent.net / 2)
            val first = child(half)
            val second = child(round2(parent.net - half))
            return lines.toMutableList().apply { addAll(at, listOf(first, second)) } to first.id
        }
        val count = existing.size + 1
        val per = round2(parent.net / count)
        val last = round2(parent.net - per * (count - 1))
        var index = 0
        val evened = lines.map { line ->
            if (line.splitParentId == parentId) {
                val isLast = index == existing.size - 1
                index++
                line.withAmount(if (isLast) last else per)
            } else {
                line
            }
        }
        val added = child(per)
        return evened.toMutableList().apply { add(at, added) } to added.id
    }

    /**
     * Removes a line. The last parent stays; a parent takes its children with
     * it; removing one of two children folds the split back into the parent,
     * and otherwise the siblings share the parent again.
     */
    @Suppress("ReturnCount") // The web's four cases, each returning where it is decided.
    fun remove(lines: List<ProcessLine>, id: String): List<ProcessLine> {
        val line = lines.firstOrNull { it.id == id } ?: return lines
        if (!line.isSplit) {
            val lastParent = lines.count { !it.isSplit } <= 1
            return if (lastParent) lines else lines.filter { it.id != id && it.splitParentId != id }
        }
        val remaining = lines.filter { it.id != id }
        val siblings = remaining.filter { it.splitParentId == line.splitParentId }
        if (siblings.size == 1) return remaining.filter { it.id != siblings.single().id }
        val parent = remaining.firstOrNull { it.id == line.splitParentId } ?: return remaining
        val each = parent.net / siblings.size
        return remaining.map { if (it.splitParentId == line.splitParentId) it.withAmount(each) else it }
    }

    /** Whether any line was cut from [id] — its amount is then the read-only sum. */
    fun hasSplits(lines: List<ProcessLine>, id: String?): Boolean =
        id != null && lines.any { it.splitParentId == id }
}

/**
 * Who may do what in the process editor.
 *
 * The web's header buttons (`ProcessReceiptModal.jsx:503-570`) and the
 * queue's row lock (`ProcessPage.jsx:436-438`), in one place so the screen
 * and the view model's handlers cannot drift apart.
 */
object ProcessRules {

    /**
     * Whether this accountant may open the receipt at all.
     *
     * A senior works any row; everyone else only the rows assigned to them. An
     * unassigned row is a senior's to hand out.
     */
    fun canOpen(viewer: CardViewer, receipt: CardReceipt): Boolean {
        if (!viewer.isAccountant) return false
        val assignee = receipt.assignedTo
        return viewer.isSenior || (!assignee.isNullOrBlank() && assignee == viewer.userId)
    }

    /**
     * Whether Post is offered: hidden from a non-senior while a review or
     * query rule is on the receipt, and from anyone whose posting limit is
     * below what will be debited.
     */
    fun canPost(viewer: CardViewer, processing: ReceiptProcessing, effectiveAmount: Double): Boolean {
        if (!viewer.isAccountant) return false
        if (!viewer.isSenior && (processing.needsReview || processing.needsQuery)) return false
        val limit = viewer.metadata.postingLimit
        return limit == null || limit >= effectiveAmount
    }

    /** Escalating and submitting for review are how a non-senior hands a receipt up. */
    fun canHandUp(viewer: CardViewer): Boolean = viewer.isAccountant && !viewer.isSenior

    /** A row's ledger day for the lock: its effective date, else its own date (`effective_date ?? date`). */
    fun lockDate(receipt: CardReceipt): Long? = receipt.processing.effectiveDate ?: receipt.date

    /** Approval rows dated in the closed period cannot be approved or overridden (`ApprovalQueuePage.jsx:76`). */
    fun approvalSelectable(receipt: CardReceipt, lock: CardPeriodLock): Boolean = !lock.isLocked(lockDate(receipt))

    /**
     * Bulk rows: out of the closed period, and the viewer's to post — a
     * senior's any, everyone else's only their own (`BulkProcessPage.jsx:126-134`).
     */
    fun bulkSelectable(viewer: CardViewer, receipt: CardReceipt, lock: CardPeriodLock): Boolean =
        !lock.isLocked(lockDate(receipt)) &&
            (viewer.isSenior || (!receipt.assignedTo.isNullOrBlank() && receipt.assignedTo == viewer.userId))
}

/** The reclaimable-tax row as it is posted: an `is_tax` line appended after the rest. */
data class TaxLineWire(
    val amount: Double,
    val account: String,
    val trackingCodes: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
)

/**
 * One `save-process` call.
 *
 * [status] tells the four buttons apart: null saves, `posted` posts,
 * `under_review` submits for review, `escalated` escalates — the web's
 * `saveAndAction` (`ProcessReceiptModal.jsx:446-460`).
 */
data class ProcessSubmission(
    val lines: List<ProcessLine>,
    val fixedLines: List<FixedLine>,
    val net: Double,
    val tax: Double,
    val gross: Double,
    val description: String?,
    val nominalCode: String?,
    val effectiveDate: Long?,
    val userId: String,
    val status: String? = null,
    val topUpMethod: TopUpMethod? = null,
    val topUpAmount: Double? = null,
    val escalationReason: String? = null,
    /** The reclaimable-tax row, when it is sent; see [ProcessFigures.sendsTaxLine]. */
    val taxLine: TaxLineWire? = null,
) {
    companion object {
        const val POSTED = "posted"
        const val UNDER_REVIEW = "under_review"
        const val ESCALATED = "escalated"
    }
}

/** Handing a receipt to someone: the web's `{assign_to, assigned_by, reason}`. */
data class ReceiptAssignment(val assignTo: String, val assignedBy: String, val reason: String)

/**
 * Activating an approved card: what kind it is, its number, and — for a card
 * requested without one — the provider it is held with, whose bank and
 * company ride along (`CardRegisterPage.jsx:460-506`).
 */
data class CardActivation(
    val cardType: CardType,
    val cardNumber: String,
    val providerId: String? = null,
    val bankId: String? = null,
    val companyId: String? = null,
)

/**
 * One register row as the export prints it (`CardRegisterPage.jsx:369-381`):
 * the names are resolved here, because the server has only ids.
 */
data class CardExportRow(
    val id: String,
    val last4: String,
    val holder: String,
    val department: String,
    val issuer: String,
    val status: String,
    val currency: String,
    val limit: Double,
    val balance: Double?,
)

/** The two file kinds the card exports come in. */
enum class ExportFormat(val wire: String, val extension: String) {
    Pdf("pdf", "pdf"),
    Excel("xlsx", "xlsx"),
}

internal fun round2(value: Double): Double = (value * HUNDRED).roundToLong() / HUNDRED

private const val PERCENT = 100.0
private const val HUNDRED = 100.0
private const val PENNY = 0.01
