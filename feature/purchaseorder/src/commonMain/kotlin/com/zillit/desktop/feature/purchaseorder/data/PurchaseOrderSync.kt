package com.zillit.desktop.feature.purchaseorder.data

import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.feature.purchaseorder.domain.PoRefresh
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire events that mean "somebody's purchase order changed" — two families,
 * because two backends emit them.
 *
 * The hub family (`po:*`) is what the `/api/v2/purchase-orders` service this
 * module actually talks to announces: the web bridges every one of them to an
 * `ah:po:list` / `ah:po:approval` refetch key (`accountHubListeners.js:161-244`)
 * which `PurchaseOrdersModule.jsx:2016-2017` answers with a plain refetch.
 *
 * The classic family (`purchase-order:*`) is the film-tool PO module's stream,
 * bridged one event to one custom event in `src/socket/listenerSocket.js:1332-1412`
 * (`purchase-order:create` → `purchase_order_create`, `purchase-order:accept` →
 * both `purchase_order_accept` and `purchaseorder_status_accepted`, and so on);
 * the pages under `components/purchaseOrderModule` refetch on each
 * (`ApprovePurchaseOrder.jsx:80-188`, `ListofApprovedpo.jsx:39-49`). Subscribed
 * here too because both spellings exist in production and an event that never
 * fires costs nothing, while a missed one leaves a stale queue on screen.
 */
val PO_ORDER_SYNC_EVENTS: List<SocketEventName> = listOf(
    // -- hub family: accountHubListeners.js PO_HANDLERS (161-244) ----------
    SocketEventName("po:draft_created"),
    SocketEventName("po:created"),
    SocketEventName("po:submitted"),
    SocketEventName("po:tier_approved"),
    SocketEventName("po:fully_approved"),
    SocketEventName("po:rejected"),
    SocketEventName("po:acct_entered"),
    SocketEventName("po:posted"),
    SocketEventName("po:closed"),
    SocketEventName("po:updated"),
    SocketEventName("po:deleted"),
    SocketEventName("po:bulk_op_done"),
    SocketEventName("po:reassigned"),
    SocketEventName("po:approval_queue_changed"),
    // -- classic family: listenerSocket.js 1332-1412 ------------------------
    SocketEventName("purchase-order:create"),
    SocketEventName("purchase-order:update"),
    SocketEventName("purchase-order:accept"),
    SocketEventName("purchase-order:reject"),
    SocketEventName("purchase-order:reinit"),
    SocketEventName("purchase-order:user:approval:added"),
    SocketEventName("purchase-order:user:approval:removed"),
    SocketEventName("purchase-order:approval-level:delete"),
    // The company/PO-settings edit (`purchaseorder_companyedit` on the web,
    // CompanyIndex.jsx:91) — order headers carry company details, so the list
    // is refetched rather than left showing the old letterhead.
    SocketEventName("purchase-order:posetting:update"),
)

/**
 * Events that stale the vendor picker rather than the order lists.
 *
 * `vendor:*` from the hub (`accountHubListeners.js:371-390`, answered by
 * `PurchaseOrdersModule.jsx:1959` with `fetchVendors`); the classic tool's
 * `purchase-order:supplier:update` → `supplier_updated`
 * (`listenerSocket.js:1399-1401`, `Suppliers.jsx:51`,
 * `CreatePurchaseOrder.jsx:381`). `supplier_created` is bridged on the web but
 * no page listens to it, so it is not subscribed here either.
 */
val PO_VENDOR_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("vendor:created"),
    SocketEventName("vendor:updated"),
    SocketEventName("vendor:verified"),
    SocketEventName("vendor:unverified"),
    SocketEventName("vendor:deleted"),
    SocketEventName("purchase-order:supplier:update"),
)

val PO_SYNC_EVENTS: List<SocketEventName> = PO_ORDER_SYNC_EVENTS + PO_VENDOR_SYNC_EVENTS

/** Which of the tool's reads [event] invalidates. */
internal fun poRefreshFor(event: SocketEventName): PoRefresh =
    if (event in PO_VENDOR_SYNC_EVENTS) PoRefresh.Vendors else PoRefresh.Orders

/**
 * The slice of the payload every family carries: which production it is about.
 *
 * Hub events arrive in the uniform account-hub envelope
 * `{project_id, user_id, device_id, data}` and the web drops cross-project
 * frames before any handler runs (`accountHubListeners.js:14-33, 2060-2072`);
 * classic frames carry `project_id` at the top level too
 * (`ApprovePurchaseOrder.jsx:80-87` compares it). Everything else is ignored —
 * this port refetches rather than patching rows in place.
 */
@Serializable
internal data class PoSyncEnvelope(
    @SerialName("project_id") val projectId: String? = null,
) {
    /** A frame that names another production is not ours; unnamed ones pass. */
    fun inProject(here: String?): Boolean =
        projectId == null || here == null || projectId == here
}
