package com.zillit.desktop.feature.productionreport.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.productionreport.domain.PublishedCallSheetLookup
import com.zillit.desktop.feature.productionreport.domain.ReportBadgeSource
import com.zillit.desktop.feature.productionreport.domain.ReportDelivery
import com.zillit.desktop.feature.productionreport.domain.ReportKind
import com.zillit.desktop.feature.productionreport.domain.ReportPublishing
import com.zillit.desktop.feature.productionreport.domain.ReportRepository
import com.zillit.desktop.feature.productionreport.domain.ReportTime
import com.zillit.desktop.feature.productionreport.domain.ReportViewer
import com.zillit.desktop.feature.productionreport.domain.ReportWeatherSource
import com.zillit.desktop.feature.productionreport.domain.SheetMember
import kotlinx.coroutines.Job

/** One-shot feedback: the web's antd `message.success` / `message.error`. */
sealed interface ReportEffect {
    data class Toast(val message: String, val isError: Boolean = false) : ReportEffect
}

/** The host services a report needs beyond its own service. */
class ReportServices(
    val delivery: ReportDelivery,
    val publishing: ReportPublishing,
    val callSheets: PublishedCallSheetLookup,
    val badges: ReportBadgeSource = object : ReportBadgeSource {},
    val weather: ReportWeatherSource? = null,
)

/**
 * What the controllers share: state, services and the ways to act. The
 * ViewModel implements it; each controller owns one area of the web's
 * `ProductionReportApp` and its tabs.
 */
internal interface ReportContext {
    val state: ReportUiState
    val repository: ReportRepository
    val services: ReportServices
    val kind: ReportKind
    val lists: ListsController
    val editor: EditorController

    fun update(reducer: ReportUiState.() -> ReportUiState)
    fun launchWork(block: suspend () -> Unit): Job
    fun toast(message: String, isError: Boolean = false)
    fun projectId(): String?
    fun members(): List<SheetMember>
    fun viewer(): ReportViewer
    fun now(): Long
    fun todayYmd(): String = ReportTime.todayYmd(now())
}

/** A failure's sentence, prefixed the way the web's toasts are. */
internal fun ZillitError.withPrefix(prefix: String): String = "$prefix${localised()}"

/**
 * Production report creation and review — and the AD / Wrap reports that
 * ride the same engine under `shared.reportType`.
 */
class ReportViewModel(
    private val repository: ReportRepository,
    private val services: ReportServices,
    /** Which of the three report tools this instance is. */
    val kind: ReportKind = ReportKind.Production,
    private val resolveViewer: () -> ReportViewer,
    private val projectIdProvider: () -> String?,
    private val membersProvider: () -> List<SheetMember>,
    private val nowMillis: () -> Long,
    /** Whether the host shows a chat in the Chat workspace. */
    hasChat: Boolean = false,
) : ZillitViewModel<ReportUiState, ReportEvent, ReportEffect>(ReportUiState(kind = kind, hasChat = hasChat)) {

    private val context: ReportContext = object : ReportContext {
        override val state: ReportUiState get() = currentState
        override val repository: ReportRepository get() = this@ReportViewModel.repository
        override val services: ReportServices get() = this@ReportViewModel.services
        override val kind: ReportKind get() = this@ReportViewModel.kind
        override val lists: ListsController get() = this@ReportViewModel.lists
        override val editor: EditorController get() = this@ReportViewModel.editor
        override fun update(reducer: ReportUiState.() -> ReportUiState) = setState(reducer)
        override fun launchWork(block: suspend () -> Unit): Job = launch { block() }
        override fun toast(message: String, isError: Boolean) = sendEffect(ReportEffect.Toast(message, isError))
        override fun projectId(): String? = projectIdProvider()?.takeIf { it.isNotBlank() }
        override fun members(): List<SheetMember> = membersProvider()
        override fun viewer(): ReportViewer = resolveViewer()
        override fun now(): Long = nowMillis()
    }

    private val lists = ListsController(context)
    private val rows = RowActionsController(context)
    private val editor = EditorController(context)
    private val workflow = WorkflowController(context)
    private val templates = TemplateController(context)
    private val comments = CommentsController(context)
    private val document = DocumentController(context)

    init {
        lists.onCommentEvent = comments::onSyncEvent
    }

    /** Called each time the tool's window is shown. */
    fun start() = lists.start()

    override fun onEvent(event: ReportEvent) {
        when (event) {
            is ListEvent -> onListEvent(event)
            is WorkflowEvent -> workflow.onEvent(event)
            is DialogEvent -> onDialogEvent(event)
            EditorEvent.SaveAsTemplate -> templates.saveAsTemplate()
            EditorEvent.UpdateTemplate -> templates.updateTemplate()
            EditorEvent.SendForSignature -> workflow.sendFromEditorForSignature()
            EditorEvent.SendForComments -> workflow.openEditorComments()
            is EditorEvent -> editor.onEvent(event)
            is DocumentEvent -> document.onEvent(event)
        }
    }

    private fun onListEvent(event: ListEvent) {
        when (event) {
            is ListEvent.SetWorkspace, is ListEvent.OpenTab, is ListEvent.OpenSection, is ListEvent.SetDraftChip,
            is ListEvent.SetDraftsView, is ListEvent.SetApprovalsView, ListEvent.ToggleOlderPublished, ListEvent.Retry,
            -> lists.onEvent(event)
            is ListEvent.OpenComments -> comments.open(event.report, event.readOnly)
            else -> rows.onEvent(event)
        }
    }

    private fun onDialogEvent(event: DialogEvent) {
        when (event) {
            DialogEvent.Dismiss -> dismiss()
            DialogEvent.Confirm -> confirm(secondary = false)
            DialogEvent.ConfirmSecondary -> confirm(secondary = true)
            is DialogEvent.EditDraftName, DialogEvent.ConfirmDraftName -> editor.onDialog(event)
            is DialogEvent.CreateTemplate, is DialogEvent.PickTemplate, is DialogEvent.UseTemplate,
            is DialogEvent.OpenSavedTemplate, is DialogEvent.DeleteSavedTemplate,
            -> templates.onEvent(event)
            else -> comments.onEvent(event)
        }
    }

    /** A dialog with a request in flight (a busy confirm, a sending picker) does not close under it. */
    private fun dismiss() {
        val dialog = currentState.dialog
        if (currentState.busy && dialog !is ReportDialog.Comments) return
        if ((dialog as? ReportDialog.Confirm)?.busy == true || (dialog as? ReportDialog.SendForChat)?.sending == true) {
            return
        }
        comments.close()
        setState { copy(dialog = null) }
    }

    /**
     * The deletes keep their confirm up until the request settles (the
     * controller closes it on success); everything else closes at once.
     */
    private fun confirm(secondary: Boolean) {
        val dialog = currentState.dialog as? ReportDialog.Confirm ?: return
        if (dialog.busy) return
        when (val action = dialog.action) {
            is ConfirmAction.DeleteReport -> rows.delete(action.report)
            is ConfirmAction.DeleteTemplate -> templates.delete(action.template)
            else -> {
                setState { copy(dialog = null) }
                when (action) {
                    is ConfirmAction.RestartReview -> editor.runSave(action.then)
                    ConfirmAction.LeaveEditor -> if (secondary) editor.saveAndLeave() else editor.discardAndLeave()
                    // NoApprovers, MissingCallTimes, NoPublishedCallSheet: an OK that only closes.
                    else -> Unit
                }
            }
        }
    }
}
