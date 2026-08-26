@file:Suppress("TooManyFunctions") // One handler per user act.

package com.zillit.desktop.feature.sides.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.sides.domain.GeneratePlan
import com.zillit.desktop.feature.sides.domain.GeneratePoller
import com.zillit.desktop.feature.sides.domain.SidesRepository
import com.zillit.desktop.feature.sides.domain.SidesStatus
import com.zillit.desktop.feature.sides.domain.SidesTransfer
import com.zillit.desktop.feature.sides.domain.SidesViewer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Sides: scripts in, scene selection, server-side generation, review, publish.
 *
 * Generation is the web's exact loop — start `publish:false`, poll every 2 s
 * for at most 90 ticks, `ready`/`error` both terminal, transient poll
 * failures swallowed — via [GeneratePoller]. The `sides:generated` socket
 * nudge refetches the list the moment a generation finishes; while any
 * list row is still `generating`, the 5 s re-fetch stays as the fallback
 * for a missed push, exactly as on the web.
 */
class SidesViewModel(
    private val repository: SidesRepository,
    private val transfer: SidesTransfer,
    private val resolveViewer: () -> SidesViewer,
) : ZillitViewModel<SidesUiState, SidesEvent, SidesEffect>(SidesUiState()) {

    private var generatingWatch: Job? = null

    fun start() {
        setState { copy(viewer = resolveViewer()) }
        refresh()
        listenOnce()
    }

    /**
     * Refetches the visible list when the backend announces a finished
     * generation — the web's `sides:generated` handler
     * (`SidesPage.jsx:92-109`). Guarded so a second Start (the window
     * reopening) does not stack collectors.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes.collect { refresh() }
        }
    }

    private var listening = false

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out: one line per act.
    override fun onEvent(event: SidesEvent) {
        when (event) {
            is SidesEvent.Open -> {
                setState { copy(destination = event.destination) }
                refresh()
            }
            is SidesEvent.ShowHistory -> {
                setState { copy(showHistory = event.history) }
                refresh()
            }
            SidesEvent.Refresh -> refresh()
            SidesEvent.OpenGenerate -> openGenerate()
            is SidesEvent.PickScript -> pickScript(event.scriptId)
            is SidesEvent.PickVersion -> pickVersion(event.versionId)
            is SidesEvent.ToggleScene -> updateGenerate {
                copy(
                    selected = if (event.sceneNumber in selected) {
                        selected - event.sceneNumber
                    } else {
                        selected + event.sceneNumber
                    },
                )
            }
            is SidesEvent.TitleChanged -> updateGenerate { copy(title = event.title) }
            is SidesEvent.DisplayModeChanged -> updateGenerate { copy(displayMode = event.mode) }
            is SidesEvent.OrderChanged -> updateGenerate { copy(orderText = event.text) }
            SidesEvent.StartGenerate -> startGenerate()
            SidesEvent.CloseGenerate -> setState { copy(generate = null) }
            SidesEvent.PublishResult -> publishResult()
            is SidesEvent.ViewSides -> view(event.id, event.title)
            is SidesEvent.DownloadSides -> download(event.id)
            is SidesEvent.Publish -> publish(event.id)
            is SidesEvent.DeleteSides -> deleteSides(event.id)
            SidesEvent.ClosePdf -> setState { copy(pdf = null) }
            SidesEvent.UploadScript -> sendEffect(SidesEffect.PickScriptFile)
            is SidesEvent.ScriptPicked -> uploadScript(event.fileName, event.bytes)
            is SidesEvent.DeleteScript -> deleteScript(event.id)
            SidesEvent.DismissError -> setState { copy(error = null) }
        }
    }

    private fun updateGenerate(change: GenerateState.() -> GenerateState) {
        setState { copy(generate = generate?.change()) }
    }

    private fun refresh() {
        setState { copy(loading = true) }
        launch {
            when (state.value.destination) {
                SidesDestination.Sides -> {
                    val history = state.value.showHistory
                    when (val list = repository.sides(history = history)) {
                        is ZillitResult.Success -> setState {
                            if (history) copy(historyList = list.data) else copy(sidesList = list.data)
                        }
                        is ZillitResult.Failure -> setState { copy(error = list.error.localised()) }
                    }
                    watchGenerating()
                }
                SidesDestination.Scripts -> when (val list = repository.scripts()) {
                    is ZillitResult.Success -> setState { copy(scripts = list.data) }
                    is ZillitResult.Failure -> setState { copy(error = list.error.localised()) }
                }
            }
            setState { copy(loading = false) }
        }
    }

    /** The web's fallback poll: 5 s re-fetch while anything is generating. */
    private fun watchGenerating() {
        if (state.value.sidesList.none { it.status == SidesStatus.Generating }) return
        if (generatingWatch?.isActive == true) return
        generatingWatch = launch {
            delay(GENERATING_REFETCH_MS)
            if (state.value.destination == SidesDestination.Sides) refresh()
        }
    }

    private fun openGenerate() {
        setState { copy(generate = GenerateState(), busy = true) }
        launch {
            when (val scripts = repository.scripts()) {
                is ZillitResult.Success -> {
                    setState { copy(busy = false, generate = generate?.copy(scripts = scripts.data)) }
                    scripts.data.firstOrNull()?.let { pickScript(it.id) }
                }
                is ZillitResult.Failure -> setState {
                    copy(busy = false, generate = null, error = scripts.error.localised())
                }
            }
        }
    }

    private fun pickScript(scriptId: String) {
        updateGenerate {
            copy(
                scriptId = scriptId,
                versions = emptyList(),
                versionId = "",
                scenes = emptyList(),
                selected = emptySet(),
            )
        }
        launch {
            when (val versions = repository.versions(scriptId)) {
                is ZillitResult.Success -> {
                    updateGenerate { copy(versions = versions.data) }
                    versions.data.maxByOrNull { it.versionNumber }?.let { pickVersion(it.id) }
                }
                is ZillitResult.Failure -> setState { copy(error = versions.error.localised()) }
            }
        }
    }

    private fun pickVersion(versionId: String) {
        updateGenerate {
            copy(versionId = versionId, scenes = emptyList(), selected = emptySet(), scenesLoading = true)
        }
        launch {
            when (val scenes = repository.scenes(versionId)) {
                is ZillitResult.Success -> updateGenerate {
                    copy(scenes = scenes.data, scenesLoading = false)
                }
                is ZillitResult.Failure -> {
                    updateGenerate { copy(scenesLoading = false) }
                    setState { copy(error = scenes.error.localised()) }
                }
            }
        }
    }

    private fun startGenerate() {
        val form = state.value.generate ?: return
        if (!form.canStart) return
        val order = GeneratePlan.parseScenes(form.orderText)
        val plan = GeneratePlan(
            scriptId = form.scriptId,
            title = form.title.trim(),
            versionId = form.versionId,
            sceneNumbers = if (order.isEmpty()) form.selected.toList() else order,
            displayMode = form.displayMode,
            sceneOrder = order,
        )
        updateGenerate { copy(running = true, result = null) }
        launch {
            val poller = GeneratePoller(
                generate = repository::generate,
                get = repository::sidesById,
            )
            when (val outcome = poller.run(plan, onTick = { tick ->
                updateGenerate { copy(result = tick) }
            })) {
                is ZillitResult.Success -> {
                    updateGenerate { copy(running = false, result = outcome.data.sides) }
                    if (outcome.data.timedOut) {
                        sendEffect(SidesEffect.Notice("Still rendering — check Sides shortly"))
                    }
                    refresh()
                }
                is ZillitResult.Failure -> {
                    updateGenerate { copy(running = false) }
                    setState { copy(error = outcome.error.localised()) }
                }
            }
        }
    }

    private fun publishResult() {
        val id = state.value.generate?.result?.id ?: return
        updateGenerate { copy(publishing = true) }
        launch {
            when (val published = repository.publish(id)) {
                is ZillitResult.Success -> {
                    setState { copy(generate = null) }
                    sendEffect(SidesEffect.Notice("Published"))
                    refresh()
                }
                is ZillitResult.Failure -> {
                    updateGenerate { copy(publishing = false) }
                    setState { copy(error = published.error.localised()) }
                }
            }
        }
    }

    private fun view(id: String, title: String) {
        setState { copy(pdf = SidesPdfView(sidesId = id, title = title)) }
        launch {
            val url = when (val signed = repository.downloadUrl(id, countDownload = false)) {
                is ZillitResult.Success -> signed.data
                is ZillitResult.Failure -> {
                    setState { copy(pdf = null, error = signed.error.localised()) }
                    return@launch
                }
            }
            val bytes = when (val fetched = transfer.fetch(url)) {
                is ZillitResult.Success -> fetched.data
                is ZillitResult.Failure -> {
                    setState { copy(pdf = null, error = fetched.error.localised()) }
                    return@launch
                }
            }
            when (val pages = transfer.renderPages(bytes, PDF_RENDER_WIDTH)) {
                is ZillitResult.Success -> setState {
                    copy(pdf = pdf?.copy(loading = false, pages = pages.data))
                }
                is ZillitResult.Failure -> setState {
                    copy(pdf = null, error = pages.error.localised())
                }
            }
        }
    }

    private fun download(id: String) {
        if (!state.value.viewer.mayDownload) {
            sendEffect(SidesEffect.Notice("You do not have download rights on Sides"))
            return
        }
        launch {
            when (val signed = repository.downloadUrl(id, countDownload = true)) {
                is ZillitResult.Success -> sendEffect(SidesEffect.OpenUrl(signed.data))
                is ZillitResult.Failure -> setState { copy(error = signed.error.localised()) }
            }
        }
    }

    private fun publish(id: String) {
        setState { copy(busy = true) }
        launch {
            when (val published = repository.publish(id)) {
                is ZillitResult.Success -> {
                    setState { copy(busy = false) }
                    sendEffect(SidesEffect.Notice("Published"))
                    refresh()
                }
                is ZillitResult.Failure -> setState {
                    copy(busy = false, error = published.error.localised())
                }
            }
        }
    }

    private fun deleteSides(id: String) {
        setState { copy(busy = true) }
        launch {
            when (val deleted = repository.deleteSides(id)) {
                is ZillitResult.Success -> {
                    setState { copy(busy = false) }
                    refresh()
                }
                is ZillitResult.Failure -> setState {
                    copy(busy = false, error = deleted.error.localised())
                }
            }
        }
    }

    private fun uploadScript(fileName: String, bytes: ByteArray) {
        setState { copy(busy = true) }
        launch {
            val attachment = when (val stored = transfer.upload(fileName, bytes)) {
                is ZillitResult.Success -> stored.data
                is ZillitResult.Failure -> {
                    setState { copy(busy = false, error = stored.error.localised()) }
                    return@launch
                }
            }
            val title = fileName.substringBeforeLast('.').ifBlank { fileName }
            when (val created = repository.createScript(title, attachment)) {
                is ZillitResult.Success -> {
                    setState { copy(busy = false) }
                    sendEffect(SidesEffect.Notice("Script uploaded"))
                    refresh()
                }
                is ZillitResult.Failure -> setState {
                    copy(busy = false, error = created.error.localised())
                }
            }
        }
    }

    private fun deleteScript(id: String) {
        setState { copy(busy = true) }
        launch {
            when (val deleted = repository.deleteScript(id)) {
                is ZillitResult.Success -> {
                    setState { copy(busy = false) }
                    refresh()
                }
                is ZillitResult.Failure -> setState {
                    copy(busy = false, error = deleted.error.localised())
                }
            }
        }
    }

    private companion object {
        const val GENERATING_REFETCH_MS = 5_000L
        const val PDF_RENDER_WIDTH = 1000
    }
}
