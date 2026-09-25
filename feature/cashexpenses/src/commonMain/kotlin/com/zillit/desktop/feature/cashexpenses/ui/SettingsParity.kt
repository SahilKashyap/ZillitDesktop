package com.zillit.desktop.feature.cashexpenses.ui

import com.zillit.desktop.feature.cashexpenses.domain.CashSettings
import com.zillit.desktop.feature.cashexpenses.domain.CashSettingsSection
import com.zillit.desktop.feature.cashexpenses.domain.DeductionRuleEdit
import com.zillit.desktop.feature.cashexpenses.domain.DepartmentCoordinator

/**
 * What the Settings page holds beyond the settings draft itself: which
 * section is saving, the coordinator rows' errors, and the two dialogs.
 */
data class SettingsUiState(
    /** The section whose Save is on its way — its button reads "Saving...". */
    val saving: CashSettingsSection? = null,
    /** Coordinator cell errors, keyed as `CoordinatorRules` keys them. */
    val coordErrors: Set<String> = emptySet(),
    /** The coordinator department / users picker, while it is open. */
    val coordPicker: CoordinatorPicker? = null,
    /** The deduction rule being added or edited, while its dialog is open. */
    val ruleEditor: DeductionRuleEdit? = null,
)

/**
 * The coordinator picker — the web's buffered modal: the choice is applied
 * only on Done (`PCSettingsPage.jsx:851-927`).
 */
data class CoordinatorPicker(
    val row: Int,
    /** True for the users picker, false for the department picker. */
    val users: Boolean,
    val search: String = "",
    val departmentId: String = "",
    val userIds: List<String> = emptyList(),
)

/** The Settings page's own events, handled by [SettingsDesk]. */
sealed interface SettingsEvent : CashEvent {
    /** Saves one section with only its own keys. */
    data class SaveSection(val section: CashSettingsSection) : SettingsEvent

    data object AddCoordinator : SettingsEvent

    data class UpdateCoordinator(val row: Int, val coordinator: DepartmentCoordinator) : SettingsEvent

    data class RemoveCoordinator(val row: Int) : SettingsEvent

    data class OpenCoordinatorPicker(val row: Int, val users: Boolean) : SettingsEvent

    data class EditCoordinatorPicker(val picker: CoordinatorPicker) : SettingsEvent

    data object ApplyCoordinatorPicker : SettingsEvent

    data object CloseCoordinatorPicker : SettingsEvent

    data object AddDeductionRule : SettingsEvent

    data class EditDeductionRule(val index: Int) : SettingsEvent

    /** The rule dialog's fields; null closes it without applying. */
    data class UpdateRuleEditor(val edit: DeductionRuleEdit?) : SettingsEvent

    /** Applies the dialog's rule to the draft; the section's Save sends it. */
    data object CommitDeductionRule : SettingsEvent

    data class RemoveDeductionRule(val index: Int) : SettingsEvent

    data object AddAssignmentRule : SettingsEvent

    /** Removes a rule; one the server holds is deleted at once, as on the web. */
    data class RemoveAssignmentRule(val index: Int) : SettingsEvent
}

/**
 * Whether anything on the Settings page is mid-edit — the web's `anyDirty`
 * guard (`PCSettingsPage.jsx:398-402`), under which a socket-driven reload
 * must not replace what the accountant is typing.
 */
internal val CashUiState.settingsEditing: Boolean
    get() = (settingsDraft != null && settingsDraft != settings) ||
        rulesDraft != null ||
        capDraft != null ||
        teamEditor != null ||
        settingsUi.ruleEditor != null ||
        settingsUi.coordPicker != null ||
        settingsUi.saving != null

/** A fresh settings read, applied only while nothing is being edited. */
internal fun CashUiState.settingsReloaded(fresh: CashSettings): CashUiState =
    if (settings != null && settingsEditing) this else copy(settings = fresh, settingsDraft = fresh)

/**
 * The draft after a save answered with [saved]: every section the person had
 * not touched follows the server, and each one still being edited — other
 * than those [justSaved] — keeps the typing.
 */
internal fun rebaseDraft(
    stored: CashSettings?,
    draft: CashSettings?,
    saved: CashSettings,
    justSaved: CashSettingsSection? = null,
): CashSettings {
    if (draft == null) return saved
    return CashSettingsSection.entries.fold(saved) { next, section ->
        if (section != justSaved && section.isDirty(draft, stored)) section.merge(next, draft) else next
    }
}
