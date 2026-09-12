package com.zillit.desktop.feature.purchaseorder.ui

import com.zillit.desktop.feature.purchaseorder.domain.PoViewer

/**
 * The pages the purchase order tool offers, and who sees which.
 *
 * The web mounts **two different modules** here — `PurchaseOrdersModule` for
 * the accounts team and `DepartmentPOModule` for everyone else — with their own
 * tab strips, and the desktop shows the union of both, gated. Same labels, same
 * order, same right-hand group; one screen instead of two, because a desktop
 * window does not pay the code-splitting cost that made the web lazy-load them.
 *
 * ## Why a [segment] as well as an [slug]
 *
 * The web reuses one URL for two different tabs: `/purchase-orders/all` is
 * "All POs" in the accountant module and "Approval Queue" in the department
 * one, because the role already decided which module is mounted. The desktop
 * keeps those addresses so a hub hand-off or a deep link written against the
 * web still lands right — but it needs distinct ids for a single tab strip, so
 * the two are separate fields and [forRoute] resolves by role.
 */
enum class PoDestination(
    val slug: String,
    val label: String,
    /** The web's own path segment; equal to the slug unless the two roles collide on it. */
    val segment: String = slug,
    /** Which of the web's two modules this tab belongs to. */
    val audience: PoAudience = PoAudience.Both,
) {
    // -- the accounts console: PurchaseOrdersModule's TABS --------------------
    AllPos("all-pos-console", "All POs", segment = "all", audience = PoAudience.Accounts),
    Queue("queue", "Queue", audience = PoAudience.Accounts),
    Entry("entry", "PO Entry", segment = "po-entry", audience = PoAudience.Accounts),
    Posted("posted", "Posted", audience = PoAudience.Accounts),
    Reports("reports", "Reports", audience = PoAudience.Accounts),
    Settings("settings", "Settings", audience = PoAudience.Accounts),

    // -- the department view: DepartmentPOModule's TABS -----------------------
    DepartmentAllPos("all-pos", "All POs", audience = PoAudience.Department),
    ApprovalQueue("approval", "Approval Queue", segment = "all", audience = PoAudience.Department),
    MyPos("my", "My POs", audience = PoAudience.Department),
    DepartmentPos("department", "My Department POs", audience = PoAudience.Department),
    Vendors("vendors", "Vendors", audience = PoAudience.Department),
    Invoices("invoices", "Invoices", audience = PoAudience.Department),

    // -- the right-hand group, shared by both: RIGHT_TABS ---------------------
    Templates("templates", "Templates"),
    Drafts("drafts", "PO Drafts"),
    DeliveryAddresses("delivery-addresses", "Delivery Addresses"),

    /** Raising or editing one — a full-page form, not a tab. */
    Form("new", "Create PO"),
    ;

    /**
     * Whether [viewer] may open this page.
     *
     * Three gates, each the web's:
     *
     * - **Posted** and **Settings** need the senior designation. The web hides
     *   both from an accounts assistant and says why in a banner
     *   ("Assistant View"), which this tool renders too.
     * - The department view's **All POs** exposes every order on the
     *   production — amounts, vendors, other departments' spend — so it wants
     *   `is_admin` **or** the tool's own posting right. The web's hook fails
     *   *closed* while the rights payload is in flight for exactly this reason.
     * - Everything else follows the audience: an accounts user never sees
     *   "My Department POs", and a department user never sees "PO Entry".
     */
    fun visibleTo(viewer: PoViewer): Boolean = when {
        audience == PoAudience.Accounts && !viewer.isAccountant -> false
        audience == PoAudience.Department && viewer.isAccountant -> false
        this == Posted || this == Settings -> viewer.isSeniorAccountant
        this == DepartmentAllPos -> viewer.hasFullAccess || viewer.canPostPurchaseOrders
        // Not a tab: reached by the Create PO / Enter PO button and by route.
        this == Form -> true
        else -> true
    }

    /** Whether this page is one of the right-hand group, drawn after the action button. */
    val isRegisterTab: Boolean get() = this == Templates || this == Drafts || this == DeliveryAddresses

    /** Whether orders raised offline, not yet on the server, belong on this page. */
    val showsLocalOrders: Boolean
        get() = this == MyPos || this == DepartmentAllPos || this == AllPos || this == Drafts

    companion object {
        /**
         * The page a tool route names, or null for the bare tool path — which
         * leaves the choice to the viewer's role, as the web's bare
         * `/purchase-orders` does.
         *
         * Resolved against [viewer] because two of the web's segments mean
         * different tabs in its two modules; an unknown segment answers null
         * rather than a guess, and the caller keeps the role's own landing.
         */
        fun forRoute(path: String, viewer: PoViewer? = null): PoDestination? {
            val segment = path.removePrefix(PURCHASE_ORDER_PATH).trim('/').substringBefore('/')
            if (segment.isEmpty()) return null
            val matches = entries.filter { it.segment == segment }
            if (matches.isEmpty()) return null
            if (viewer == null) return matches.first()
            return matches.firstOrNull { it.visibleTo(viewer) } ?: matches.first()
        }

        /**
         * Where a viewer lands when nothing was asked for.
         *
         * The web lands an accountant on their own queue (`/queue/my`) and
         * everyone else on their orders — `poEntryPath.js`. Both are the page
         * with work on it rather than a summary, which is the point.
         */
        fun landingFor(viewer: PoViewer): PoDestination = if (viewer.isAccountant) Queue else MyPos
    }
}

/** Which of the web's two purchase-order modules a tab came from. */
enum class PoAudience { Accounts, Department, Both }

/**
 * The two halves of the Queue tab — the web's `/queue/my` and `/queue/all`.
 *
 * "My Queue" is what is routed to this accountant; "All Queue" is every order
 * in flight on the production. The sub-tab is part of the address on the web,
 * so it is part of the route here too.
 */
enum class PoQueueScope(val slug: String, val label: String) {
    Mine("my", "My Queue"),
    All("all", "All Queue"),
}
