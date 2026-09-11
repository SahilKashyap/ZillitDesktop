package com.zillit.desktop.feature.bankrec.domain

/** Who a reconciliation summary is being shared with. */
enum class PortalOrgType(val wire: String, val label: String) {
    CompletionGuarantor("completion_guarantor", "Completion guarantor"),
    Broadcaster("broadcaster", "Broadcaster"),
    CoProducer("co_producer", "Co-producer"),
    ExternalAuditor("external_auditor", "External auditor"),
    Investor("investor", "Investor"),
    Other("other", "Other"),
    ;

    companion object {
        fun from(wire: String?): PortalOrgType =
            entries.firstOrNull { it.wire == wire?.lowercase() } ?: CompletionGuarantor
    }
}

/**
 * What a shared link lets its recipient see.
 *
 * [defaultOn] mirrors the web's own defaults. The two that are off by default
 * are the two that name people: fraud alerts and individual transactions.
 */
enum class PortalPermission(val wire: String, val label: String, val defaultOn: Boolean) {
    Balances("balances", "Opening and closing balances", true),
    ReconciliationStatus("reconciliation_status", "Reconciliation status and match rate", true),
    Exceptions("exceptions", "Exceptions list", true),
    FxVariance("fx_variance", "FX variance summary", true),
    FraudAlerts("fraud_alerts", "Fraud alerts, names redacted", false),
    TransactionDetail("transaction_detail", "Individual transaction detail", false),
    ;

    companion object {
        fun from(wire: String?): PortalPermission? =
            entries.firstOrNull { it.wire == wire?.lowercase() }

        val defaults: Set<PortalPermission> get() = entries.filter { it.defaultOn }.toSet()
    }
}

/** How long a link stays usable. */
enum class PortalExpiry(val wire: String, val label: String) {
    SevenDays("7d", "7 days"),
    FourteenDays("14d", "14 days"),
    ThirtyDays("30d", "30 days"),
    Never("none", "No expiry"),
    ;

    companion object {
        fun from(wire: String?): PortalExpiry =
            entries.firstOrNull { it.wire == wire?.lowercase() } ?: SevenDays
    }
}

/** Whether the accountant hears about a view. */
enum class PortalNotify(val wire: String, val label: String) {
    FirstView("first_view", "Email me on first view"),
    EveryView("every_view", "Email me on every view"),
    None("none", "No notifications"),
    ;

    companion object {
        fun from(wire: String?): PortalNotify =
            entries.firstOrNull { it.wire == wire?.lowercase() } ?: None
    }
}

/** Whether a link still works. */
enum class PortalStatus(val wire: String, val label: String) {
    Active("active", "Active"),
    Revoked("revoked", "Revoked"),
    Expired("expired", "Expired"),
    ;

    companion object {
        fun from(wire: String?): PortalStatus =
            entries.firstOrNull { it.wire == wire?.lowercase() } ?: Active
    }
}

/**
 * A read-only link to one period's reconciliation, for someone outside Zillit.
 *
 * The [token] is in the URL but is **not** the credential: the recipient
 * proves who they are with a one-time code emailed to the address on the link,
 * and only then does the portal open. Sharing the URL alone gives nothing away
 * to somebody who cannot read that mailbox.
 */
data class PortalLink(
    val id: String = "",
    val token: String = "",
    val recipientName: String = "",
    val recipientEmail: String = "",
    val orgType: PortalOrgType = PortalOrgType.CompletionGuarantor,
    val bankAccountId: String = "",
    val periodId: String = "",
    val permissions: Set<PortalPermission> = PortalPermission.defaults,
    val notifyOnView: PortalNotify = PortalNotify.None,
    val status: PortalStatus = PortalStatus.Active,
    val expiresAtMillis: Long? = null,
    val createdAtMillis: Long? = null,
    val lastViewedAtMillis: Long? = null,
    val viewCount: Int = 0,
) {
    val isActive: Boolean get() = status == PortalStatus.Active
}

/** A link being created or re-shared. */
data class PortalLinkDraft(
    val editingId: String = "",
    val recipientName: String = "",
    val recipientEmail: String = "",
    val orgType: PortalOrgType = PortalOrgType.CompletionGuarantor,
    val bankAccountId: String = "",
    val periodId: String = "",
    val permissions: Set<PortalPermission> = PortalPermission.defaults,
    val expiry: PortalExpiry = PortalExpiry.SevenDays,
    val notifyOnView: PortalNotify = PortalNotify.None,
    val saving: Boolean = false,
) {
    val isEdit: Boolean get() = editingId.isNotBlank()

    /**
     * What the form refuses to send, or null when it is ready.
     *
     * A link with no permissions is a link that shows nothing, which the web
     * also refuses — it reads as a broken page to the recipient rather than as
     * a deliberate empty share.
     */
    val problem: String?
        get() = when {
            recipientName.isBlank() -> "Give the recipient's name."
            !recipientEmail.contains('@') -> "Give the recipient's email address."
            periodId.isBlank() -> "Choose the period to share."
            permissions.isEmpty() -> "Choose at least one thing the recipient may see."
            else -> null
        }
}
