package com.zillit.desktop.feature.bankrec.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/** Who a reconciliation summary is being shared with. */
enum class PortalOrgType(val wire: String, private val labelKey: String) {
    CompletionGuarantor("completion_guarantor", S.desktop_br_org_completion_guarantor),
    Broadcaster("broadcaster", S.desktop_br_org_broadcaster),
    CoProducer("co_producer", S.desktop_br_org_co_producer),
    ExternalAuditor("external_auditor", S.desktop_br_org_external_auditor),
    Investor("investor", S.desktop_br_org_investor),
    Other("other", S.other),
    ;

    val label: String get() = str(labelKey)

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
 * [shortLabel] is the chip the links table wears.
 */
enum class PortalPermission(
    val wire: String,
    private val labelKey: String,
    private val shortLabelKey: String,
    val defaultOn: Boolean,
) {
    Balances("balances", S.desktop_br_perm_balances, S.desktop_balances, true),
    ReconciliationStatus(
        "reconciliation_status",
        S.desktop_br_perm_rec_status,
        S.desktop_br_perm_rec_status_short,
        true,
    ),
    Exceptions("exceptions", S.desktop_br_perm_exceptions, S.desktop_exceptions, true),
    FxVariance("fx_variance", S.desktop_br_perm_fx_variance, S.desktop_fx_variance, true),
    FraudAlerts(
        "fraud_alerts",
        S.desktop_br_perm_fraud_alerts,
        S.desktop_br_perm_fraud_alerts_short,
        false,
    ),
    TransactionDetail("transaction_detail", S.desktop_br_perm_transaction_detail, S.desktop_transactions, false),
    ;

    val label: String get() = str(labelKey)

    val shortLabel: String get() = str(shortLabelKey)

    companion object {
        fun from(wire: String?): PortalPermission? =
            entries.firstOrNull { it.wire == wire?.lowercase() }

        val defaults: Set<PortalPermission> get() = entries.filter { it.defaultOn }.toSet()
    }
}

/** How long a link stays usable. */
enum class PortalExpiry(val wire: String, private val labelKey: String) {
    SevenDays("7d", S.drive_expiry_7d),
    FourteenDays("14d", S.desktop_14_days),
    ThirtyDays("30d", S.drive_expiry_30d),
    Never("none", S.desktop_no_expiry),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun from(wire: String?): PortalExpiry =
            entries.firstOrNull { it.wire == wire?.lowercase() } ?: SevenDays
    }
}

/** Whether the accountant hears about a view. */
enum class PortalNotify(val wire: String, private val labelKey: String) {
    FirstView("first_view", S.desktop_br_notify_first_view),
    EveryView("every_view", S.desktop_br_notify_every_view),
    None("none", S.no_notifications),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun from(wire: String?): PortalNotify =
            entries.firstOrNull { it.wire == wire?.lowercase() } ?: None
    }
}

/** Whether a link still works. */
enum class PortalStatus(val wire: String, private val labelKey: String) {
    Active("active", S.active),
    Revoked("revoked", S.desktop_drive_revoked),
    Expired("expired", S.expired),
    ;

    val label: String get() = str(labelKey)

    companion object {
        /** Anything not active or revoked is expired, as the web reads it. */
        fun from(wire: String?): PortalStatus = when (wire?.lowercase()) {
            "active", null, "" -> Active
            "revoked" -> Revoked
            else -> Expired
        }
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
    val orgTypeWire: String = "",
    val bankAccountId: String = "",
    val periodId: String = "",
    /** The server's own label for the period, which the links table prints. */
    val periodLabel: String = "",
    val permissions: List<PortalPermission> = PortalPermission.defaults.toList(),
    val notifyOnView: PortalNotify = PortalNotify.None,
    val status: PortalStatus = PortalStatus.Active,
    val expiresAtMillis: Long? = null,
    val createdAtMillis: Long? = null,
    val lastViewedAtMillis: Long? = null,
    val views: Int = 0,
) {
    val isActive: Boolean get() = status == PortalStatus.Active
}

/** A link being created, edited or re-shared. */
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
     * a deliberate empty share. [periodIds] are the periods that exist: a link
     * to a period deleted while the dialog was open would 404 for the
     * recipient, so it is refused here as the web refuses it.
     */
    fun problem(periodIds: Collection<String>): String? = when {
        recipientName.isBlank() -> str(S.desktop_br_portal_need_recipient_name)
        recipientEmail.isBlank() || !recipientEmail.contains('@') ->
            str(S.desktop_br_portal_need_recipient_email)
        periodId.isBlank() || periodId !in periodIds -> str(S.desktop_br_portal_need_period)
        permissions.isEmpty() -> str(S.desktop_br_portal_need_permission)
        else -> null
    }
}

/**
 * The read-only summary a link shows, as the accountant previews it.
 *
 * ## An absent permission list is not an empty one
 *
 * [permissions] is null on the accountant's own preview — that route is scoped
 * to a period, takes no link, and so says nothing about what anybody may see —
 * and the preview then shows everything. A link-scoped answer always carries
 * the key, and there an empty list means *nothing*. Treating the two alike
 * either hides the whole preview from the accountant or opens fraud alerts and
 * transaction detail to a recipient who was never granted them.
 */
data class PortalPreview(
    val period: BankPeriod,
    val bankAccountName: String = "",
    val bankAccountHolder: String = "",
    val bankAccountCurrency: String = "",
    val projectName: String = "",
    val exceptions: List<PreviewException> = emptyList(),
    val fraudAlerts: List<PreviewFraudAlert> = emptyList(),
    val fxVariances: List<PreviewFx> = emptyList(),
    val transactions: List<BankTransaction> = emptyList(),
    val ledgerEntries: List<LedgerEntry> = emptyList(),
    val permissions: Set<PortalPermission>? = null,
) {
    fun allows(permission: PortalPermission): Boolean = permissions == null || permission in permissions

    /**
     * The foreign payments folded per currency, as the summary tabulates them.
     *
     * Totals add; the budget rate is the first row's and the bank rate the
     * last's, which is what the web shows for a currency paid more than once.
     */
    val fxByCurrency: List<PreviewFxGroup>
        get() = fxVariances.groupBy { it.currency.ifBlank { "FX" } }.map { (code, rows) ->
            PreviewFxGroup(
                currency = code,
                foreignTotal = rows.sumOf { it.foreignAmount },
                paidTotal = rows.sumOf { it.paidAmount },
                varianceTotal = rows.sumOf { it.variance },
                budgetRate = rows.first().budgetRate,
                bankRate = rows.last().bankRate,
            )
        }
}

/** An exception, as the summary lists it. */
data class PreviewException(
    val title: String = "",
    val status: ExceptionStatus = ExceptionStatus.Open,
    val debit: Double = 0.0,
    val credit: Double = 0.0,
    val currency: String? = null,
) {
    /** Money out negative. */
    val amount: Double get() = if (debit > 0) -debit else credit
}

/** A fraud alert, as the summary lists it. */
data class PreviewFraudAlert(
    val title: String = "",
    val status: FraudStatus = FraudStatus.Active,
    val description: String = "",
)

/** A foreign payment, as the summary lists it. */
data class PreviewFx(
    val currency: String = "",
    val foreignAmount: Double = 0.0,
    val paidAmount: Double = 0.0,
    val variance: Double = 0.0,
    val budgetRate: Double = 0.0,
    val bankRate: Double = 0.0,
)

/** One currency's foreign payments, totalled. */
data class PreviewFxGroup(
    val currency: String,
    val foreignTotal: Double,
    val paidTotal: Double,
    val varianceTotal: Double,
    val budgetRate: Double,
    val bankRate: Double,
)
