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
    // Both spellings are bridged on the web (`listenerSocket.js:1379/1383`)
    // and both aliases are consumed by two components each — the backend's
    // choice is not settled, so subscribe to the pair.
    SocketEventName("purchase-order:approval-level:delete"),
    SocketEventName("purchase-order:approval-level:removed"),
    // The create/update twins, added 2026-09-09: subscribing to a level's
    // removal but not its creation left a queue that shrank live and grew
    // only on reload. Android answers `create` with `_poApprovalLevel` and
    // logs `update`; iOS answers all three with `.updatePoLevelsNotification`.
    SocketEventName("purchase-order:approval-level:create"),
    SocketEventName("purchase-order:approval-level:update"),
    // Named for the supplier but it is the *order* that changed: sending one
    // moves it out of the draft list (`listofAccountandemail.jsx:60`).
    SocketEventName("purchase-order:supplier:sent"),
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
 * `CreatePurchaseOrder.jsx:381`). `purchase-order:supplier:added`
 * (`supplier_created`) is bridged on the web but no page listens to it, and
 * iOS registers it as an explicit no-op — so it stays unsubscribed here, and
 * the 2026-09-07 realtime audit's flagging of it is answered rather than
 * followed.
 */
val PO_VENDOR_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("vendor:created"),
    SocketEventName("vendor:updated"),
    SocketEventName("vendor:verified"),
    SocketEventName("vendor:unverified"),
    SocketEventName("vendor:deleted"),
    SocketEventName("purchase-order:supplier:update"),
)

/**
 * The accountant changed what this form is.
 *
 * The frame names a module, so it is filtered on that: a change to the petty
 * cash form must not reload the purchase order one. The web namespaces the
 * same two into `ah:form_template:<module>`.
 */
val PO_FORM_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("form_template:changed"),
    SocketEventName("form_template:reset"),
)

/**
 * The settings document and the module's rules — `accountHubListeners.js`
 * `po_settings:updated` and the three `assignment_rule:*` frames, which the
 * web folds into one silent re-read of the Settings tab.
 */
val PO_SETTINGS_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("po_settings:updated"),
    SocketEventName("assignment_rule:created"),
    SocketEventName("assignment_rule:updated"),
    SocketEventName("assignment_rule:deleted"),
)

/**
 * The two registers: saved order shapes and the delivery address book.
 *
 * The address events used to sit with the order events, on the reasoning that
 * the delivery address prints on an order's header — true, and the register
 * reload refetches the orders too for exactly that reason. Filed here because
 * the Delivery Addresses tab is the surface that *shows* them, and a tab that
 * does not update while somebody else edits the book is the visible failure.
 *
 * Android `_poDeliveryAddress`, iOS `.updatePoAddressNotification`.
 */
val PO_REGISTER_SYNC_EVENTS: List<SocketEventName> = listOf(
    SocketEventName("purchase-order:delivery-address:create"),
    SocketEventName("purchase-order:delivery-address:update"),
    SocketEventName("purchase-order:delivery-address:delete"),
    SocketEventName("purchase-order:template:create"),
    SocketEventName("purchase-order:template:update"),
    SocketEventName("purchase-order:template:delete"),
)

val PO_SYNC_EVENTS: List<SocketEventName> =
    PO_ORDER_SYNC_EVENTS + PO_VENDOR_SYNC_EVENTS + PO_FORM_SYNC_EVENTS +
        PO_SETTINGS_SYNC_EVENTS + PO_REGISTER_SYNC_EVENTS

/**
 * Which of the tool's reads [event] invalidates, or null when the frame is not
 * about this form.
 *
 * A form-template frame names a module, and a change to Petty Cash's form must
 * not reload this one's.
 */
internal fun poRefreshFor(event: SocketEventName, module: String?): PoRefresh? = when {
    event in PO_VENDOR_SYNC_EVENTS -> PoRefresh.Vendors
    event in PO_REGISTER_SYNC_EVENTS -> PoRefresh.Register
    // A rule frame names its module; an invoices rule is not this tool's.
    event in PO_SETTINGS_SYNC_EVENTS -> PoRefresh.Settings.takeIf { module == null || module == PO_FORM_MODULE }
    event in PO_FORM_SYNC_EVENTS ->
        PoRefresh.FormTemplate.takeIf { module == null || module == PO_FORM_MODULE }

    else -> PoRefresh.Orders
}

/** This form's module id, as the template service names it. */
const val PO_FORM_MODULE = "purchase_orders"

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
    /** Only a form-template frame carries one. */
    @SerialName("module") val module: String? = null,
    @SerialName("data") val data: PoSyncData? = null,
) {
    /** The module named, wherever the frame puts it. */
    val formModule: String? get() = module ?: data?.module

    /** A frame that names another production is not ours; unnamed ones pass. */
    fun inProject(here: String?): Boolean =
        projectId == null || here == null || projectId == here
}

/** The account-hub envelope nests the entity's own fields under `data`. */
@Serializable
internal data class PoSyncData(@SerialName("module") val module: String? = null)
