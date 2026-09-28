package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.feature.invoices.domain.CreditorAgeing
import com.zillit.desktop.feature.invoices.domain.CreditorFilter
import com.zillit.desktop.feature.invoices.domain.CreditorSort
import com.zillit.desktop.feature.invoices.domain.Invoice
import com.zillit.desktop.feature.invoices.domain.WireAttachment

/**
 * Payment Runs' own screen state beyond its rows — the local state of the
 * web's `PaymentsPage` (`PaymentsPage.jsx:990-1070`).
 *
 * Three tick sets, not one: Open Items starts with everything ticked and is
 * re-ticked whenever its rows change, while Wires and Cheques start empty and
 * are never ticked for the reader. None of them is cleared by moving between
 * tabs.
 */
data class PaymentsUi(
    /** The web's `selectedInvoices` — Open Items. */
    val openItemsSelected: Set<String> = emptySet(),
    /** The web's `selectedWires` — the Wires tab's bulk Mark Paid. */
    val wiresSelected: Set<String> = emptySet(),
    /** The web's `selectedCheques` — ticks only; a cheque is printed from its detail. */
    val chequesSelected: Set<String> = emptySet(),
    /** Wires and faster payments already marked paid — "Recently Marked as Paid". */
    val recentlyPaid: List<Invoice> = emptyList(),
    /** Wires being marked paid one at a time — each row's own "Marking…". */
    val markingPaid: Set<String> = emptySet(),
    /** A bulk Mark Paid in flight — the header's and the bulk bar's "Marking…". */
    val bulkMarking: Boolean = false,
    /** BACs runs being created — the header's "Creating…". */
    val creatingRun: Boolean = false,
    /** Active Runs' own loader — "Loading runs…" on the first read only. */
    val runsLoading: Boolean = false,
    /**
     * The settings document has answered, or failed — the web's
     * `runSettingsLoaded`, which the "no authoriser" banner waits for.
     */
    val settingsLoaded: Boolean = false,
    /** The run authorisation bar's names: user id → "Name (Designation)". */
    val authLabels: Map<String, String> = emptyMap(),
    /** The bank confirmations of one paid wire, when open. */
    val wireAttachments: WireAttachmentsView? = null,
) {
    /** What leaving the page forgets — the ticks and the dialog; the settings stay read. */
    fun cleared(): PaymentsUi = PaymentsUi(settingsLoaded = settingsLoaded, authLabels = authLabels)
}

/**
 * "Wire Confirmation — {ref}": one paid wire's confirmations, the upload in
 * flight, the removal in flight (one at a time — ZL-20536), and the file open
 * in the viewer (`WireAttachmentsModal`, `PaymentsPage.jsx:704-867`).
 */
data class WireAttachmentsView(
    val invoice: Invoice,
    val attachments: List<WireAttachment> = invoice.wireAttachments,
    val uploading: Boolean = false,
    /** The key being removed; every Remove waits while one is. */
    val removing: String? = null,
    val viewing: WireAttachmentPreview? = null,
)

/** The viewer over one confirmation — the web's `WireAttachmentViewer`. */
data class WireAttachmentPreview(
    val attachment: WireAttachment,
    val loading: Boolean = true,
    val bytes: AttachmentBytes? = null,
) {
    val failed: Boolean get() = !loading && bytes == null
}

/** Creditors Control's three selects (`CreditorsPage.jsx:264-269`). */
data class CreditorsUi(
    val filter: CreditorFilter = CreditorFilter.All,
    val sort: CreditorSort = CreditorSort.BalanceDesc,
    val ageing: CreditorAgeing = CreditorAgeing.All,
)

/** Payment Runs' and Creditors' own events, beside the shared [InvoicesEvent]s. */
sealed interface PaymentsEvent : InvoicesEvent {
    /** One Open Items row's tick. */
    data class ToggleOpenItem(val id: String) : PaymentsEvent

    /** A Wires row's tick, and the bulk bar's Mark Paid and Clear. */
    data class ToggleWire(val id: String) : PaymentsEvent
    data object MarkWiresPaid : PaymentsEvent
    data object ClearWires : PaymentsEvent

    data class ToggleCheque(val id: String) : PaymentsEvent

    /**
     * A Wires or Cheques row's click: with anything ticked on that tab it
     * ticks the row; otherwise it opens the detail (`PaymentsPage.jsx:1845`).
     */
    data class ClickRow(val invoice: Invoice) : PaymentsEvent

    /** The detail's own Mark Paid, for a wire or faster payment. */
    data class MarkPaidFromDetail(val invoice: Invoice) : PaymentsEvent

    /** "View Attachments (n)" / "Attach Confirmation" on a paid wire. */
    data class OpenWireAttachments(val invoice: Invoice) : PaymentsEvent
    data object CloseWireAttachments : PaymentsEvent
    data object AddWireAttachment : PaymentsEvent
    data class RemoveWireAttachment(val key: String) : PaymentsEvent
    data class ViewWireAttachment(val attachment: WireAttachment) : PaymentsEvent
    data object CloseWireAttachmentViewer : PaymentsEvent
    data object DownloadWireAttachment : PaymentsEvent

    data class SelectCreditorFilter(val filter: CreditorFilter) : PaymentsEvent
    data class SelectCreditorSort(val sort: CreditorSort) : PaymentsEvent
    data class SelectCreditorAgeing(val ageing: CreditorAgeing) : PaymentsEvent
}
