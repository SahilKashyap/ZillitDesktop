package com.zillit.desktop.feature.cashexpenses.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashAssignmentRule
import com.zillit.desktop.feature.cashexpenses.domain.CashSettings
import com.zillit.desktop.feature.cashexpenses.domain.CashTeamMember
import com.zillit.desktop.feature.cashexpenses.domain.ExportFormat
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Settings' sections that save on their own — the team, the request cap and
 * the auto-assignment rules — as the web's `PCSettingsPage` saves them.
 *
 * All of it rewrites what other people may do, so every handler asks the
 * same question the tab does: a senior accountant.
 */
internal class SettingsDesk(private val host: CashHost) {

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
        val limit = draft.postingLimit?.trim()?.let { it.toDoubleOrNull() ?: 0.0 }
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

    fun saveCap() {
        val cap = host.state.capDraft ?: return
        if (!host.state.viewer.canOpenSettings) return host.noRights()
        if (cap.blocked) return host.refuse(str(S.desktop_ce_cap_needs_amount))
        saveSection { host.repository.updateRequestCap(cap) }
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

    private fun saveTeam(members: List<CashTeamMember>, closeEditor: Boolean) = saveSection(closeEditor) {
        host.repository.updateTeamMembers(members)
    }

    private fun saveSection(closeEditor: Boolean = false, block: suspend () -> ZillitResult<CashSettings>) {
        host.work {
            host.update { copy(busy = true) }
            when (val saved = block()) {
                is ZillitResult.Success -> host.update {
                    copy(
                        busy = false,
                        notice = str(S.desktop_ce_settings_saved),
                        settings = saved.data,
                        // Only the sections this saved; an unsaved edit elsewhere stays.
                        settingsDraft = settingsDraft?.copy(
                            teamMembers = saved.data.teamMembers,
                            requestCap = saved.data.requestCap,
                        ) ?: saved.data,
                        capDraft = null,
                        teamEditor = if (closeEditor) null else teamEditor,
                    )
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
