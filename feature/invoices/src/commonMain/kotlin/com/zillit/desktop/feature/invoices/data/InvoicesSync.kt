package com.zillit.desktop.feature.invoices.data

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.invoices.domain.InvoiceRefresh
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire events that stale the invoice rows on screen.
 *
 * Every name is a `socket.on` subscription the web registers on the shared
 * socket via `registerAccountHubListeners` (`listenerSocket.js:2658-2661`);
 * the handler map (`accountHubListeners.js:548-700`) bridges each to
 * `ah:invoice:list` / `ah:invoice:approval` refetch keys that the pages answer
 * with a plain refetch (`DepartmentInvoiceModule.jsx:725`,
 * `ApprovalPage.jsx:203-204`, `RegisterPage.jsx:368`, `InboxPage.jsx:196`,
 * `EntryPage.jsx:322`) and the open detail modal re-reads by id
 * (`InvoiceDetailModal.jsx:272`).
 *
 * The two `activeRun:*` names ride along because a rejected or cancelled
 * payment run releases its invoices back into the register
 * (`accountHubListeners.js:698-711`), and the two credit-note dispute events
 * because a dispute holds — and its resolution releases — the linked invoice
 * (`accountHubListeners.js:732-742`). The rest of the payment-run, credit-note
 * and sales streams announce pages this port does not show and are left out.
 */
val INVOICE_ROW_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("invoice:created"),
    SocketEventName("invoice:updated"),
    SocketEventName("invoice:deleted"),
    SocketEventName("invoice:matched_to_po"),
    SocketEventName("invoice:hold"),
    SocketEventName("invoice:released"),
    SocketEventName("invoice:awaiting_approval"),
    SocketEventName("invoice:approval"),
    SocketEventName("invoice:approved"),
    SocketEventName("invoice:override"),
    SocketEventName("invoice:rejected"),
    SocketEventName("invoice:posted_to_ledger"),
    SocketEventName("invoice:assigned"),
    SocketEventName("invoice:paid"),
    SocketEventName("invoice:bulk_status_changed"),
    // A bulk upload's extraction progress: its invoices land in the Inbox one
    // by one, and Ongoing Uploads re-reads its counts (`UploadsTab`'s nudge).
    SocketEventName("invoice:bulk_upload_progress"),
    SocketEventName("activeRun:rejected"),
    SocketEventName("activeRun:cancelled"),
    SocketEventName("creditNote:dispute_created"),
    SocketEventName("creditNote:dispute_resolved"),
    // The pages that are not invoice lists re-read on their own streams too
    // (`accountHubListeners.js` → `ah:credit_note:list`, `ah:sales_invoice:list`,
    // `ah:accrual:list` and the runs' events), so no page needs a Refresh button:
    // a refresh re-reads only the page that is open.
    SocketEventName("activeRun:created"),
    SocketEventName("activeRun:awaiting_approval"),
    SocketEventName("activeRun:approved"),
    SocketEventName("activeRun:approval_revoked"),
    SocketEventName("creditNote:created"),
    SocketEventName("creditNote:updated"),
    SocketEventName("creditNote:deleted"),
    SocketEventName("creditNote:applied"),
    SocketEventName("creditNote:paid"),
    SocketEventName("salesInvoice:created"),
    SocketEventName("salesInvoice:sent"),
    SocketEventName("salesInvoice:paid"),
    SocketEventName("salesInvoice:overdue"),
    SocketEventName("accruals:generated"),
)

/**
 * Events that stale the reference data instead: the vendor list
 * (`accountHubListeners.js:371-390` → `ah:vendor:list`,
 * `InvoicesModule.jsx:347`), the approval-tier configs
 * (`accountHubListeners.js:399-401` fans `approval_tier:*` into
 * `ah:invoice:approval_tier` when the module is invoices,
 * `InvoicesModule.jsx:354`), and the settings document that gates row actions
 * (`accountHubListeners.js:769-776` → `ah:invoice:settings`,
 * `InvoicesModule.jsx:351`, `EntryPage.jsx:189`).
 */
val INVOICE_REFERENCE_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("vendor:created"),
    SocketEventName("vendor:updated"),
    SocketEventName("vendor:verified"),
    SocketEventName("vendor:unverified"),
    SocketEventName("vendor:deleted"),
    SocketEventName("approval_tier:configured"),
    SocketEventName("approval_tier:updated"),
    SocketEventName("approval_tier:deleted"),
    SocketEventName("invoice:settings:updated"),
    SocketEventName("team:posting_rights_updated"),
)

val INVOICE_SYNC_EVENTS: List<SocketEventName> =
    INVOICE_ROW_SYNC_EVENTS + INVOICE_REFERENCE_SYNC_EVENTS

/** Which of the tool's reads [event] invalidates. */
internal fun invoiceRefreshFor(event: SocketEventName): InvoiceRefresh =
    if (event in INVOICE_REFERENCE_SYNC_EVENTS) InvoiceRefresh.Reference else InvoiceRefresh.Rows

/**
 * The slice of the account-hub envelope this port reads: which production the
 * frame is about. The web drops cross-project frames before any handler runs
 * (`accountHubListeners.js:14-33, 2060-2072`); the rest of the payload is
 * ignored because the port refetches rather than patching rows.
 */
@Serializable
internal data class InvoiceSyncEnvelope(
    @SerialName("project_id") val projectId: String? = null,
) {
    /** A frame that names another production is not ours; unnamed ones pass. */
    fun inProject(here: String?): Boolean =
        projectId == null || here == null || projectId == here
}
