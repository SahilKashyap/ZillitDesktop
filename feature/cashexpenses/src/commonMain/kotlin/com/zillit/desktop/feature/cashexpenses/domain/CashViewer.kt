package com.zillit.desktop.feature.cashexpenses.domain

/**
 * Lowercased words, from either an identifier or a display name.
 *
 * `designation_financial_controller_accounts` and "Financial Controller" both
 * become strings containing "financial controller", which is what makes one
 * comparison serve both wire shapes.
 */
internal fun String?.normalised(): String =
    orEmpty().lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").trim()

/**
 * Who is looking at the cash module, and what that entitles them to.
 *
 * Two independent inputs decide it: the person's **department and designation**
 * (from the production profile — an accountant is an accountant on every
 * production they are on) and the **cash team metadata** (`GET /metadata` — a
 * per-production grant made in this module's own Settings). The web reads both
 * from two different places and re-derives the combination on a dozen screens;
 * here it is derived once.
 */
data class CashViewer(
    val userId: String,
    val departmentIdentifier: String?,
    val designationIdentifier: String?,
    val metadata: CashMetadata = CashMetadata(),
    /**
     * True when the module was opened from the Film Tools grid rather than from
     * Account Hub.
     *
     * An accountant arriving that way gets the **crew** view, because they are
     * there to submit their own receipts, not to process everyone else's. The
     * web calls this `enteredAsTool` and it is the only reason an accountant
     * ever sees the crew screens.
     */
    val enteredAsTool: Boolean = false,
) {

    /**
     * Whether this person processes other people's cash.
     *
     * Matched on the department containing `accounts`, as the web does
     * (`department_identifier?.includes('accounts')`), rather than on an exact
     * identifier: productions name the department differently
     * (`department_accounts`, `department_accounts_uk`) and an equality check
     * has already silently demoted whole accounts teams to the crew view.
     */
    val isAccountant: Boolean
        get() = !enteredAsTool && departmentIdentifier?.contains(ACCOUNTS, ignoreCase = true) == true

    /**
     * Production Accountant and Financial Controller, who are senior by role.
     *
     * Independent of the team list: a Financial Controller who was never added
     * to Settings → Team is still senior, and treating them otherwise strips
     * their sign-off and settings access on a production where nobody thought
     * to list them.
     *
     * Matched on the *normalised* value rather than the exact identifier,
     * because `project/users` hands this client either the identifier
     * (`designation_financial_controller_accounts`) or the already-translated
     * name ("Financial Controller") depending on the endpoint. An equality
     * check against the identifier alone silently demotes every senior on a
     * production served the translated form.
     */
    val isSeniorAccountant: Boolean
        get() = designationIdentifier.normalised().let { value ->
            value.isNotEmpty() && SENIOR_DESIGNATIONS.any { value.contains(it) }
        }

    /** Senior either by the team flag or by designation. Both halves matter. */
    val isSenior: Boolean get() = metadata.isSenior || isSeniorAccountant

    val isApprover: Boolean get() = metadata.isApprover

    val isCoordinator: Boolean get() = metadata.isCoordinator

    /**
     * Whether the Sign-off tab is offered.
     *
     * Requires all three: the production asks for senior sign-off at all, the
     * viewer processes cash, and the viewer is senior. The accountant clause is
     * not redundant — it excludes a non-accountant who carries a senior flag
     * from a queue of other people's batches.
     */
    val canSeeSignOff: Boolean
        get() = metadata.requireSeniorSignOff && isAccountant && isSenior

    /** Settings is a senior accountant's screen — it rewrites everyone's rights. */
    val canOpenSettings: Boolean get() = isAccountant && isSenior

    /** Whether an approval row offers the accountant's override action. */
    fun canOverrideFloat(): Boolean =
        isAccountant && metadata.canOverride && metadata.overrideFloatRequest

    fun canOverrideBatch(): Boolean =
        isAccountant && metadata.canOverride && metadata.overrideReceiptBatch

    /**
     * Whether [amount] is within this person's posting ceiling.
     *
     * A null limit is no limit — that is what the server means by omitting it,
     * and treating it as zero would lock out every accountant on a production
     * that never configured one.
     */
    fun canPost(amount: Double): Boolean {
        val limit = metadata.postingLimit ?: return true
        return amount <= limit
    }

    private companion object {
        const val ACCOUNTS = "accounts"

        /** Normalised fragments — see [isSeniorAccountant]. */
        val SENIOR_DESIGNATIONS = setOf(
            "production accountant",
            "financial controller",
        )
    }
}
