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
) {
    /** The accounts department, matched loosely because productions name it differently. */
    val isAccountant: Boolean get() = departmentIdentifier.contains(ACCOUNTS, ignoreCase = true)

    val hasSeniorDesignation: Boolean
        get() = designationIdentifier.lowercase().let { d -> SENIOR_DESIGNATIONS.any { d.contains(it) } }

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

    fun withSettings(settings: InvoiceSettings): InvoiceViewer = copy(
        seniorFlag = settings.seniorFor(userId),
        overrideFlag = settings.overrideFor(userId),
        isRunApprover = userId in settings.runApprovers,
    )

    companion object {
        const val TOOL_IDENTIFIER = "invoices_tool"
        private const val ACCOUNTS = "accounts"
        private val SENIOR_DESIGNATIONS = setOf("production_accountant", "financial_controller")

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
