package com.zillit.desktop.feature.talentdeals.domain

/**
 * What this person may do in Talent Deals — the web's `useTalentDealsRights`
 * (`hooks/useDealMemoRights.js`): the Deal Memo rule, read from the
 * `talent_deals_tool` row of the production's tool grid.
 *
 *  - `view_access` reads the register and a contract, and is the only
 *    requirement for entry; posting implies it.
 *  - `posting_access` adds Add Contract, Edit and the remove chips.
 *  - `download_access` shows the file viewer's Download.
 *  - An accounts-department user always posts and downloads; a member whose
 *    invitation is still PENDING never posts, whatever their department says.
 *    Only an explicit `pending` restricts: the member list lands after the
 *    tool opens, and treating "not known yet" as pending would flash a
 *    read-only register at every accountant on every load.
 *
 * Hiding Download is not a security boundary — the server must enforce it.
 */
data class TalentDealsViewer(
    val userId: String = "",
    val departmentIdentifier: String? = null,
    val hasPostingAccess: Boolean = false,
    val hasViewAccess: Boolean = false,
    val hasDownloadAccess: Boolean = false,
    /** The member's standing from the project list: `accepted`, `pending`, … */
    val memberStatus: String? = null,
    /** The tool grid has answered at least once — before that, nothing is refused. */
    val rightsLoaded: Boolean = false,
) {
    val isAccountant: Boolean get() = departmentIdentifier.orEmpty().contains(ACCOUNTS, ignoreCase = true)

    val isPending: Boolean get() = memberStatus?.trim().equals(PENDING, ignoreCase = true)

    val canPost: Boolean get() = !isPending && (hasPostingAccess || isAccountant)

    val canView: Boolean get() = hasPostingAccess || hasViewAccess || isAccountant

    val canDownload: Boolean get() = hasDownloadAccess || isAccountant

    private companion object {
        const val ACCOUNTS = "accounts"
        const val PENDING = "pending"
    }
}
