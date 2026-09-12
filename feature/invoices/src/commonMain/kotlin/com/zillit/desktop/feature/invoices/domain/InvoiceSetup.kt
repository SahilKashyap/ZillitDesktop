package com.zillit.desktop.feature.invoices.domain

import com.zillit.desktop.core.common.Money

/**
 * The module's own settings document — the web's `SettingsPage.jsx` over
 * `GET/PATCH /api/v2/invoices/settings`.
 *
 * Four sections, each saving on its own: who may post and up to what value,
 * which alerts go out, the sign-off chain a payment run climbs, and the rules
 * that put an arriving invoice on somebody's desk. The rules are not part of
 * this document — they are the hub's, filed under module `invoices` — but the
 * GET answers them alongside, so they load together.
 */
data class InvoiceSetup(
    val teamMembers: List<InvoiceTeamRow> = emptyList(),
    /** The alert keys switched on; anything the desktop does not know round-trips in [unknownAlerts]. */
    val alerts: Set<String> = emptySet(),
    val runAuthorisation: List<RunAuthLevel> = emptyList(),
) {
    /** Alert keys the server holds that this build has no row for — kept so a save never drops them. */
    val unknownAlerts: Set<String> get() = alerts - InvoiceAlert.entries.map { it.wire }.toSet()

    fun isOn(alert: InvoiceAlert): Boolean = alert.wire in alerts

    fun toggle(alert: InvoiceAlert): InvoiceSetup =
        copy(alerts = if (isOn(alert)) alerts - alert.wire else alerts + alert.wire)

    /** A new level at [index], with every level renumbered from one — the web's `insertRunAuthLevelAt`. */
    fun insertingLevel(index: Int): InvoiceSetup {
        val next = runAuthorisation.toMutableList().apply { add(index.coerceIn(0, size), RunAuthLevel()) }
        return copy(runAuthorisation = next.renumbered())
    }

    fun removingLevel(tier: Int): InvoiceSetup =
        copy(runAuthorisation = runAuthorisation.filterNot { it.tier == tier }.renumbered())

    fun addingToLevel(tier: Int, userIds: List<String>): InvoiceSetup = copy(
        runAuthorisation = runAuthorisation.map {
            if (it.tier == tier) it.copy(userIds = (it.userIds + userIds).distinct()) else it
        },
    )

    fun removingFromLevel(tier: Int, userId: String): InvoiceSetup = copy(
        runAuthorisation = runAuthorisation.map {
            if (it.tier == tier) it.copy(userIds = it.userIds - userId) else it
        },
    )

    /** Everyone already on the chain — nobody is offered to a second level. */
    val allApprovers: List<String> get() = runAuthorisation.flatMap { it.userIds }

    private fun List<RunAuthLevel>.renumbered(): List<RunAuthLevel> =
        mapIndexed { index, level -> level.copy(tier = index + 1) }
}

/** One row of `team_members`: who may post, up to what, and what else they may do. */
data class InvoiceTeamRow(
    val userId: String,
    /** Null is unlimited; zero is "submit only" — the web tells those two apart. */
    val postingLimit: Double? = null,
    val runAccess: Boolean = false,
    val overrideAccess: Boolean = false,
    val isSenior: Boolean = false,
) {
    val isUnlimited: Boolean get() = isSenior || postingLimit == null

    val isSubmitOnly: Boolean get() = !isUnlimited && postingLimit == 0.0

    /** "Unlimited", "Submit Only" or the figure — the web's posting-limit cell. */
    fun limitLabel(currency: String): String = when {
        isUnlimited -> "Unlimited"
        isSubmitOnly -> "Submit Only"
        else -> Money.format(postingLimit ?: 0.0, currency)
    }

    /**
     * Senior is full rights — the web's `setSenior(true)` forces unlimited
     * posting, run authorisation and override, and locks those three.
     */
    fun asSenior(senior: Boolean): InvoiceTeamRow = if (senior) {
        copy(isSenior = true, postingLimit = null, runAccess = true, overrideAccess = true)
    } else {
        copy(isSenior = false)
    }
}

/** One level of the payment-run sign-off chain. */
data class RunAuthLevel(val tier: Int = 1, val userIds: List<String> = emptyList())

/** The six alerts the web offers, in its order, with its wording. */
enum class InvoiceAlert(val wire: String, val label: String, val hint: String) {
    InvoiceOverdue(
        "invoice_overdue",
        "Invoice overdue notifications",
        "Get notified when an invoice passes its due date without being paid.",
    ),
    ApprovalSlaBreach(
        "approval_sla_breach",
        "Approval SLA breach warnings",
        "Alert when an invoice sits in the approval queue beyond the SLA window.",
    ),
    DuplicateDetection(
        "duplicate_detection",
        "Duplicate invoice detection",
        "Flag invoices that appear to be duplicates based on vendor and amount.",
    ),
    OverPoFlagging(
        "over_po_flagging",
        "Over-PO flagging alerts",
        "Warn when an invoice amount exceeds the linked purchase order value.",
    ),
    NoPoOverride(
        "no_po_override",
        "No-PO override notifications",
        "Notify when an invoice is approved without a linked purchase order.",
    ),
    DailyApSummary(
        "daily_ap_summary",
        "Daily AP summary email",
        "Receive a morning summary of pending invoices and payment run status.",
    ),
}

/**
 * One auto-assignment rule — whose desk an invoice lands on.
 *
 * `/api/v2/account-hub/assignment-rules`, module `invoices`. The conditions
 * OR: any department, vendor or nominal code in the lists, or an amount at or
 * over [amountMin], sends the invoice to [assignTo].
 */
data class InvoiceAssignmentRule(
    val id: String,
    val departments: List<String> = emptyList(),
    val vendors: List<String> = emptyList(),
    val nominalCodes: List<String> = emptyList(),
    /** As typed; blank is no minimum. */
    val amountMin: String = "",
    val assignTo: String = "",
    val isActive: Boolean = true,
    val priority: Int = 0,
    /** Whether the server holds this row; a new one carries a local id until it is saved. */
    val persisted: Boolean = false,
) {
    val amountMinValue: Double? get() = amountMin.trim().replace(",", "").toDoubleOrNull()

    /** "2 depts | 1 vendor | ≥ £500", or what the web says when nothing is set. */
    fun summary(currency: String): String {
        val parts = mutableListOf<String>()
        if (departments.isNotEmpty()) parts += "${departments.size} dept${if (departments.size > 1) "s" else ""}"
        if (vendors.isNotEmpty()) parts += "${vendors.size} vendor${if (vendors.size > 1) "s" else ""}"
        if (nominalCodes.isNotEmpty()) parts += "${nominalCodes.size} nominal"
        amountMinValue?.let { parts += "≥ ${Money.format(it, currency)}" }
        return if (parts.isEmpty()) "No condition set" else parts.joinToString(" | ")
    }

    companion object {
        /** A rule not yet saved carries one of these; the server mints the real id on create. */
        const val LOCAL_ID_PREFIX = "temp-"
    }
}

/** The GET answers the document and the module's rules together. */
data class InvoiceSetupBundle(
    val setup: InvoiceSetup = InvoiceSetup(),
    val rules: List<InvoiceAssignmentRule> = emptyList(),
)

/** A postable line of the chart, offered to a rule by its code. */
data class InvoiceNominal(val code: String, val name: String = "") {
    /** "2400 — Camera — Equipment Hire", the web's `coaLabel`. */
    val label: String get() = if (name.isBlank()) code else "$code — $name"
}
