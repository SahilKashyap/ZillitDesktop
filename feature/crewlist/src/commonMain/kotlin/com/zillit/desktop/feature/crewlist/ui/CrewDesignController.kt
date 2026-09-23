package com.zillit.desktop.feature.crewlist.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.crewlist.data.CrewCanvasMessage
import com.zillit.desktop.feature.crewlist.data.CrewDesignDocument
import com.zillit.desktop.feature.crewlist.domain.CrewListRepository
import com.zillit.desktop.feature.crewlist.domain.HeaderLayout
import com.zillit.desktop.feature.crewlist.domain.LayoutHistory
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Customise & Preview (ZL-19725): the Design canvas — the backend's stacked
 * render with the arranger layered on — and the Preview canvas, the real render
 * the PDF will be, re-fetched every time the tab is entered.
 *
 * What changes the canvases, and how, follows the web: the arrangement, the
 * logo size and the table lines recompose the Design document locally (no
 * round trip); a nudge is already on the page and reloads nothing; the logo's
 * alignment and saved company details need the backend to render again.
 */
internal class CrewDesignController(
    private val store: CrewStore,
    private val repository: CrewListRepository,
) {
    private var nextKey = 0
    private var designGeneration = 0
    private var previewGeneration = 0

    @Suppress("CyclomaticComplexMethod") // Event fan-out: one line per act.
    fun onEvent(event: CrewListEvent.Design) {
        when (event) {
            CrewListEvent.Design.Open -> open()
            CrewListEvent.Design.Close -> store.update { copy(customise = null) }
            is CrewListEvent.Design.Switch -> switch(event.mode)
            CrewListEvent.Design.Undo -> changeHistory { undo() }
            CrewListEvent.Design.Redo -> changeHistory { redo() }
            CrewListEvent.Design.Reset -> changeHistory { reset() }
            is CrewListEvent.Design.AlignLogo -> changeLayout { copy(logo = event.align) }
            is CrewListEvent.Design.DragLogoSize -> store.update {
                copy(customise = customise?.copy(logoSizeDraft = event.size))
            }
            is CrewListEvent.Design.CommitLogoSize -> changeLayout { copy(logoSize = event.size) }
            is CrewListEvent.Design.ShowInternalLines -> {
                if (!mayDesign()) return
                store.update {
                    copy(hideInternalLines = !event.show, customise = customise?.copy(previewDirty = true))
                }
                recomposeDesign()
            }
            is CrewListEvent.Design.CanvasMessage -> onCanvas(event.text)
            CrewListEvent.Design.Retry -> retry()
        }
    }

    /**
     * Opens on Design for those who may shape the document. Everyone else sees
     * Preview — the web shows them the stacked Design render without its
     * grips, which is not what their PDF looks like.
     */
    private fun open() {
        val canDesign = store.state.viewer.canPost
        store.update {
            copy(
                customise = CustomiseState(
                    mode = if (canDesign) CanvasMode.Design else CanvasMode.Preview,
                    designLoading = canDesign,
                    previewLoading = !canDesign,
                    logoSizeDraft = layout.current.logoSize,
                ),
            )
        }
        if (canDesign) fetchDesign() else fetchPreview()
    }

    private fun switch(mode: CanvasMode) {
        val customise = store.state.customise ?: return
        if (!store.state.viewer.canPost || customise.mode == mode) return
        when (mode) {
            CanvasMode.Preview -> {
                store.update { copy(customise = customise.copy(mode = mode, previewLoading = true)) }
                fetchPreview()
            }
            CanvasMode.Design -> {
                store.update { copy(customise = customise.copy(mode = mode)) }
                // The canvas showed the Preview page meanwhile; the arranger comes
                // back seeded with every nudge made so far.
                recomposeDesign()
            }
        }
    }

    /** Company details were saved: the letterhead the backend renders has changed. */
    fun companyChanged() {
        val customise = store.state.customise ?: return
        store.update { copy(customise = customise.copy(previewDirty = true)) }
        when (customise.mode) {
            CanvasMode.Design -> fetchDesign()
            CanvasMode.Preview -> fetchPreview()
        }
    }

    private fun retry() {
        when (store.state.customise?.mode) {
            CanvasMode.Design -> fetchDesign()
            CanvasMode.Preview -> fetchPreview()
            null -> Unit
        }
    }

    private fun mayDesign(): Boolean = store.state.customise != null && store.state.viewer.canPost

    private fun changeLayout(change: HeaderLayout.() -> HeaderLayout) =
        changeHistory { push(current.change()) }

    /**
     * Every letterhead change goes through here, so the history, the slider,
     * the Preview dot and the canvas never disagree.
     */
    private fun changeHistory(change: LayoutHistory.() -> LayoutHistory) {
        if (!mayDesign()) return
        val before = store.state.layout.current
        val history = store.state.layout.change()
        val after = history.current
        store.update {
            copy(
                layout = history,
                customise = customise?.copy(previewDirty = true, logoSizeDraft = after.logoSize),
            )
        }
        when {
            after.logo != before.logo -> fetchDesign()
            after.order != before.order || after.logoSize != before.logoSize -> recomposeDesign()
            // An undone or redone nudge is not on the page — reseed it.
            after.offsets != before.offsets && !fromCanvas -> recomposeDesign()
        }
    }

    /** Set while applying a message from the page, whose own layout is already current. */
    private var fromCanvas = false

    private fun onCanvas(text: String) {
        if (!mayDesign()) return
        val message = CrewCanvasMessage.parse(text) ?: return
        fromCanvas = true
        try {
            when (message) {
                is CrewCanvasMessage.Layout -> changeLayout { copy(order = message.order) }
                is CrewCanvasMessage.Offset ->
                    changeLayout { copy(offsets = offsets + (message.section to message.offset)) }
            }
        } finally {
            fromCanvas = false
        }
    }

    /** The stacked render — fetched on open, on a logo alignment change, and after company details are saved. */
    private fun fetchDesign() {
        val generation = ++designGeneration
        store.update { copy(customise = customise?.copy(designLoading = true, failure = null)) }
        val request = store.state.documentRequest(stacked = true)
        store.launch {
            val outcome = repository.previewHtml(request)
            if (generation != designGeneration || store.state.customise == null) return@launch
            when (outcome) {
                is ZillitResult.Success -> {
                    store.update {
                        copy(customise = customise?.copy(designSource = outcome.data, designLoading = false))
                    }
                    recomposeDesign()
                }
                is ZillitResult.Failure -> {
                    val message = outcome.error.readable().ifBlank { str(S.desktop_failed_to_load_preview) }
                    store.update { copy(customise = customise?.copy(designLoading = false, failure = message)) }
                    store.toast(message, CrewListEffect.Tone.Error)
                }
            }
        }
    }

    /** Arranger layered onto the stacked render, as a fresh document — the canvas reloads. */
    private fun recomposeDesign() {
        val state = store.state
        val customise = state.customise ?: return
        if (customise.designSource.isEmpty() || !state.viewer.canPost) return
        val html = CrewDesignDocument.compose(customise.designSource, state.layout.current, state.hideInternalLines)
        // Taken outside the reducer, which may run more than once.
        val document = CanvasDocument(++nextKey, html)
        store.update { copy(customise = this.customise?.copy(design = document)) }
    }

    /** The real render, every time the tab is entered — the web caches nothing here either. */
    private fun fetchPreview() {
        val generation = ++previewGeneration
        store.update { copy(customise = customise?.copy(previewLoading = true, failure = null)) }
        val request = store.state.documentRequest()
        store.launch {
            val outcome = repository.previewHtml(request)
            if (generation != previewGeneration || store.state.customise == null) return@launch
            when (outcome) {
                is ZillitResult.Success -> {
                    val document = CanvasDocument(++nextKey, outcome.data)
                    store.update {
                        val rendered = customise?.copy(preview = document, previewLoading = false, previewDirty = false)
                        copy(customise = rendered)
                    }
                }
                is ZillitResult.Failure -> {
                    val message = outcome.error.readable().ifBlank { str(S.desktop_failed_to_load_preview) }
                    store.update { copy(customise = customise?.copy(previewLoading = false, failure = message)) }
                    store.toast(message, CrewListEffect.Tone.Error)
                }
            }
        }
    }
}
