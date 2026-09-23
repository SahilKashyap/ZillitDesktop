package com.zillit.desktop.feature.invoices.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * Who is looking. Department and designation come from the crew list; the
 * tool rights from the production's permission grid (`invoices_tool`); the
 * senior/override flags from the module's own `/settings`, folded in later
 * with [withSettings].
 */
data class InvoiceViewer(
    val userId: String = "",
    /** The department `_id` — what `?department_id=` and uploads carry. */
    val departmentId: String = "",
    /** The department identifier, e.g. `accounts` — what decides the accountant view. */
    val departmentIdentifier: String = "",
    val designationIdentifier: String = "",
    val isTelevision: Boolean = false,
    val canView: Boolean = true,
    val canPost: Boolean = true,
    val canDownload: Boolean = true,
    val isAdmin: Boolean = false,
    /** False until the tools call has answered; unresolved rights are permissive. */
    val ready: Boolean = false,
    /** From `/settings`; null until loaded. */
    val seniorFlag: Boolean? = null,
    val overrideFlag: Boolean? = null,
    val isRunApprover: Boolean = false,
    /** Settings → Team's "Can authorise payment runs" for this person; false until read. */
    val runAccessFlag: Boolean = false,
    /** Settings → Team gives this person a posting limit; false until read. */
    val postingRightFlag: Boolean = false,
) {
    /** The accounts department, matched loosely because productions name it differently. */
    val isAccountant: Boolean get() = departmentIdentifier.contains(ACCOUNTS, ignoreCase = true)

    /**
     * Production Accountant or Financial Controller — senior by role.
     *
     * Two exact designations, as the web's `isSeniorAccountant`
     * (`utils/po-permissions.js`) — never a substring: an *Assistant*
     * Production Accountant (`designation_assistant_production_accountant_accounts`)
     * contains the words "production accountant" and is not senior. The
     * already-translated names ("Production Accountant") are accepted too,
     * exactly, because some endpoints hand this client the name rather than
     * the identifier.
     */
    val hasSeniorDesignation: Boolean
        get() = isSeniorDesignation(designationIdentifier)

    val isSenior: Boolean get() = seniorFlag == true || hasSeniorDesignation

    val canOverride: Boolean get() = overrideFlag == true || isSenior

    /**
     * Rights as issued, with no project-admin bypass.
     *
     * The web's invoice entry page reads `ADMIN_DESIGNATIONS` — which is the
     * same two senior designations, Production Accountant and Financial
     * Controller — not the project-owner flag. Owning the production is not
     * the same as running its ledger, and the phones list every tool from the
     * user's own `view_access` without consulting `isAdmin` either.
     */
    val isBlocked: Boolean get() = ready && !canView

    /** `if (isAdmin) setCanPost(true)` on the web, where that admin is PA/FC. */
    val mayPost: Boolean get() = canPost || hasSeniorDesignation

    /**
     * May create, pay and cancel payment runs — the web's
     * `canAuthorisePaymentRuns`: a senior by designation always, anyone else
     * only when Settings → Team gives them run access. Signing a run's tiers
     * is a separate question, answered by the run's own chain
     * ([PaymentRuns.resolveApproval]).
     */
    val canOperateRuns: Boolean get() = hasSeniorDesignation || runAccessFlag

    /**
     * May post an entered invoice to the ledger — the web's `canPostToLedger`:
     * a senior (by the settings flag or by designation) always, anyone else
     * only with a posting limit in Settings → Team. Seniority never waits on
     * the settings read (ZL-20767).
     */
    val canPostToLedger: Boolean get() = isSenior || postingRightFlag

    fun withSettings(settings: InvoiceSettings): InvoiceViewer = copy(
        seniorFlag = settings.seniorFor(userId),
        overrideFlag = settings.overrideFor(userId),
        isRunApprover = userId in settings.runApprovers,
        runAccessFlag = settings.runAccessFor(userId),
        postingRightFlag = settings.postingRightFor(userId),
    )

    companion object {
        const val TOOL_IDENTIFIER = "invoices_tool"
        private const val ACCOUNTS = "accounts"
        /** The web's `PO_FULL_ACCESS_DESIGNATIONS`. */
        private val SENIOR_DESIGNATIONS = setOf(
            "designation_production_accountant_accounts",
            "designation_financial_controller_accounts",
        )

        /** The same two roles by their display names. */
        private val SENIOR_NAMES = setOf("production accountant", "financial controller")

        fun isSeniorDesignation(designation: String): Boolean {
            val value = designation.trim().lowercase()
            if (value in SENIOR_DESIGNATIONS) return true
            val words = value.map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
                .split(' ').filter { it.isNotEmpty() }.joinToString(" ")
            return words in SENIOR_NAMES
        }

        fun from(
            permissions: ProjectPermissions,
            userId: String,
            departmentId: String,
            departmentIdentifier: String,
            designationIdentifier: String,
            isTelevision: Boolean,
        ): InvoiceViewer {
            val base = InvoiceViewer(
                userId = userId,
                departmentId = departmentId,
                departmentIdentifier = departmentIdentifier,
                designationIdentifier = designationIdentifier,
                isTelevision = isTelevision,
                isAdmin = permissions.isAdmin,
            )
            val access = permissions.access(TOOL_IDENTIFIER)
            if (!access.enabled && permissions.visibleTools.isEmpty()) return base
            // Accountants reach invoices through the Account Hub, where the
            // tile may not be issued at all; the grid never blocks them.
            // An identifier the grid never mentions does not gate either: the
            // tile is only issued on some productions, and the hub entry has no
            // gate on the web. Only a row that is present and switched off blocks.
            val open = base.isAccountant || permissions.tools.none { it.identifier == TOOL_IDENTIFIER }
            return base.copy(
                canView = open || permissions.canView(TOOL_IDENTIFIER),
                canPost = open || permissions.canPost(TOOL_IDENTIFIER),
                canDownload = open || permissions.canDownload(TOOL_IDENTIFIER),
                ready = true,
            )
        }
    }
}
