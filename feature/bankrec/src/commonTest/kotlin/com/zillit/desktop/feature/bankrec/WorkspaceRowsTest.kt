package com.zillit.desktop.feature.bankrec

import com.zillit.desktop.feature.bankrec.data.WorkspaceDto
import com.zillit.desktop.feature.bankrec.data.asServerCounts
import com.zillit.desktop.feature.bankrec.domain.BankTransaction
import com.zillit.desktop.feature.bankrec.domain.ExceptionType
import com.zillit.desktop.feature.bankrec.domain.FraudStatus
import com.zillit.desktop.feature.bankrec.domain.FraudType
import com.zillit.desktop.feature.bankrec.domain.FxDetail
import com.zillit.desktop.feature.bankrec.domain.LedgerEntry
import com.zillit.desktop.feature.bankrec.domain.LedgerEntryKind
import com.zillit.desktop.feature.bankrec.domain.ServerCounts
import com.zillit.desktop.feature.bankrec.domain.SuggestionKind
import com.zillit.desktop.feature.bankrec.domain.TxnStatus
import com.zillit.desktop.feature.bankrec.domain.WorkspaceFilter
import com.zillit.desktop.feature.bankrec.domain.WorkspaceRows
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

private val unmatchedLine = BankTransaction(id = "t1", vendorName = "Panavision", debit = 1200.0)

private val invoice = LedgerEntry(
    id = "led-1",
    entityId = "inv-1",
    kind = LedgerEntryKind.Invoice,
    supplierName = "Panavision",
    invoiceNumber = "INV-88",
    payMethod = "bacs",
    grossAmount = 1200.0,
)

private fun bank(txn: BankTransaction, ledger: List<LedgerEntry> = listOf(invoice)) =
    WorkspaceRows.bankRow(txn, ledger, "GBP")

private fun ledger(entry: LedgerEntry) = WorkspaceRows.ledgerRow(entry, emptyMap())

/**
 * What each bar under a bank line offers, and the words and figures the
 * workspace derives from the wire — the web's `mapBankTxn`, `counts` and
 * `filterTxn`, as pure functions.
 */
class WorkspaceRowsTest {

    /** A wrong suggestion is often a line with nothing in Zillit to match, so its bar offers Quick Add too. */
    @Test
    fun `a suggested line's bar offers Quick Add`() {
        val row = bank(
            unmatchedLine.copy(status = TxnStatus.Suggested, matchConfidence = 92, matchedInvoiceIds = listOf("inv-1")),
        )

        val bar = row.suggestion!!
        assertEquals(SuggestionKind.Match, bar.kind)
        assertTrue(bar.quickAdd)
        assertNull(row.matchSuggestion)
    }

    /** "Not in Zillit" already has Quick Add as its own action, not as the extra one. */
    @Test
    fun `an unmatched line's Quick Add is its action, not a second button`() {
        val bar = bank(unmatchedLine).suggestion!!

        assertEquals(SuggestionKind.Info, bar.kind)
        assertFalse(bar.quickAdd)
    }

    /** With a match suggested under the fraud bar, that amber bar carries Quick Add and the red one does not. */
    @Test
    fun `under a fraud bar the amber match bar carries Quick Add`() {
        val row = bank(
            unmatchedLine.copy(
                fraudType = FraudType.DuplicatePayment,
                fraudStatus = FraudStatus.Active,
                matchConfidence = 80,
                matchedInvoiceIds = listOf("inv-1"),
            ),
        )

        assertEquals(SuggestionKind.Fraud, row.suggestion!!.kind)
        assertFalse(row.suggestion!!.quickAdd)
        assertTrue(row.matchSuggestion!!.quickAdd)
    }

    /** A fraud line with nothing suggested has no amber bar, so the red bar offers it. */
    @Test
    fun `a fraud line with no suggested match offers Quick Add on its own bar`() {
        val row = bank(unmatchedLine.copy(fraudType = FraudType.RoundLargePayment, fraudStatus = FraudStatus.Active))

        assertNull(row.matchSuggestion)
        assertTrue(row.suggestion!!.quickAdd)
    }

    @Test
    fun `an actioned fraud line keeps Quick Add on its match bar only`() {
        val row = bank(
            unmatchedLine.copy(
                fraudType = FraudType.SplitPayment,
                fraudStatus = FraudStatus.Dismissed,
                matchConfidence = 70,
                matchedInvoiceIds = listOf("inv-1"),
            ),
        )

        assertFalse(row.suggestion!!.quickAdd)
        assertTrue(row.matchSuggestion!!.quickAdd)
    }

    /** What a matched line is reconciled with is the banner unreconcile mode draws. */
    @Test
    fun `a matched line says what it is reconciled with`() {
        val row = bank(unmatchedLine.copy(status = TxnStatus.Matched, matchedInvoiceIds = listOf("inv-1")))

        assertEquals("Reconciled with Panavision INV-88 · £1,200.00 · BACS — undo?", row.reconciledText)
    }

    /** Empty parts are dropped, so a ledger row with no invoice number or pay method leaves no dangling separator. */
    @Test
    fun `a bare ledger row leaves no dangling separator`() {
        val bare = invoice.copy(invoiceNumber = "", payMethod = "")
        val matched = unmatchedLine.copy(status = TxnStatus.Matched, matchedInvoiceIds = listOf("inv-1"))
        val row = bank(matched, listOf(bare))

        assertEquals("Reconciled with Panavision · £1,200.00 — undo?", row.reconciledText)
    }

    /** A line matched here, to a quick entry, names no invoice — it still reads as something. */
    @Test
    fun `a matched line with no known invoice falls back to the payee and amount`() {
        val row = bank(unmatchedLine.copy(status = TxnStatus.Matched))

        assertEquals("Reconciled with Panavision · £1,200.00 — undo?", row.reconciledText)
    }

    @Test
    fun `only a matched line has a reconciled banner`() {
        assertNull(bank(unmatchedLine).reconciledText)
    }

    private fun counted(vararg txns: BankTransaction) = txns.map { bank(it) }

    /** The backend's counts win, key by key; the row tally fills in what a response leaves out. */
    @Test
    fun `server counts win key by key and the tally stands in for the rest`() {
        val rows = counted(unmatchedLine, unmatchedLine.copy(id = "t2", status = TxnStatus.Matched))
        val ledgerRows = listOf(ledger(invoice), ledger(invoice.copy(id = "led-2", entityId = "inv-2")))

        val tallied = WorkspaceRows.counts(rows, ledgerRows)
        assertEquals(3, tallied.unmatched) // one bank line and two ledger entries — the old tally
        assertEquals(2, tallied.all)

        val server = WorkspaceRows.counts(rows, ledgerRows, ServerCounts(unmatched = 1, fx = 4))
        assertEquals(1, server.unmatched) // unmatched ledger entries no longer add to it
        assertEquals(4, server.fx)
        assertEquals(2, server.all) // not sent, so tallied
        assertEquals(1, server.matched)
    }

    @Test
    fun `counts are read off workspace-data whether numbers or text`() {
        val dto = json.decodeFromString(
            WorkspaceDto.serializer(),
            """{"transactions":[],"invoices":[],"counts":""" +
                """{"all":12,"matched":"7","suggested":2,"unmatched":3,"fraud":0}}""",
        )

        assertEquals(
            ServerCounts(all = 12, matched = 7, unmatched = 3, suggested = 2, fraud = 0, fx = null),
            dto.counts.asServerCounts(),
        )
    }

    @Test
    fun `a response without counts reads as none`() {
        val dto = json.decodeFromString(WorkspaceDto.serializer(), """{"transactions":[],"invoices":[]}""")

        assertNull(dto.counts.asServerCounts())
    }

    /** The backend counts an FX line under its own status as well as under FX, so the line lists under both pills. */
    @Test
    fun `an FX line also lists under the pill of its own status`() {
        val fx = bank(
            unmatchedLine.copy(
                currency = "EUR",
                status = TxnStatus.Unmatched,
                fx = FxDetail(foreignAmount = 1000.0, currency = "EUR"),
            ),
        )
        val suggestedFx = bank(
            unmatchedLine.copy(
                id = "t2",
                status = TxnStatus.Suggested,
                fx = FxDetail(foreignAmount = 500.0, currency = "EUR"),
            ),
        )

        assertEquals(TxnStatus.Fx, fx.status)
        assertTrue(WorkspaceFilter.Fx.accepts(fx))
        assertTrue(WorkspaceFilter.Unmatched.accepts(fx))
        assertFalse(WorkspaceFilter.Suggested.accepts(fx))
        assertTrue(WorkspaceFilter.Suggested.accepts(suggestedFx))
        assertFalse(WorkspaceFilter.Unmatched.accepts(suggestedFx))
        assertTrue(WorkspaceFilter.All.accepts(fx))
    }

    @Test
    fun `a plain line is filtered by its own status`() {
        val row = bank(unmatchedLine)

        assertTrue(WorkspaceFilter.Unmatched.accepts(row))
        assertFalse(WorkspaceFilter.Fraud.accepts(row))
        assertFalse(WorkspaceFilter.Fx.accepts(row))
    }

    private val hits = listOf(
        ledger(invoice.copy(id = "a", entityId = "a", supplierName = "Panavision", grossAmount = 1250.0)),
        ledger(
            invoice.copy(id = "b", entityId = "b", supplierName = "Kodak", invoiceNumber = "K-77", grossAmount = 99.5),
        ),
    )

    private fun names(query: String) = WorkspaceRows.manualMatchHits(hits, query, "GBP").map { it.id }

    /** Supplier, ref line or amount — and `1250` finds `-£1,250.00` because the bare figure is searched too. */
    @Test
    fun `manual match search finds by name, reference or amount`() {
        assertEquals(listOf("a", "b"), names(""))
        assertEquals(listOf("a", "b"), names("   "))
        assertEquals(listOf("b"), names("kodak"))
        assertEquals(listOf("b"), names("K-77"))
        assertEquals(listOf("a"), names("1250"))
        assertEquals(listOf("a"), names("-£1,250.00"))
        assertEquals(listOf("b"), names("99.50"))
        assertEquals(emptyList(), names("zzz"))
    }

    @Test
    fun `a fraud exception is its own type and never seeds the entry`() {
        assertEquals(ExceptionType.Fraud, ExceptionType.from("fraud"))
        assertFalse(ExceptionType.Fraud.seedsDescription)
        assertTrue(ExceptionType.BankCharge.seedsDescription)
    }
}
