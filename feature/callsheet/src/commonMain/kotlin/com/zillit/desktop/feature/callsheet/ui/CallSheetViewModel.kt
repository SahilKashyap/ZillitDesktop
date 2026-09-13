package com.zillit.desktop.feature.callsheet.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.callsheet.domain.CallSheetDelivery
import com.zillit.desktop.feature.callsheet.domain.CallSheetPublishing
import com.zillit.desktop.feature.callsheet.domain.CallSheetRepository
import com.zillit.desktop.feature.callsheet.domain.CallSheetViewer
import com.zillit.desktop.feature.callsheet.domain.CompanySeed
import com.zillit.desktop.feature.callsheet.domain.SavedSignatureSource
import com.zillit.desktop.feature.callsheet.domain.SheetBadgeSource
import com.zillit.desktop.feature.callsheet.domain.SheetChatOpener
import com.zillit.desktop.feature.callsheet.domain.SheetMember
import com.zillit.desktop.feature.callsheet.domain.SheetTime
import com.zillit.desktop.feature.callsheet.domain.SheetWeatherSource
import kotlinx.coroutines.Job

/** One-shot feedback: the web's antd `message.success` / `message.error`. */
sealed interface SheetEffect {
    data class Toast(val message: String, val isError: Boolean = false) : SheetEffect
}

/** The host services a call sheet needs beyond its own service. */
class SheetServices(
    val delivery: CallSheetDelivery,
    val publishing: CallSheetPublishing,
    val badges: SheetBadgeSource = object : SheetBadgeSource {},
    val chat: SheetChatOpener = SheetChatOpener { _, _ -> false },
    val weather: SheetWeatherSource? = null,
    val signatures: SavedSignatureSource? = null,
    /** The open production's name and company, for Company Details. */
    val company: () -> CompanySeed = { CompanySeed() },
)

/**
 * What the controllers share: state, services and the ways to act. The
 * ViewModel implements it; each controller owns one area of the web's
 * `CallSheetApp` and its tabs.
 */
internal interface SheetContext {
    val state: SheetUiState
    val repository: CallSheetRepository
    val services: SheetServices
    val lists: ListsController
    val editor: EditorController

    fun update(reducer: SheetUiState.() -> SheetUiState)
    fun launchWork(block: suspend () -> Unit): Job
    fun toast(message: String, isError: Boolean = false)
    fun projectId(): String?
    fun members(): List<SheetMember>
    fun viewer(): CallSheetViewer
    fun now(): Long
    fun todayMs(): Long = SheetTime.todayMidnight(now())
}

/** A failure's sentence, prefixed the way the web's toasts are. */
internal fun ZillitError.withPrefix(prefix: String): String = "$prefix${localised()}"

/**
 * Call sheet creation and review — `/film-tools/call-sheet`.
 */
class CallSheetViewModel(
    private val repository: CallSheetRepository,
    private val services: SheetServices,
    private val resolveViewer: () -> CallSheetViewer,
    private val projectIdProvider: () -> String?,
    private val membersProvider: () -> List<SheetMember>,
    private val nowMillis: () -> Long,
) : ZillitViewModel<SheetUiState, SheetEvent, SheetEffect>(SheetUiState()) {

    /** The tool window's "open this person's chat", attached while it is shown. */
    private var chatTarget: SheetChatOpener? = null

    /** A chat opener that goes through the window when one is attached, else the host's own. */
    private val hostServices = SheetServices(
        delivery = services.delivery,
        publishing = services.publishing,
        badges = services.badges,
        chat = SheetChatOpener { userId, fullName -> (chatTarget ?: services.chat).openChat(userId, fullName) },
        weather = services.weather,
        signatures = services.signatures,
        company = services.company,
    )

    private val context: SheetContext = object : SheetContext {
        override val state: SheetUiState get() = currentState
        override val repository: CallSheetRepository get() = this@CallSheetViewModel.repository
        override val services: SheetServices get() = hostServices
        override val lists: ListsController get() = this@CallSheetViewModel.lists
        override val editor: EditorController get() = this@CallSheetViewModel.editor
        override fun update(reducer: SheetUiState.() -> SheetUiState) = setState(reducer)
        override fun launchWork(block: suspend () -> Unit): Job = launch { block() }
        override fun toast(message: String, isError: Boolean) = sendEffect(SheetEffect.Toast(message, isError))
        override fun projectId(): String? = projectIdProvider()?.takeIf { it.isNotBlank() }
        override fun members(): List<SheetMember> = membersProvider().filter { it.isAccepted }
        override fun viewer(): CallSheetViewer = resolveViewer()
        override fun now(): Long = nowMillis()
    }

    private val lists = ListsController(context)
    private val rows = RowActionsController(context)
    private val editor = EditorController(context)
    private val workflow = WorkflowController(context)
    private val templates = TemplateController(context)
    private val comments = CommentsController(context)
    private val document = DocumentController(context)
    private val permission = PermissionController(context)

    init {
        lists.onCommentEvent = comments::onSyncEvent
        lists.onPermissionTab = permission::load
    }

    /** Called each time the tool's window is shown. */
    fun start() = lists.start()

    /** Routes "Chat with …" through the tool's window while it is open; null detaches. */
    fun attachChat(opener: SheetChatOpener?) {
        chatTarget = opener
    }

    override fun onEvent(event: SheetEvent) {
        when (event) {
            is ListEvent -> onListEvent(event)
            is PermissionEvent -> permission.onEvent(event)
            is WorkflowEvent -> workflow.onEvent(event)
            is DialogEvent -> onDialogEvent(event)
            EditorEvent.SaveAsTemplate -> editor.guardTemplateSave { templates.saveAsTemplate() }
            EditorEvent.UpdateTemplate -> editor.guardTemplateSave { templates.updateTemplate() }
            EditorEvent.SendForSignature -> workflow.sendFromEditorForSignature()
            EditorEvent.SendForComments -> workflow.openEditorComments()
            is EditorEvent -> editor.onEvent(event)
            is DocumentEvent -> document.onEvent(event)
        }
    }

    private fun onListEvent(event: ListEvent) {
        when (event) {
            is ListEvent.OpenTab, is ListEvent.OpenSection, is ListEvent.SetDraftChip,
            is ListEvent.SetDraftsView, is ListEvent.SetApprovalsView, ListEvent.ToggleOlderPublished, ListEvent.Retry,
            -> lists.onEvent(event)
            is ListEvent.OpenComments -> comments.open(event.sheet, event.readOnly)
            else -> rows.onEvent(event)
        }
    }

    private fun onDialogEvent(event: DialogEvent) {
        when (event) {
            DialogEvent.Dismiss -> dismiss()
            DialogEvent.Confirm -> confirm(secondary = false)
            DialogEvent.ConfirmSecondary -> confirm(secondary = true)
            is DialogEvent.EditDraftName, DialogEvent.ConfirmDraftName, is DialogEvent.FixMissingTitle ->
                editor.onDialog(event)
            is DialogEvent.CreateTemplate, is DialogEvent.PickTemplate, is DialogEvent.UseTemplate,
            is DialogEvent.OpenSavedTemplate, is DialogEvent.DeleteSavedTemplate,
            -> templates.onEvent(event)
            else -> comments.onEvent(event)
        }
    }

    /** Closes the dialog — never mid-call, except the thread, which has nothing to lose. */
    private fun dismiss() {
        val dialog = currentState.dialog
        val sending = (dialog as? SheetDialog.ChatSend)?.sending == true ||
            (dialog as? SheetDialog.AttachDocument)?.uploading == true ||
            (dialog as? SheetDialog.Approve)?.uploading == true
        if (sending || (currentState.busy && dialog !is SheetDialog.Comments)) return
        val approve = dialog as? SheetDialog.Approve
        if (approve?.savedPicker != null) {
            setState { copy(dialog = approve.copy(savedPicker = null)) }
            return
        }
        val picker = dialog as? SheetDialog.SendPicker
        if (picker?.pendingRemoval != null) {
            setState { copy(dialog = picker.copy(pendingRemoval = null)) }
            return
        }
        comments.close()
        setState { copy(dialog = null) }
    }

    private fun confirm(secondary: Boolean) {
        val dialog = currentState.dialog as? SheetDialog.Confirm ?: return
        setState { copy(dialog = null) }
        when (val action = dialog.action) {
            is ConfirmAction.DeleteSheet -> rows.delete(action.sheet)
            is ConfirmAction.DeleteTemplate -> templates.delete(action.template)
            ConfirmAction.NoApprovers -> Unit
            is ConfirmAction.RestartReview -> editor.runSave(action.then)
            ConfirmAction.LeaveEditor -> if (secondary) editor.saveAndLeave() else editor.discardAndLeave()
        }
    }
}
