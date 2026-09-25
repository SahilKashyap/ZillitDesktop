package com.zillit.desktop.feature.cashexpenses.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.BatchAssignment
import com.zillit.desktop.feature.cashexpenses.domain.CashAssignmentRule
import com.zillit.desktop.feature.cashexpenses.domain.CashSettings
import com.zillit.desktop.feature.cashexpenses.domain.CashSettingsSection
import com.zillit.desktop.feature.cashexpenses.domain.CashTeamMember
import com.zillit.desktop.feature.cashexpenses.domain.CoordinatorRules
import com.zillit.desktop.feature.cashexpenses.domain.DeductionRuleEdit
import com.zillit.desktop.feature.cashexpenses.domain.DepartmentCoordinator
import com.zillit.desktop.feature.cashexpenses.domain.ExportFormat
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Settings, section by section — as the web's `PCSettingsPage` saves it.
 *
 * Six sections of the settings document save with their own Save and only
 * their own keys ([CashSettingsSection]); the team saves the moment a member
 * changes, the request cap and the assignment rules through their own calls.
 * All of it rewrites what other people may do, so every handler asks the
 * same question the tab does: a senior accountant.
 */
@Suppress("TooManyFunctions") // One handler per control on the page.
internal class SettingsDesk(private val host: CashHost) {

    @Suppress("CyclomaticComplexMethod") // A dispatcher: one branch per event.
    fun handle(event: SettingsEvent) {
        if (!host.state.viewer.canOpenSettings) return host.noRights()
        when (event) {
            is SettingsEvent.SaveSection -> saveSection(event.section)
            SettingsEvent.AddCoordinator -> editCoordinators { it + DepartmentCoordinator(departmentId = "") }
            is SettingsEvent.UpdateCoordinator -> editCoordinators { rows ->
                rows.mapIndexed { i, row -> if (i == event.row) event.coordinator else row }
            }
            is SettingsEvent.RemoveCoordinator -> {
                editCoordinators { rows -> rows.filterIndexed { i, _ -> i != event.row } }
                ui { copy(coordErrors = emptySet()) }
            }
            is SettingsEvent.OpenCoordinatorPicker -> openPicker(event.row, event.users)
            is SettingsEvent.EditCoordinatorPicker -> ui { copy(coordPicker = event.picker) }
            SettingsEvent.ApplyCoordinatorPicker -> applyPicker()
            SettingsEvent.CloseCoordinatorPicker -> ui { copy(coordPicker = null) }
            SettingsEvent.AddDeductionRule ->
                ui { copy(ruleEditor = DeductionRuleEdit.fresh(Clock.System.now().toEpochMilliseconds())) }
            is SettingsEvent.EditDeductionRule -> {
                val rule = draft()?.deductionRules?.getOrNull(event.index) ?: return
                ui { copy(ruleEditor = DeductionRuleEdit(rule, event.index)) }
            }
            is SettingsEvent.UpdateRuleEditor -> ui { copy(ruleEditor = event.edit) }
            SettingsEvent.CommitDeductionRule -> commitRule()
            is SettingsEvent.RemoveDeductionRule -> editDraft { settings ->
                val rule = settings.deductionRules.getOrNull(event.index)
                // The four the server ships are switched off, never deleted.
                if (rule == null || rule.systemDefault) {
                    settings
                } else {
                    settings.copy(deductionRules = settings.deductionRules.filterIndexed { i, _ -> i != event.index })
                }
            }
            SettingsEvent.AddAssignmentRule -> addRule()
            is SettingsEvent.RemoveAssignmentRule -> removeRule(event.index)
        }
    }

    // -- the six document sections --------------------------------------------

    /**
     * Saves one section — `PATCH /settings` with its keys alone. Coordinator
     * rows are checked first, and nothing is sent while one is incomplete.
     */
    private fun saveSection(section: CashSettingsSection) {
        val draft = draft() ?: return
        if (host.state.settingsUi.saving != null) return
        if (section == CashSettingsSection.Coordinators) {
            val errors = CoordinatorRules.errors(draft.departmentCoordinators)
            if (errors.isNotEmpty()) return ui { copy(coordErrors = errors) }
        }
        host.work {
            host.update {
                copy(
                    busy = true,
                    settingsUi = settingsUi.copy(
                        saving = section,
                        coordErrors = settingsUi.coordErrors.takeUnless {
                            section == CashSettingsSection.Coordinators
                        }.orEmpty(),
                    ),
                )
            }
            when (val saved = host.repository.updateSettingsSection(section, draft)) {
                is ZillitResult.Success -> {
                    host.update {
                        copy(
                            busy = false,
                            notice = str(S.desktop_ce_settings_saved),
                            settings = saved.data,
                            settingsDraft = rebaseDraft(settings, settingsDraft, saved.data, justSaved = section),
                            settingsUi = settingsUi.copy(saving = null),
                        )
                    }
                    // The saver re-pulls its own grants — `emitCashMetadataRefetch`.
                    host.refetchMetadata()
                }

                is ZillitResult.Failure -> {
                    host.update { copy(busy = false, settingsUi = settingsUi.copy(saving = null)) }
                    host.report(saved.error)
                }
            }
        }
    }

    private fun editCoordinators(change: (List<DepartmentCoordinator>) -> List<DepartmentCoordinator>) =
        editDraft { it.copy(departmentCoordinators = change(it.departmentCoordinators)) }

    /** Opens a coordinator cell's picker on the row's current choice; a users cell needs a department first. */
    private fun openPicker(row: Int, users: Boolean) {
        val coordinator = draft()?.departmentCoordinators?.getOrNull(row) ?: return
        if (users && coordinator.departmentId.isBlank()) return
        val field = if (users) CoordinatorRules.USERS else CoordinatorRules.DEPARTMENT
        ui {
            copy(
                coordPicker = CoordinatorPicker(
                    row = row,
                    users = users,
                    departmentId = coordinator.departmentId,
                    userIds = coordinator.userIds,
                ),
                coordErrors = coordErrors - CoordinatorRules.key(row, field),
            )
        }
    }

    /**
     * Done: the picked department or people land on the row. A new
     * department empties the people, who belonged to the old one.
     */
    private fun applyPicker() {
        val picker = host.state.settingsUi.coordPicker ?: return
        editCoordinators { rows ->
            rows.mapIndexed { i, row ->
                when {
                    i != picker.row -> row
                    picker.users -> row.copy(userIds = picker.userIds)
                    picker.departmentId == row.departmentId -> row
                    else -> row.copy(departmentId = picker.departmentId, userIds = emptyList())
                }
            }
        }
        ui { copy(coordPicker = null) }
    }

    /** Applies the rule dialog to the draft: a new rule appended, an edited one replaced. */
    private fun commitRule() {
        val edit = host.state.settingsUi.ruleEditor ?: return
        if (!edit.canCommit) return
        editDraft { settings ->
            val rules = settings.deductionRules
            val next = edit.index?.let { index -> rules.mapIndexed { i, old -> if (i == index) edit.rule else old } }
                ?: (rules + edit.rule)
            settings.copy(deductionRules = next)
        }
        ui { copy(ruleEditor = null) }
    }

    // -- the team --------------------------------------------------------------

    fun editMember(draft: TeamMemberDraft?) {
        if (draft != null && !host.state.viewer.canOpenSettings) return host.noRights()
        host.update { copy(teamEditor = draft) }
    }

    /** Adds or updates the member being edited; the team saves at once, as on the web. */
    fun saveMember() {
        val draft = host.state.teamEditor ?: return
        val settings = host.state.settings ?: return
        if (!host.state.viewer.canOpenSettings) return host.noRights()
        if (draft.userId.isBlank()) return host.refuse(str(S.desktop_ce_choose_team_member))
        // A cleared limit is Unlimited, not zero (`emptyValue={null}` on the web).
        val limit = draft.postingLimit?.trim()?.takeIf { it.isNotEmpty() }?.let { it.toDoubleOrNull() ?: 0.0 }
        val member = CashTeamMember(
            userId = draft.userId,
            name = host.state.assignees.firstOrNull { it.userId == draft.userId }?.fullName.orEmpty(),
            // A senior is unlimited and may override, whatever was typed.
            isSenior = draft.isSenior,
            canOverride = draft.isSenior || draft.canOverride,
            postingLimit = if (draft.isSenior) null else limit,
        )
        val members = settings.teamMembers
        val next = draft.index?.let { index -> members.mapIndexed { i, old -> if (i == index) member else old } }
            ?: (members + member)
        saveTeam(next, closeEditor = true)
    }

    fun removeMember(index: Int) {
        val settings = host.state.settings ?: return
        if (!host.state.viewer.canOpenSettings) return host.noRights()
        saveTeam(settings.teamMembers.filterIndexed { i, _ -> i != index }, closeEditor = false)
    }

    // -- request cap -----------------------------------------------------------

    fun saveCap() {
        val cap = host.state.capDraft ?: return
        if (!host.state.viewer.canOpenSettings) return host.noRights()
        if (cap.blocked) return host.refuse(str(S.desktop_ce_cap_needs_amount))
        saveWhole(clearCap = true) { host.repository.updateRequestCap(cap) }
    }

    // -- auto-assignment rules ---------------------------------------------------

    /** A new rule, active, assigned to the first of the accounts team — `addAssignmentRule`. */
    private fun addRule() {
        val stored = host.state.settings?.assignmentRules.orEmpty()
        val rules = host.state.rulesDraft ?: stored
        val team = BatchAssignment.accountsTeam(host.state.assignees)
        val fresh = CashAssignmentRule(
            id = "temp-${Clock.System.now().toEpochMilliseconds()}",
            assignTo = team.firstOrNull()?.userId.orEmpty(),
        )
        host.update { copy(rulesDraft = rules + fresh) }
    }

    /**
     * Removes a rule. One the server holds is deleted there at once, without
     * waiting for Save — the web's `removeAssignmentRule`.
     */
    private fun removeRule(index: Int) {
        val stored = host.state.settings?.assignmentRules.orEmpty()
        val rules = host.state.rulesDraft ?: stored
        val rule = rules.getOrNull(index) ?: return
        val remaining = rules.filterIndexed { i, _ -> i != index }
        host.update {
            val keptStored = settings?.assignmentRules.orEmpty().filterNot { it.persisted && it.id == rule.id }
            copy(
                rulesDraft = remaining.takeUnless { it == keptStored },
                settings = settings?.copy(assignmentRules = keptStored),
                settingsDraft = settingsDraft?.copy(assignmentRules = keptStored),
            )
        }
        if (!rule.persisted) return
        host.work {
            when (val deleted = host.repository.deleteAssignmentRule(rule.id)) {
                is ZillitResult.Success -> host.refetchMetadata()
                is ZillitResult.Failure -> host.report(deleted.error)
            }
        }
    }

    /**
     * Saves the rules as a diff: removed rows deleted, the rest created or
     * updated, one call each — the web's `saveAssignmentRules`.
     */
    fun saveRules() {
        val rules = host.state.rulesDraft ?: return
        val stored = host.state.settings?.assignmentRules.orEmpty()
        if (!host.state.viewer.canOpenSettings) return host.noRights()
        if (rules.any { it.assignTo.isBlank() }) return host.refuse(str(S.desktop_ce_rule_needs_assignee))
        host.work {
            host.update { copy(busy = true) }
            val kept = rules.map { it.id }.toSet()
            stored.filter { it.persisted && it.id !in kept }.forEach { host.repository.deleteAssignmentRule(it.id) }
            val saved = mutableListOf<CashAssignmentRule>()
            for (rule in rules) {
                val unchanged = rule.persisted && stored.any { it == rule }
                if (unchanged) {
                    saved += rule
                    continue
                }
                when (val result = host.repository.saveAssignmentRule(rule)) {
                    is ZillitResult.Success -> saved += result.data
                    is ZillitResult.Failure -> {
                        host.update { copy(busy = false) }
                        return@work host.report(result.error)
                    }
                }
            }
            // A rule change can change who this viewer is here (the web's
            // `emitAssignmentRuleRefetch`).
            host.refetchMetadata()
            host.update {
                copy(
                    busy = false,
                    notice = str(S.desktop_ce_settings_saved),
                    rulesDraft = null,
                    settings = settings?.copy(assignmentRules = saved),
                    settingsDraft = settingsDraft?.copy(assignmentRules = saved),
                )
            }
        }
    }

    // -- plumbing ----------------------------------------------------------------

    private fun draft(): CashSettings? = host.state.settingsDraft ?: host.state.settings

    private fun editDraft(change: (CashSettings) -> CashSettings) {
        val current = draft() ?: return
        host.update { copy(settingsDraft = change(current)) }
    }

    private fun ui(change: SettingsUiState.() -> SettingsUiState) =
        host.update { copy(settingsUi = settingsUi.change()) }

    private fun saveTeam(members: List<CashTeamMember>, closeEditor: Boolean) = saveWhole(closeEditor = closeEditor) {
        host.repository.updateTeamMembers(members)
    }

    /**
     * A save whose answer is the whole document — the team, the cap. The
     * sections still being edited keep their typing; the rest follow the
     * server ([rebaseDraft]).
     */
    private fun saveWhole(
        closeEditor: Boolean = false,
        clearCap: Boolean = false,
        block: suspend () -> ZillitResult<CashSettings>,
    ) {
        host.work {
            host.update { copy(busy = true) }
            when (val saved = block()) {
                is ZillitResult.Success -> {
                    host.update {
                        copy(
                            busy = false,
                            notice = str(S.desktop_ce_settings_saved),
                            settings = saved.data,
                            settingsDraft = rebaseDraft(settings, settingsDraft, saved.data),
                            capDraft = if (clearCap) null else capDraft,
                            teamEditor = if (closeEditor) null else teamEditor,
                        )
                    }
                    // The saver re-pulls its own grants — `emitCashMetadataRefetch`.
                    host.refetchMetadata()
                }

                is ZillitResult.Failure -> {
                    host.update { copy(busy = false) }
                    host.report(saved.error)
                }
            }
        }
    }
}

/**
 * The register exports: floats and receipts, PDF or spreadsheet — the web's
 * Export menu on the pipeline tabs and on History.
 */
internal class ExportDesk(private val host: CashHost) {

    fun export(register: ExportRegister, format: ExportFormat) {
        if (!host.state.viewer.isAccountant) return host.noRights()
        val files = host.files ?: return host.refuse(str(S.desktop_cannot_save_exports))
        if (host.state.exporting) return
        host.update { copy(exporting = true) }
        host.work {
            val bytes = when (register) {
                ExportRegister.Floats -> host.repository.exportFloats(format)
                ExportRegister.PettyCashReceipts -> host.repository.exportReceipts(format, ExpenseType.PettyCash, false)
                ExportRegister.OutOfPocketReceipts ->
                    host.repository.exportReceipts(format, ExpenseType.OutOfPocket, false)
                ExportRegister.History -> host.repository.exportReceipts(format, null, true)
            }
            val name = "${register.fileBase}_${stamp()}.${format.wire}"
            val outcome = when (bytes) {
                is ZillitResult.Failure -> bytes
                is ZillitResult.Success -> files.saveAndOpen(name, bytes.data)
            }
            val saved = outcome is ZillitResult.Success
            host.update { copy(exporting = false, notice = if (saved) str(S.desktop_exported_file, name) else notice) }
            (outcome as? ZillitResult.Failure)?.let { host.report(it.error) }
        }
    }

    /** `YYYY-MM-DD_HHMM`, local — the web's `exportTs`. */
    private fun stamp(): String {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        fun pad(value: Int) = value.toString().padStart(2, '0')
        return "${now.date}_${pad(now.hour)}${pad(now.minute)}"
    }

    private val ExportRegister.fileBase: String
        get() = when (this) {
            ExportRegister.Floats -> "cash-floats"
            ExportRegister.PettyCashReceipts -> "pc-receipts"
            ExportRegister.OutOfPocketReceipts -> "oop-receipts"
            ExportRegister.History -> "cash-receipts-history"
        }
}
