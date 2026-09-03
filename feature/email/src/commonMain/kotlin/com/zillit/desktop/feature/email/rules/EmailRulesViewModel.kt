package com.zillit.desktop.feature.email.rules

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.email.domain.EmailFolder

data class RuleEditorState(
    val draft: EmailRule = EmailRule(),
    val isSaving: Boolean = false,
    /** The mailbox's folders a Move action may target — the system ones already excluded. */
    val folderOptions: List<String> = emptyList(),
    /** The Drive picker: the folders being looked at, the trail above them, and which action row is picking. */
    val driveChildren: List<DriveFolderOption> = emptyList(),
    val driveTrail: List<DriveFolderOption> = emptyList(),
    val driveLoading: Boolean = false,
    val pickingForAction: Int? = null,
)

data class RuleHistoryState(
    val rule: EmailRule,
    val executions: List<RuleExecution> = emptyList(),
    val total: Int = 0,
    val isLoading: Boolean = false,
) {
    val hasMore: Boolean get() = executions.size < total
}

data class EmailRulesUiState(
    val rules: List<EmailRule> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val editor: RuleEditorState? = null,
    val history: RuleHistoryState? = null,
    val canPickDriveFolder: Boolean = false,
) {
    val atLimit: Boolean get() = rules.size >= EmailRule.MAX_RULES
}

sealed interface EmailRulesEvent {
    data object Load : EmailRulesEvent
    data object New : EmailRulesEvent
    data class Edit(val ruleId: String) : EmailRulesEvent
    data object CloseEditor : EmailRulesEvent
    /** The editor's whole draft, replaced — every field control edits a copy. */
    data class Draft(val rule: EmailRule) : EmailRulesEvent
    data object Save : EmailRulesEvent
    data class Delete(val ruleId: String) : EmailRulesEvent
    data class SetEnabled(val ruleId: String, val enabled: Boolean) : EmailRulesEvent
    data class Move(val ruleId: String, val up: Boolean) : EmailRulesEvent
    data class OpenHistory(val ruleId: String) : EmailRulesEvent
    data object CloseHistory : EmailRulesEvent
    data object MoreHistory : EmailRulesEvent
    /** Browse the Drive for the action at [actionIndex]; null folder is the root. */
    data class BrowseDrive(val actionIndex: Int, val folder: DriveFolderOption?) : EmailRulesEvent
    data object DriveUp : EmailRulesEvent
    data class PickDriveFolder(val folder: DriveFolderOption) : EmailRulesEvent
    data object CancelDrivePick : EmailRulesEvent
    data object DismissMessage : EmailRulesEvent
}

/**
 * Inbox rules — the web's `EmailRulesPage` and Android's `EmailRulesActivity`
 * on `/v2/email-rules`. The list is the run order; Move up/down re-sends the
 * whole order (`reorder`), as dragging does on the web.
 */
class EmailRulesViewModel(
    private val repository: EmailRulesRepository,
    private val folders: suspend () -> ZillitResult<List<EmailFolder>> = { ZillitResult.Success(emptyList()) },
    private val driveFolders: DriveFolderSource? = null,
) : ZillitViewModel<EmailRulesUiState, EmailRulesEvent, Nothing>(
    EmailRulesUiState(canPickDriveFolder = driveFolders != null),
) {

    @Suppress("CyclomaticComplexMethod") // One branch per user action.
    override fun onEvent(event: EmailRulesEvent) {
        when (event) {
            EmailRulesEvent.Load -> load()
            EmailRulesEvent.New -> openEditor(EmailRule())
            is EmailRulesEvent.Edit -> currentState.rules.firstOrNull { it.id == event.ruleId }?.let(::openEditor)
            EmailRulesEvent.CloseEditor -> setState { copy(editor = null) }
            is EmailRulesEvent.Draft -> setState { copy(editor = editor?.copy(draft = event.rule)) }
            EmailRulesEvent.Save -> save()
            is EmailRulesEvent.Delete -> delete(event.ruleId)
            is EmailRulesEvent.SetEnabled -> setEnabled(event.ruleId, event.enabled)
            is EmailRulesEvent.Move -> move(event.ruleId, event.up)
            is EmailRulesEvent.OpenHistory -> openHistory(event.ruleId)
            EmailRulesEvent.CloseHistory -> setState { copy(history = null) }
            EmailRulesEvent.MoreHistory -> loadHistory(append = true)
            is EmailRulesEvent.BrowseDrive -> browseDrive(event.actionIndex, event.folder)
            EmailRulesEvent.DriveUp -> driveUp()
            is EmailRulesEvent.PickDriveFolder -> pickDriveFolder(event.folder)
            EmailRulesEvent.CancelDrivePick -> setState { copy(editor = editor?.copy(pickingForAction = null)) }
            EmailRulesEvent.DismissMessage -> setState { copy(error = null, info = null) }
        }
    }

    private fun load() {
        setState { copy(isLoading = true, error = null) }
        launchResult(
            block = { repository.rules() },
            onSuccess = { rules -> setState { copy(isLoading = false, rules = rules) } },
            onError = { failure -> setState { copy(isLoading = false, error = failure.text()) } },
        )
    }

    private fun openEditor(rule: EmailRule) {
        if (rule.isNew && currentState.atLimit) {
            setState { copy(error = "You already have ${EmailRule.MAX_RULES} rules — remove one to add another.") }
            return
        }
        setState { copy(editor = RuleEditorState(draft = rule), error = null, info = null) }
        launch {
            val names = (folders() as? ZillitResult.Success)?.data.orEmpty().map { it.name }
            setState { copy(editor = editor?.copy(folderOptions = selectableMoveFolders(names))) }
        }
    }

    private fun save() {
        val editor = currentState.editor ?: return
        val draft = editor.draft.copy(name = editor.draft.name.trim())
        draft.validationIssue?.let { issue ->
            setState { copy(error = issue) }
            return
        }
        setState { copy(editor = editor.copy(isSaving = true), error = null) }
        launchResult(
            block = { if (draft.isNew) repository.create(draft) else repository.update(draft) },
            onSuccess = { saved ->
                setState {
                    val kept = if (draft.isNew) rules + saved else rules.map { if (it.id == saved.id) saved else it }
                    copy(editor = null, rules = kept, info = if (draft.isNew) "Rule added" else "Rule saved")
                }
            },
            onError = { failure -> setState { copy(editor = editor.copy(isSaving = false), error = failure.text()) } },
        )
    }

    private fun delete(ruleId: String) {
        launchResult(
            block = { repository.delete(ruleId) },
            onSuccess = { setState { copy(rules = rules.filterNot { it.id == ruleId }, info = "Rule removed") } },
            onError = { failure -> setState { copy(error = failure.text()) } },
        )
    }

    private fun setEnabled(ruleId: String, enabled: Boolean) {
        val rule = currentState.rules.firstOrNull { it.id == ruleId } ?: return
        setState { copy(rules = rules.map { if (it.id == ruleId) it.copy(enabled = enabled) else it }) }
        launchResult(
            block = { repository.update(rule.copy(enabled = enabled)) },
            onSuccess = { saved -> setState { copy(rules = rules.map { if (it.id == saved.id) saved else it }) } },
            onError = { failure ->
                setState { copy(rules = rules.map { if (it.id == ruleId) rule else it }, error = failure.text()) }
            },
        )
    }

    private fun move(ruleId: String, up: Boolean) {
        val before = currentState.rules
        val index = before.indexOfFirst { it.id == ruleId }
        val target = if (up) index - 1 else index + 1
        if (index < 0 || target !in before.indices) return
        val after = before.toMutableList().also { it.add(target, it.removeAt(index)) }
        setState { copy(rules = after) }
        launchResult(
            block = { repository.reorder(after.map { it.id }) },
            onSuccess = { setState { copy(rules = rules.mapIndexed { i, rule -> rule.copy(priority = i) }) } },
            onError = { failure -> setState { copy(rules = before, error = failure.text()) } },
        )
    }

    private fun openHistory(ruleId: String) {
        val rule = currentState.rules.firstOrNull { it.id == ruleId } ?: return
        setState { copy(history = RuleHistoryState(rule = rule, isLoading = true)) }
        loadHistory(append = false)
    }

    private fun loadHistory(append: Boolean) {
        val history = currentState.history ?: return
        val skip = if (append) history.executions.size else 0
        setState { copy(history = history.copy(isLoading = true)) }
        launchResult(
            block = { repository.executions(history.rule.id, skip = skip) },
            onSuccess = { page ->
                setState {
                    val current = this.history ?: return@setState this
                    val rows = if (append) current.executions + page.executions else page.executions
                    copy(history = current.copy(executions = rows, total = page.total, isLoading = false))
                }
            },
            onError = { failure ->
                setState { copy(history = this.history?.copy(isLoading = false), error = failure.text()) }
            },
        )
    }

    private fun browseDrive(actionIndex: Int, folder: DriveFolderOption?) {
        val source = driveFolders ?: return
        setState {
            val editor = editor ?: return@setState this
            val trail = when {
                folder == null -> emptyList()
                folder in editor.driveTrail -> editor.driveTrail.take(editor.driveTrail.indexOf(folder) + 1)
                else -> editor.driveTrail + folder
            }
            copy(editor = editor.copy(pickingForAction = actionIndex, driveTrail = trail, driveLoading = true))
        }
        launchResult(
            block = { source.children(folder?.id) },
            onSuccess = { children ->
                setState { copy(editor = editor?.copy(driveChildren = children, driveLoading = false)) }
            },
            onError = { failure ->
                setState { copy(editor = editor?.copy(driveLoading = false), error = failure.text()) }
            },
        )
    }

    private fun driveUp() {
        val editor = currentState.editor ?: return
        val index = editor.pickingForAction ?: return
        browseDrive(index, editor.driveTrail.dropLast(1).lastOrNull())
    }

    private fun pickDriveFolder(folder: DriveFolderOption) {
        setState {
            val editor = editor ?: return@setState this
            val index = editor.pickingForAction ?: return@setState this
            val actions = editor.draft.actions.mapIndexed { i, action ->
                if (i == index && action is RuleAction.SaveAttachmentsToDrive) {
                    action.copy(driveFolderId = folder.id, driveFolderName = folder.name)
                } else {
                    action
                }
            }
            copy(editor = editor.copy(draft = editor.draft.copy(actions = actions), pickingForAction = null))
        }
    }

    private fun ZillitError.text(): String = localised()
}
