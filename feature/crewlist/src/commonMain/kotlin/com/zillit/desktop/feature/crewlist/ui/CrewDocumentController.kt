package com.zillit.desktop.feature.crewlist.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.crewlist.domain.CrewListHost
import com.zillit.desktop.feature.crewlist.domain.CrewListPdf
import com.zillit.desktop.feature.crewlist.domain.CrewListRepository
import com.zillit.desktop.feature.crewlist.domain.CrewListViewer

/**
 * The PDF and its two destinations — the web's Generate PDF chooser,
 * `DocumentViewer`, `publishCrewList` and `useDistributeToDocDist`: render with
 * the current letterhead, edits and label choice, then view it, publish it to
 * Info, or file it in Document Distribution.
 *
 * Rights are checked here before anything renders — the web renders a PDF it
 * then refuses to publish — and again at each write.
 */
internal class CrewDocumentController(
    private val store: CrewStore,
    private val repository: CrewListRepository,
    private val host: CrewListHost,
    private val resolveViewer: () -> CrewListViewer,
) {
    /** The bytes the viewer drew, for its Download. */
    private var viewedBytes: ByteArray? = null

    @Suppress("CyclomaticComplexMethod") // Event fan-out: one line per act.
    fun onEvent(event: CrewListEvent.Document) {
        when (event) {
            CrewListEvent.Document.OpenChooser -> openChooser()
            CrewListEvent.Document.CloseChooser -> store.update { copy(chooserOpen = false) }
            is CrewListEvent.Document.HideExternalLabel -> store.update { copy(hideExternalLabel = event.hide) }
            is CrewListEvent.Document.Run -> run(event.action)
            CrewListEvent.Document.CloseViewer -> {
                viewedBytes = null
                store.update { copy(pdf = null, confirmPublish = false) }
            }
            CrewListEvent.Document.DownloadViewed -> downloadViewed()
            CrewListEvent.Document.AskPublishViewed -> if (mayPublish()) store.update { copy(confirmPublish = true) }
            CrewListEvent.Document.ConfirmPublish -> {
                store.update { copy(confirmPublish = false) }
                store.state.pdf?.pdf?.let(::publish)
            }
            CrewListEvent.Document.CancelPublish -> store.update { copy(confirmPublish = false) }
            CrewListEvent.Document.DistributeViewed -> store.state.pdf?.pdf?.let { pdf ->
                if (mayDistribute()) store.update { copy(distribution = DistributionPrompt(pdf)) }
            }
            CrewListEvent.Document.ConfirmDistribution -> distribute()
            CrewListEvent.Document.CancelDistribution -> {
                val reopen = store.state.distribution?.fromChooser == true
                store.update { copy(distribution = null, chooserOpen = chooserOpen || reopen) }
            }
            CrewListEvent.Document.DismissDistribution -> store.update { copy(distribution = null) }
        }
    }

    /** A new production: the last one's drawn PDF goes with it. */
    fun forget() {
        viewedBytes = null
    }

    private fun viewer(): CrewListViewer = resolveViewer().also { resolved -> store.update { copy(viewer = resolved) } }

    private fun openChooser() {
        val viewer = viewer()
        when {
            store.state.problems.isNotEmpty() -> store.toast(
                store.copy(
                    "crew_list_fix_errors_before_generating",
                    "Please fix the highlighted phone / country code errors before generating.",
                ),
                CrewListEffect.Tone.Error,
            )
            !viewer.mayGenerate ->
                store.toast("You don't have rights to generate the ${viewer.toolName}.", CrewListEffect.Tone.Error)
            else -> store.update { copy(chooserOpen = true) }
        }
    }

    /**
     * The chooser's actions. The web closes the chooser first so the loader is
     * not hidden behind it, renders the PDF, then views, publishes or files it.
     */
    private fun run(action: GenerateAction) {
        if (store.state.working != null) return
        val allowed = when (action) {
            GenerateAction.Publish -> mayPublish()
            GenerateAction.Distribute -> mayDistribute()
            GenerateAction.View, GenerateAction.Download -> true
        }
        if (!allowed) return
        store.update { copy(chooserOpen = false, working = CrewWork.Generating) }
        val request = store.state.documentRequest(hideExternalLabel = store.state.hideExternalLabel)
        store.launch {
            when (val generated = repository.generate(request)) {
                is ZillitResult.Failure -> {
                    store.update { copy(working = null) }
                    failed(generated.error, "Failed to generate PDF")
                }
                is ZillitResult.Success -> {
                    store.update { copy(working = null) }
                    val pdf = generated.data
                    when (action) {
                        GenerateAction.View -> view(pdf)
                        GenerateAction.Publish -> publish(pdf)
                        GenerateAction.Distribute ->
                            store.update { copy(distribution = DistributionPrompt(pdf, fromChooser = true)) }
                        GenerateAction.Download -> download(pdf)
                    }
                }
            }
        }
    }

    private fun view(pdf: CrewListPdf) {
        viewedBytes = null
        store.update { copy(pdf = PdfViewerState(pdf)) }
        store.launch {
            val pages = when (val fetched = host.fetchPdf(pdf)) {
                is ZillitResult.Failure -> fetched
                is ZillitResult.Success -> {
                    viewedBytes = fetched.data
                    host.renderPages(fetched.data, VIEWER_PAGE_WIDTH_PX)
                }
            }
            store.update {
                if (this.pdf?.pdf != pdf) return@update this
                when (pages) {
                    is ZillitResult.Success -> copy(pdf = PdfViewerState(pdf, pages.data, loading = false))
                    is ZillitResult.Failure ->
                        copy(pdf = PdfViewerState(pdf, loading = false, failed = pages.error.readable()))
                }
            }
        }
    }

    private fun downloadViewed() {
        val viewed = store.state.pdf ?: return
        val bytes = viewedBytes ?: return download(viewed.pdf)
        store.launch {
            when (val saved = host.savePdf(viewed.pdf.fileName, bytes)) {
                is ZillitResult.Success -> store.toast("Saved to Downloads.", CrewListEffect.Tone.Success)
                is ZillitResult.Failure -> failed(saved.error, NOT_SAVED)
            }
        }
    }

    private fun download(pdf: CrewListPdf) {
        store.launch {
            val saved = when (val fetched = host.fetchPdf(pdf)) {
                is ZillitResult.Failure -> fetched
                is ZillitResult.Success -> host.savePdf(pdf.fileName, fetched.data)
            }
            when (saved) {
                is ZillitResult.Success ->
                    store.toast("${resolveViewer().toolName} saved to Downloads.", CrewListEffect.Tone.Success)
                is ZillitResult.Failure -> failed(saved.error, NOT_SAVED)
            }
        }
    }

    /** Publishing needs the crew list's posting right AND Info's — it lands on the Info board. */
    private fun mayPublish(): Boolean {
        val viewer = viewer()
        return when {
            !viewer.canPost -> {
                val refusal = store.copy(
                    "crew_list_posting_right_lable",
                    "You don’t have posting rights to publish ‘{tool_name}’",
                )
                store.refuse(refusal, viewer.toolName)
                false
            }
            !viewer.canPostInfo -> {
                store.refuse(
                    store.copy(
                        "you_do_not_have_posting_rights_on_info",
                        "You don't have posting rights on info. " +
                            "Please request one of the admins to give you posting rights",
                    ),
                    INFO,
                )
                false
            }
            else -> true
        }
    }

    private fun mayDistribute(): Boolean {
        val viewer = viewer()
        if (!viewer.canDistribute) {
            store.refuse("You don't have permission to distribute to Document Distribution.", DOC_DISTRIBUTION)
        }
        return viewer.canDistribute
    }

    private fun publish(pdf: CrewListPdf) {
        if (store.state.working != null) return
        store.update { copy(working = CrewWork.Publishing) }
        val toolName = resolveViewer().toolName
        store.launch {
            when (val published = repository.publishToInfo(pdf, caption = toolName)) {
                is ZillitResult.Success -> {
                    viewedBytes = null
                    store.update { copy(working = null, pdf = null, chooserOpen = false) }
                    store.toast(
                        store.copy("CrewListPublished", "{tool_name} Published Successfully"),
                        CrewListEffect.Tone.Success,
                    )
                }
                is ZillitResult.Failure -> {
                    store.update { copy(working = null) }
                    store.toast(
                        published.error.readable().ifBlank { "The $toolName could not be published." },
                        CrewListEffect.Tone.Error,
                    )
                }
            }
        }
    }

    /**
     * Files the PDF once the confirmation is accepted. A failure from the
     * chooser's path reopens the chooser, as the web does, so the choice can be
     * changed without starting over.
     */
    private fun distribute() {
        val prompt = store.state.distribution ?: return
        if (prompt.stage != DistributionPrompt.Stage.Confirm || !mayDistribute()) return
        store.update { copy(distribution = prompt.copy(stage = DistributionPrompt.Stage.Sending)) }
        store.launch {
            when (val filed = host.distribute(prompt.pdf)) {
                is ZillitResult.Success ->
                    store.update { copy(distribution = prompt.copy(stage = DistributionPrompt.Stage.Done)) }
                is ZillitResult.Failure -> {
                    store.update { copy(distribution = null, chooserOpen = chooserOpen || prompt.fromChooser) }
                    val raw = filed.error.readable()
                    // A bare code is not shown to anyone; a sentence is.
                    val friendly = raw.takeIf { it.contains(' ') } ?: DISTRIBUTION_FAILED
                    store.toast(friendly, CrewListEffect.Tone.Error)
                }
            }
        }
    }

    private fun failed(error: ZillitError, fallback: String) =
        store.toast(error.readable().ifBlank { fallback }, CrewListEffect.Tone.Error)

    private companion object {
        const val NOT_SAVED = "The PDF could not be saved."
        const val INFO = "Info"
        const val DOC_DISTRIBUTION = "Document Distribution"
        const val DISTRIBUTION_FAILED = "Couldn't send to Document Distribution — please try again"

        /** Wide enough to read a crew table at the viewer's full width, sharp on a 2× screen. */
        const val VIEWER_PAGE_WIDTH_PX = 1600
    }
}
