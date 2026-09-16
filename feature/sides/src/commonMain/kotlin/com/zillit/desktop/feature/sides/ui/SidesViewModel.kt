package com.zillit.desktop.feature.sides.ui

import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.permissions.rightsRefusalMessage
import com.zillit.desktop.feature.sides.domain.SidesRepository
import com.zillit.desktop.feature.sides.domain.SidesTransfer
import com.zillit.desktop.feature.sides.domain.SidesViewer
import com.zillit.desktop.feature.sides.ui.flows.AutoFlow
import com.zillit.desktop.feature.sides.ui.flows.DialogFlow
import com.zillit.desktop.feature.sides.ui.flows.GenerateFlow
import com.zillit.desktop.feature.sides.ui.flows.ListFlow
import com.zillit.desktop.feature.sides.ui.flows.PdfFlow
import com.zillit.desktop.feature.sides.ui.flows.ScriptsFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Sides: scripts in, scene selection, server-side generation, review,
 * publish — the web's `sides/` module.
 *
 * Generation is the web's exact loop — start `publish:false`, poll every
 * 2 s for at most 90 ticks, `ready`/`error` both terminal, transient poll
 * failures swallowed — via `GeneratePoller`. The `sides:generated` socket
 * nudge refetches the list the moment a generation finishes; while any
 * list row is still `generating`, the 5 s re-fetch stays as the fallback
 * for a missed push, exactly as on the web.
 *
 * Rights are checked HERE, not only on screen: every posting and download
 * handler runs through [refuses], which keeps the control visible and turns
 * a refused press into a request to an admin.
 */
class SidesViewModel(
    override val repository: SidesRepository,
    override val transfer: SidesTransfer,
    private val resolveViewer: () -> SidesViewer,
    private val rights: RightsRequestBus? = null,
) : ZillitViewModel<SidesUiState, SidesEvent, SidesEffect>(SidesUiState()), SidesStore {

    private val pdf = PdfFlow(this)
    private val list = ListFlow(this, pdf)
    private val scripts = ScriptsFlow(this, pdf)
    private val generate = GenerateFlow(this, pdf, onPublished = list::load)
    private val auto = AutoFlow(this, pdf, onPublished = list::load)
    private val dialogs = DialogFlow(this, list, scripts, auto)

    private var listening = false

    fun start() {
        setState { copy(viewer = resolveViewer()) }
        refresh()
        listenOnce()
    }

    /**
     * Refetches the list when the backend announces a finished generation.
     * Guarded so a second Start (the window reopening) does not stack
     * collectors.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes.collect { if (currentState.destination == SidesDestination.Sides) list.load() }
        }
    }

    // ---------------------------------------------------------------- store

    override val current: SidesUiState get() = currentState
    override fun update(reducer: SidesUiState.() -> SidesUiState) = setState(reducer)
    override fun effect(effect: SidesEffect) = sendEffect(effect)
    override fun runTask(block: suspend CoroutineScope.() -> Unit): Job = launch(block)

    override fun refuses(kind: RightsKind): Boolean {
        val viewer = currentState.viewer
        val granted = when (kind) {
            RightsKind.Post -> viewer.mayPost
            RightsKind.Download -> viewer.mayDownload
        }
        if (granted) return false
        rights?.ask(SidesViewer.MODULE_LABEL, kind)
        failed(rightsRefusalMessage(SidesViewer.MODULE_LABEL, kind, rights != null))
        return true
    }

    private fun refresh() {
        when (currentState.destination) {
            SidesDestination.Sides -> list.load()
            SidesDestination.Scripts -> scripts.load()
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out: one line per act.
    override fun onEvent(event: SidesEvent) {
        when (event) {
            is SidesEvent.Open -> {
                setState { copy(destination = event.destination, generate = null) }
                refresh()
            }
            SidesEvent.Refresh -> refresh()
            SidesEvent.DismissError -> setState { copy(error = null) }

            is SidesEvent.SetLayout -> list.setLayout(event.layout)
            SidesEvent.ToggleHistory -> list.toggleHistory()
            is SidesEvent.HistorySearch -> list.searchHistory(event.query)
            SidesEvent.OpenAutogenerate -> list.activeScriptForAutogenerate()?.let { scriptId ->
                auto.open(scriptId, currentState.list.latestScheduleId)
            }
            SidesEvent.OpenGenerate -> if (list.mayOpenGenerate()) generate.open()
            is SidesEvent.ViewSides -> list.view(event.record)
            is SidesEvent.DownloadSides -> list.download(event.record)
            is SidesEvent.AskDeleteSides -> list.askDelete(event.record)

            SidesEvent.AskAddScript -> scripts.askAdd()
            is SidesEvent.ReplaceScript -> scripts.replace(event.script)
            is SidesEvent.ViewVersion -> scripts.view(event.script, event.version)
            is SidesEvent.DownloadVersion -> scripts.download(event.script, event.version)
            is SidesEvent.AskDeleteScript -> scripts.askDelete(event.script)
            is SidesEvent.VersionMenu -> scripts.versionMenu(event.scriptId)
            is SidesEvent.TogglePages -> scripts.togglePages(event.scriptId)
            is SidesEvent.PageSearch -> scripts.searchPages(event.scriptId, event.query)
            is SidesEvent.AskAddPage -> scripts.askAddPage(event.scriptId)
            is SidesEvent.AskEditPage -> scripts.askEditPage(event.scriptId, event.page)
            is SidesEvent.ViewPage -> scripts.viewPage(event.page)
            is SidesEvent.AskDeletePage -> scripts.askDeletePage(event.page)

            is SidesEvent.DialogTitle -> dialogs.title(event.value)
            is SidesEvent.DialogSceneNumber -> dialogs.sceneNumber(event.value)
            is SidesEvent.DialogColor -> dialogs.color(event.value)
            is SidesEvent.DialogDescription -> dialogs.description(event.value)
            SidesEvent.DialogPickFile -> dialogs.pickFile()
            is SidesEvent.DialogFileDropped -> dialogs.fileChosen(event.file)
            SidesEvent.DialogClearFile -> dialogs.clearFile()
            SidesEvent.DialogSubmit -> dialogs.submit()
            SidesEvent.DialogDismiss -> dialogs.dismiss()
            is SidesEvent.FilePicked -> filePicked(event)

            is SidesEvent.GenPickScript -> generate.pickScript(event.scriptId)
            is SidesEvent.GenAddScript -> generate.addScript(event.scriptId)
            is SidesEvent.GenRemoveScript -> generate.removeScript(event.scriptId)
            is SidesEvent.GenToggleVersionOpen -> generate.toggleVersionOpen(event.versionId)
            is SidesEvent.GenTogglePageOpen -> generate.togglePageOpen(event.pageId)
            is SidesEvent.GenToggleScene -> generate.toggleScene(event.versionId, event.sceneNumber)
            is SidesEvent.GenSetScenes -> generate.setScenes(event.versionId, event.sceneNumbers)
            is SidesEvent.GenTogglePageScene -> generate.togglePageScene(event.pageId, event.sceneNumber)
            is SidesEvent.GenSetPageScenes -> generate.setPageScenes(event.pageId, event.sceneNumbers)
            is SidesEvent.GenToggleWholePage -> generate.toggleWholePage(event.pageId)
            is SidesEvent.GenRearrange -> generate.rearrange(event.on)
            is SidesEvent.GenOrder -> generate.order(event.order)
            is SidesEvent.GenDisplayMode -> generate.displayMode(event.mode)
            is SidesEvent.GenTitle -> generate.title(event.title)
            SidesEvent.GenSubmit -> generate.submit()
            SidesEvent.GenClose -> generate.close()
            SidesEvent.GenBackToForm -> generate.backToForm()
            SidesEvent.GenView -> generate.view()
            SidesEvent.GenDownload -> generate.download()
            SidesEvent.GenPublish -> generate.publish()

            is SidesEvent.AutoSelectCallSheet -> auto.selectCallSheet(event.id)
            is SidesEvent.AutoSelectSchedule -> auto.selectSchedule(event.id)
            is SidesEvent.AutoViewCallSheet -> auto.viewCallSheet(event.sheet)
            is SidesEvent.AutoViewSchedule -> auto.viewSchedule(event.schedule)
            is SidesEvent.AutoAskDeleteCallSheet -> auto.askDeleteCallSheet(event.sheet)
            is SidesEvent.AutoAskDeleteSchedule -> auto.askDeleteSchedule(event.schedule)
            is SidesEvent.AutoUpload -> auto.upload(event.kind)
            is SidesEvent.AutoRearrange -> auto.rearrange(event.on)
            is SidesEvent.AutoOrder -> auto.order(event.order)
            is SidesEvent.AutoOrderText -> auto.orderText(event.text)
            is SidesEvent.AutoDisplayMode -> auto.displayMode(event.mode)
            SidesEvent.AutoGenerate -> auto.generate()
            SidesEvent.AutoClose -> auto.close()
            SidesEvent.AutoView -> auto.view()
            SidesEvent.AutoDownload -> auto.download()
            SidesEvent.AutoPublish -> auto.publish()

            SidesEvent.ClosePdf -> pdf.close()
            SidesEvent.PdfOpenExternal -> pdf.openExternal()
            SidesEvent.PdfDownload -> pdf.downloadShown()
        }
    }

    private fun filePicked(event: SidesEvent.FilePicked) {
        val file = event.file ?: return
        when (val purpose = event.purpose) {
            PickPurpose.Dialog -> dialogs.fileChosen(file)
            is PickPurpose.ReplaceScript -> scripts.replaceWith(purpose.script, file)
        }
    }
}
