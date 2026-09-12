package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.feature.invoices.domain.InvoiceAssignee
import com.zillit.desktop.feature.invoices.domain.InvoiceAssignmentRule
import com.zillit.desktop.feature.invoices.domain.InvoiceNominal
import com.zillit.desktop.feature.invoices.domain.InvoiceSetup
import com.zillit.desktop.feature.invoices.domain.InvoiceTeamRow

/** The Settings page — the web's `SettingsPage`, with a dirty flag and a Save per card. */
data class InvoiceSetupState(
    val loading: Boolean = false,
    val loadError: ZillitError? = null,
    val saved: InvoiceSetup = InvoiceSetup(),
    val edited: InvoiceSetup = InvoiceSetup(),
    val savedRules: List<InvoiceAssignmentRule> = emptyList(),
    val rules: List<InvoiceAssignmentRule> = emptyList(),
    val rulesLoading: Boolean = false,
    /** Cards mid-save. */
    val saving: Set<InvoiceSetupSection> = emptySet(),
    /** Cards whose Save just landed; the tick fades after a moment. */
    val justSaved: Set<InvoiceSetupSection> = emptySet(),
    val nominals: List<InvoiceNominal> = emptyList(),
    /**
     * Everyone on the production, for the sign-off chain's picker.
     *
     * The web hands that picker `USERS`, not the accounts team: a run can be
     * signed off by a producer who never touches an invoice. The other three
     * pickers stay on the accounts team, as the web's do.
     */
    val people: List<InvoiceAssignee> = emptyList(),
    /** The member being added or edited, over the table. */
    val memberDraft: TeamMemberDraft? = null,
    /** The member a confirm is open over. */
    val removingMember: String? = null,
    /** The rule a confirm is open over. */
    val removingRule: InvoiceAssignmentRule? = null,
    /** The level whose user picker is open, or null. */
    val pickingForTier: Int? = null,
    val pickerSearch: String = "",
) {
    /** Whether [section] differs from what the server holds — the web's per-card dirty flags. */
    fun isDirty(section: InvoiceSetupSection): Boolean = when (section) {
        // Team persists the moment a member is added, edited or removed, so it
        // is never dirty — the web has no Save on that card either.
        InvoiceSetupSection.Team -> false
        InvoiceSetupSection.Alerts -> edited.alerts != saved.alerts
        InvoiceSetupSection.RunAuthorisation -> edited.runAuthorisation != saved.runAuthorisation
        InvoiceSetupSection.Rules -> rules != savedRules
    }
}

/** The four cards of the page; three of them save on their own. */
enum class InvoiceSetupSection { Team, Alerts, RunAuthorisation, Rules }

/**
 * The Add / Edit Team Member sheet — the web's `TeamMemberModal`.
 *
 * The limit is kept as typed so the field can hold "1,500" mid-edit, and
 * [unlimited] is a control of its own because a blank box and a zero mean
 * different things: no ceiling, and no direct posting at all.
 */
data class TeamMemberDraft(
    val row: InvoiceTeamRow,
    val limitText: String = "",
    val unlimited: Boolean = true,
    val isNew: Boolean = true,
    val busy: Boolean = false,
) {
    val isReady: Boolean get() = row.userId.isNotBlank() && !busy

    /** What is stored: null when unlimited or senior, else the figure typed. */
    fun toRow(): InvoiceTeamRow {
        val typed = limitText.trim().replace(",", "").toDoubleOrNull() ?: 0.0
        return row.copy(
            postingLimit = if (unlimited || row.isSenior) null else typed,
            runAccess = row.isSenior || row.runAccess,
            overrideAccess = row.isSenior || row.overrideAccess,
        )
    }

    companion object {
        fun of(row: InvoiceTeamRow, isNew: Boolean): TeamMemberDraft = TeamMemberDraft(
            row = row,
            limitText = row.postingLimit?.let { if (it == 0.0) "0" else it.toString().removeSuffix(".0") }.orEmpty(),
            unlimited = row.isUnlimited,
            isNew = isNew,
        )
    }
}
