package com.zillit.desktop.feature.accounthub.domain

import com.zillit.desktop.core.localization.localised

/**
 * One person on the production, as every hub picker names them.
 *
 * The web's `useAccountHubUsers` reshapes the crew list into four views —
 * accepted users, the accounts team, "available" users and everyone — and
 * this is the row all four are built from. Supplied by the host: the hub's
 * own service has no crew endpoint.
 */
data class HubUser(
    val id: String,
    val name: String = "",
    val email: String = "",
    /** The department's display name. */
    val department: String = "",
    /** The department's identifier — `department_accounts` marks the accounts team. */
    val departmentIdentifier: String = "",
    val departmentId: String = "",
    val designation: String = "",
    val isAdmin: Boolean = false,
    /** `accepted` or `pending`; only accepted crew are offered as approvers. */
    val status: String = ACCEPTED,
    val avatarUrl: String? = null,
) {
    val isAccepted: Boolean get() = status.equals(ACCEPTED, ignoreCase = true)

    val isAccountsTeam: Boolean
        get() = departmentIdentifier.equals(ACCOUNTS_DEPARTMENT, ignoreCase = true) ||
            department.contains("account", ignoreCase = true)

    /** "Name (Role)" — what the assign-to pickers print. */
    val roleLabel: String get() = designation.ifBlank { department }

    /** Two initials, for the avatar. */
    val initials: String
        get() = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            .take(2).joinToString("") { it.first().uppercaseChar().toString() }
            .ifBlank { "?" }

    companion object {
        const val ACCEPTED = "accepted"
        const val ACCOUNTS_DEPARTMENT = "department_accounts"
    }
}

/** The web's four derived lists over one crew roster. */
object HubUsers {

    /** Accepted crew only. */
    fun accepted(users: List<HubUser>): List<HubUser> = users.filter { it.isAccepted }

    /** The accounts department — who payroll and invoices route to. */
    fun accountsTeam(users: List<HubUser>): List<HubUser> = accepted(users).filter { it.isAccountsTeam }

    /** Everyone accepted, for the general pickers. */
    fun available(users: List<HubUser>): List<HubUser> = accepted(users)

    fun byId(users: List<HubUser>): Map<String, HubUser> = users.associateBy { it.id }

    /** A name for an id, or the id itself when the roster does not know it. */
    fun nameOf(users: List<HubUser>, id: String): String =
        users.firstOrNull { it.id == id }?.name?.ifBlank { id } ?: id

    /** Rows whose name, email, department or role contains [term]. */
    fun search(users: List<HubUser>, term: String): List<HubUser> {
        val needle = term.trim()
        if (needle.isEmpty()) return users
        // Department and designation arrive as translation keys and are shown
        // translated, so match the words on screen as well as the raw key.
        return users.filter {
            it.name.contains(needle, true) || it.email.contains(needle, true) ||
                it.department.contains(needle, true) || it.designation.contains(needle, true) ||
                it.department.localised().contains(needle, true) ||
                it.designation.localised().contains(needle, true)
        }
    }
}

/** A department, as the pickers offer it. Designations ride along for payroll groups. */
data class HubDepartment(
    val id: String,
    val name: String = "",
    val identifier: String = "",
    val designations: List<HubDesignation> = emptyList(),
)

data class HubDesignation(val id: String, val name: String = "")

/** The counts the sidebar badges read — computed by the host from the notification ledger. */
data class HubBadgeCounts(
    /** Unread under the account hub tool, keyed by unit — `purchase_order_label`… */
    val hubUnits: Map<String, Int> = emptyMap(),
    /** Each tool's own unread, keyed by wire tool name — what a department user sees. */
    val tools: Map<String, Int> = emptyMap(),
) {
    fun hubUnit(unit: String): Int = hubUnits[unit] ?: 0

    fun tool(wire: String): Int = tools[wire] ?: 0

    companion object {
        val Empty = HubBadgeCounts()
    }
}

/**
 * Which count each sidebar row wears — the web's `getBadgeCount`.
 *
 * Accountants read the hub's own ledger by unit; department users read each
 * tool's own total. Purchase Orders shows nothing to a department user, and
 * Bank Reconciliation shows nothing to anyone but an accountant.
 */
object HubBadges {
    const val HUB_TOOL = "account_hub_label"
    const val PO_UNIT = "purchase_order_label"
    const val INVOICES_UNIT = "invoice_label"
    const val CARD_UNIT = "card_expenses_label"
    const val CASH_UNIT = "cash_expenses_label"
    const val BANK_RECON_UNIT = "bank_recon_label"
    const val INVOICES_TOOL = "invoices_label"

    fun countFor(itemId: String, isAccountant: Boolean, counts: HubBadgeCounts): Int = when (itemId) {
        "purchase-orders" -> if (isAccountant) counts.hubUnit(PO_UNIT) else 0
        "invoices" -> if (isAccountant) counts.hubUnit(INVOICES_UNIT) else counts.tool(INVOICES_TOOL)
        "card-expenses" -> if (isAccountant) counts.hubUnit(CARD_UNIT) else counts.tool(CARD_UNIT)
        "cash-expenses" -> if (isAccountant) counts.hubUnit(CASH_UNIT) else counts.tool(CASH_UNIT)
        "bank-reconciliation" -> if (isAccountant) counts.hubUnit(BANK_RECON_UNIT) else 0
        else -> 0
    }

    /** The web's antd `overflowCount`. */
    const val OVERFLOW = 99
}
