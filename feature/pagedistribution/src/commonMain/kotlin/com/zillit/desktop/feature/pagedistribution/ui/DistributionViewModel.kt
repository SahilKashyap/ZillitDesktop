@file:Suppress("TooManyFunctions") // One handler per user act.

package com.zillit.desktop.feature.pagedistribution.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.pagedistribution.data.dayMillis
import com.zillit.desktop.feature.pagedistribution.data.ymd
import com.zillit.desktop.feature.pagedistribution.domain.DistFolder
import com.zillit.desktop.feature.pagedistribution.domain.DistDocument
import com.zillit.desktop.feature.pagedistribution.domain.DistributionRepository
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTab
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTool
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTransfer
import com.zillit.desktop.feature.pagedistribution.domain.DistributionViewer
import com.zillit.desktop.feature.pagedistribution.domain.FolderKey
import com.zillit.desktop.feature.pagedistribution.domain.ListMode
import com.zillit.desktop.feature.pagedistribution.domain.PageColour
import com.zillit.desktop.feature.pagedistribution.domain.ReadAction
import com.zillit.desktop.feature.pagedistribution.domain.ScheduleType
import com.zillit.desktop.feature.pagedistribution.domain.TabKind
import kotlinx.datetime.TimeZone

/**
 * One of the three PDF distribution tools, driven by its [DistributionTool].
 *
 * The web's component in one place: tabs, lists and folders, the upload
 * dialog, the viewer, download, delete, tallies, move (D.O.D) and publish
 * to Document Distribution.
 */
class DistributionViewModel(
    private val tool: DistributionTool,
    private val repository: DistributionRepository,
    private val transfer: DistributionTransfer,
    private val resolveViewer: () -> DistributionViewer,
    private val nowMillis: () -> Long,
    /** Marks a list (or a folder) read — the web's `notification:read` emit. */
    private val onListViewed: suspend (module: String, segment: String) -> Unit = { _, _ -> },
) : ZillitViewModel<DistributionUiState, DistributionEvent, DistributionEffect>(DistributionUiState(tool)) {

    fun start() {
        setState { copy(viewer = resolveViewer()) }
        loadTab()
        listenOnce()
    }

    /**
     * Refetches what is on screen when the socket announces this tool's
     * uploads, replaces, moves or deletes from another client — the web
     * pages' own refetch handlers (`RenderScript.jsx:1415-1521`,
     * `ScheduleDistributionMain.jsx:1636-1717`, `DoD.jsx:1114-1154`) as a
     * targeted reload. Guarded so a second Start (the window reopening)
     * does not stack collectors.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes(tool).collect {
                val open = state.value.openFolder
                if (open == null) loadTab() else reloadOpenFolder(open)
            }
        }
    }

    private var listening = false

    /** First page again, keeping the folder open — the web refetches in place. */
    private fun reloadOpenFolder(open: OpenFolder) {
        val tab = state.value.activeTab
        launch {
            val rows = repository.folderDocuments(
                tab,
                open.folder.key,
                nowMillis(),
                next = false,
                mode = state.value.mode,
            ).orError()
            setState {
                copy(
                    openFolder = openFolder?.copy(
                        documents = rows ?: openFolder.documents,
                        exhausted = rows.isNullOrEmpty(),
                    ),
                )
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out: one line per act.
    override fun onEvent(event: DistributionEvent) {
        when (event) {
            is DistributionEvent.SelectTab -> {
                setState {
                    copy(
                        activeTabKey = event.key,
                        openFolder = null,
                        searchResults = null,
                        searchScene = "",
                        searchEpisode = "",
                        searchColour = null,
                    )
                }
                loadTab()
            }
            DistributionEvent.ToggleHistory -> {
                setState {
                    val flipped = if (mode == ListMode.Live) ListMode.History else ListMode.Live
                    copy(mode = flipped, openFolder = null, searchResults = null)
                }
                loadTab()
            }
            DistributionEvent.Refresh -> loadTab()
            is DistributionEvent.OpenFolder -> openFolder(event.key)
            DistributionEvent.CloseFolder -> {
                setState { copy(openFolder = null) }
                loadTab()
            }
            DistributionEvent.LoadMore -> loadMore()
            is DistributionEvent.SearchChanged -> setState {
                copy(searchScene = event.scene ?: searchScene, searchEpisode = event.episode ?: searchEpisode)
            }
            is DistributionEvent.SearchColour -> {
                setState { copy(searchColour = event.colour, searchScene = "", searchEpisode = "") }
                if (event.colour != null) runSearch() else clearSearch()
            }
            DistributionEvent.RunSearch -> runSearch()
            DistributionEvent.ClearSearch -> clearSearch()
            is DistributionEvent.PickPdf -> guardPost { sendEffect(DistributionEffect.PickPdf(event.replaces)) }
            is DistributionEvent.PdfPicked -> openUpload(event)
            is DistributionEvent.UploadChanged -> setState {
                copy(
                    upload = upload?.copy(
                        dateYmd = event.dateYmd ?: upload.dateYmd,
                        episode = event.episode ?: upload.episode,
                        sceneNumber = event.sceneNumber ?: upload.sceneNumber,
                        pageNumber = event.pageNumber ?: upload.pageNumber,
                        colour = event.colour ?: upload.colour,
                        scheduleType = event.scheduleType ?: upload.scheduleType,
                        name = event.name ?: upload.name,
                        nameFromPick = event.nameFromPick ?: (if (event.name != null) false else upload.nameFromPick),
                    ),
                )
            }
            DistributionEvent.SubmitUpload -> submitUpload()
            DistributionEvent.CancelUpload -> setState { copy(upload = null) }
            is DistributionEvent.View -> view(event.document)
            DistributionEvent.CloseViewer -> setState { copy(pdf = null) }
            is DistributionEvent.Download -> download(event.document)
            is DistributionEvent.Delete -> setState { copy(confirmDelete = event.document) }
            DistributionEvent.ConfirmDelete -> delete()
            DistributionEvent.CancelDelete -> setState { copy(confirmDelete = null) }
            is DistributionEvent.ShowCounts -> showCounts(event.document, event.downloads)
            DistributionEvent.CloseCounts -> setState { copy(counts = null) }
            is DistributionEvent.Move -> guardPost { setState { copy(move = MoveEditor(event.document)) } }
            is DistributionEvent.MoveTarget -> setState { copy(move = move?.copy(target = event.folder)) }
            DistributionEvent.ConfirmMove -> move()
            DistributionEvent.CancelMove -> setState { copy(move = null) }
            is DistributionEvent.Publish -> {
                if (state.value.viewer.mayPublish) {
                    setState { copy(confirmPublish = event.document) }
                } else {
                    setState { copy(error = "You do not have posting rights for Document Distribution") }
                }
            }
            DistributionEvent.ConfirmPublish -> publish()
            DistributionEvent.CancelPublish -> setState { copy(confirmPublish = null) }
            DistributionEvent.DismissError -> setState { copy(error = null) }
        }
    }

    // Lists ----------------------------------------------------------------

    private fun loadTab() {
        val tab = state.value.activeTab
        val mode = state.value.mode
        setState { copy(loading = true) }
        launch {
            when (tab.kind) {
                is TabKind.Single -> {
                    val rows = repository.documents(tab, mode).orError()
                    setState { copy(loading = false, documents = rows ?: documents) }
                    val segment = tab.badgeSegment
                    if (segment != null && mode == ListMode.Live) onListViewed(tab.badgeModule, segment)
                }
                is TabKind.Folders -> {
                    val rows = repository.folders(tab, mode).orError()
                    setState { copy(loading = false, folders = rows ?: folders) }
                }
            }
        }
    }

    private fun openFolder(key: String) {
        val tab = state.value.activeTab
        val folder = state.value.folders.firstOrNull { it.key == key }
            ?: state.value.searchResults?.firstOrNull { it.folderKey(tab) == key }?.let { doc ->
                DistFolder(
                    id = doc.id, key = key, createdMs = doc.createdMs, revisionDateMs = doc.revisionDateMs,
                    scheduleType = doc.scheduleType, colour = doc.colour, deleted = doc.deleted,
                )
            }
            ?: return
        setState { copy(openFolder = OpenFolder(folder), loading = true, searchResults = null) }
        launch {
            val rows = repository.folderDocuments(
                tab,
                key,
                nowMillis(),
                next = false,
                mode = state.value.mode,
            ).orError()
            setState {
                copy(
                    loading = false,
                    openFolder = openFolder?.copy(documents = rows.orEmpty(), exhausted = rows.isNullOrEmpty()),
                )
            }
            if (state.value.mode == ListMode.Live) onListViewed(tab.badgeModule, key)
        }
    }

    private fun loadMore() {
        val open = state.value.openFolder ?: return
        if (open.loadingMore || open.exhausted) return
        val last = open.documents.lastOrNull()?.createdMs ?: return
        val tab = state.value.activeTab
        setState { copy(openFolder = openFolder?.copy(loadingMore = true)) }
        launch {
            val rows = repository.folderDocuments(
                tab,
                open.folder.key,
                last,
                next = true,
                mode = state.value.mode,
            ).orError()
            setState {
                val known = openFolder?.documents.orEmpty()
                val fresh = rows.orEmpty().filter { row -> known.none { it.id == row.id } }
                copy(
                    openFolder = openFolder?.copy(
                        documents = known + fresh,
                        loadingMore = false,
                        exhausted = fresh.isEmpty(),
                    ),
                )
            }
        }
    }

    private fun runSearch() {
        val current = state.value
        val tab = current.activeTab
        if (tab.kind !is TabKind.Folders) return
        if (!current.isSearching) {
            clearSearch()
            return
        }
        setState { copy(loading = true) }
        launch {
            val rows = repository.search(
                tab,
                sceneNumber = current.searchScene.takeIf { it.isNotBlank() },
                episode = current.searchEpisode.takeIf { it.isNotBlank() },
                colour = current.searchColour,
                mode = current.mode,
            ).orError()
            setState { copy(loading = false, searchResults = rows ?: emptyList()) }
        }
    }

    private fun clearSearch() {
        setState { copy(searchResults = null, searchScene = "", searchEpisode = "", searchColour = null) }
        loadTab()
    }

    // Upload ---------------------------------------------------------------

    private fun openUpload(event: DistributionEvent.PdfPicked) {
        val current = state.value
        val tab = current.activeTab
        // A single-list tab with a document already is a REPLACE, as the web
        // decides for itself when the paperclip is used.
        val replaces = event.replaces
            ?: (tab.kind as? TabKind.Single)?.let { current.sortedDocuments.firstOrNull() }
        val folderKey = current.openFolder?.folder?.key
        setState {
            copy(
                upload = UploadEditor(
                    fileName = event.fileName,
                    bytes = event.bytes,
                    replaces = replaces?.id,
                    // Replacing keeps the old document's fields as the web seeds
                    // them; an upload from inside a folder starts in that folder.
                    episode = replaces?.episode.orEmpty(),
                    sceneNumber = replaces?.sceneNumber ?: folderKey.takeIf { !isDod }.orEmpty(),
                    pageNumber = replaces?.pageNumber.orEmpty(),
                    colour = replaces?.let { PageColour.fromHex(it.colour) } ?: PageColour.White,
                    scheduleType = replaces?.scheduleType ?: (tab.kind as? TabKind.Folders)
                        ?.takeIf { it.scheduleTypeChoice }?.let { ScheduleType.FullSchedulePages },
                    name = folderKey.takeIf { isDod }.orEmpty(),
                    nameFromPick = folderKey != null && isDod,
                ),
            )
        }
    }

    private fun submitUpload() {
        val current = state.value
        val editor = current.upload ?: return
        val tab = current.activeTab
        val problem = uploadProblem(tab, editor, current.viewer)
        if (problem != null) {
            setState { copy(error = problem) }
            return
        }
        setState { copy(upload = editor.copy(saving = true), busy = true) }
        launch {
            val stored = transfer.upload(tool.storagePath, editor.fileName, editor.bytes)
            val outcome = when (stored) {
                is ZillitResult.Failure -> stored
                is ZillitResult.Success -> repository.upload(tab, editor.toDraft(), stored.data, nowMillis())
            }
            when (outcome) {
                is ZillitResult.Failure -> setState {
                    copy(busy = false, upload = editor.copy(saving = false), error = outcome.error.localised())
                }
                is ZillitResult.Success -> {
                    setState { copy(busy = false, upload = null) }
                    val done = if (editor.replaces != null) "Document replaced" else "Document uploaded"
                    sendEffect(DistributionEffect.Notice(done))
                    val open = state.value.openFolder
                    if (open != null) openFolder(open.folder.key) else loadTab()
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount") // A validation ladder: one line per rule, first failure wins.
    private fun uploadProblem(tab: DistributionTab, editor: UploadEditor, viewer: DistributionViewer): String? {
        if (!viewer.mayPost) return "You do not have posting rights for ${tool.title}"
        if (!editor.fileName.endsWith(".pdf", ignoreCase = true)) return "Only PDF files are allowed"
        val kind = tab.kind
        val byName = kind is TabKind.Folders && kind.folderKey == FolderKey.Name
        val byScene = kind is TabKind.Folders && kind.folderKey == FolderKey.SceneNumber
        if (byName && editor.name.isBlank()) return "A folder name is required"
        if (byScene && editor.replaces == null) {
            kind as TabKind.Folders
            val scene = editor.sceneNumber.trim()
            if (scene.isEmpty()) return "A scene number is required"
            if (!scene.first().isDigit()) return "The scene number must start with a digit"
            if (scene.length > MAX_SCENE) return "The scene number is at most $MAX_SCENE characters"
            if (kind.scheduleTypeChoice && editor.scheduleType == null) return "Choose schedule pages or one line pages"
        }
        if (kind is TabKind.Single && viewer.isTelevision && editor.episode.isBlank()) {
            return "An episode number is required"
        }
        val badDate = editor.dateYmd.isNotBlank() && dayMillis(editor.dateYmd, TimeZone.currentSystemDefault()) == 0L
        return if (badDate) "The date must be YYYY-MM-DD" else null
    }

    // Documents ------------------------------------------------------------

    private fun view(document: DistDocument) {
        val tab = state.value.activeTab
        setState { copy(pdf = PdfView(document)) }
        launch {
            val fresh = repository.document(tab, document.id, ReadAction.View, state.value.mode).orError()
            val stored = fresh?.attachment ?: document.attachment
            if (stored == null) {
                setState { copy(pdf = null, error = "This document has no file") }
                return@launch
            }
            val pages = when (val bytes = transfer.fetch(stored)) {
                is ZillitResult.Failure -> bytes
                is ZillitResult.Success -> transfer.renderPages(bytes.data, VIEWER_WIDTH_PX)
            }
            when (pages) {
                is ZillitResult.Failure -> setState { copy(pdf = null, error = pages.error.localised()) }
                is ZillitResult.Success -> setState { copy(pdf = pdf?.copy(pages = pages.data, loading = false)) }
            }
        }
    }

    private fun download(document: DistDocument) {
        if (!state.value.viewer.mayDownload) {
            setState { copy(error = "You do not have download rights for ${tool.title}") }
            return
        }
        val tab = state.value.activeTab
        setState { copy(busy = true) }
        launch {
            val fresh = repository.document(tab, document.id, ReadAction.Download, state.value.mode).orError()
            val stored = fresh?.attachment ?: document.attachment
            val outcome = if (stored == null) {
                ZillitResult.Failure(ZillitError.Unknown("no file"))
            } else {
                when (val bytes = transfer.fetch(stored)) {
                    is ZillitResult.Failure -> bytes
                    is ZillitResult.Success -> transfer.saveAndOpen(stored.name.ifBlank { "document.pdf" }, bytes.data)
                }
            }
            when (outcome) {
                is ZillitResult.Failure -> setState { copy(busy = false, error = outcome.error.localised()) }
                is ZillitResult.Success -> {
                    setState { copy(busy = false) }
                    sendEffect(DistributionEffect.Notice("Saved to Downloads"))
                }
            }
        }
    }

    private fun delete() {
        val document = state.value.confirmDelete ?: return
        if (!state.value.viewer.mayDelete(document.createdBy)) {
            setState { copy(confirmDelete = null, error = "Only an admin or the uploader can delete this") }
            return
        }
        val tab = state.value.activeTab
        setState { copy(busy = true) }
        launch {
            when (val result = repository.delete(tab, document.id)) {
                is ZillitResult.Failure -> setState {
                    copy(busy = false, confirmDelete = null, error = result.error.localised())
                }
                is ZillitResult.Success -> {
                    setState {
                        copy(
                            busy = false,
                            confirmDelete = null,
                            documents = documents.filterNot { it.id == document.id },
                            openFolder = openFolder?.copy(
                                documents = openFolder.documents.filterNot { it.id == document.id },
                            ),
                            searchResults = searchResults?.filterNot { it.id == document.id },
                        )
                    }
                    sendEffect(DistributionEffect.Notice("Deleted"))
                }
            }
        }
    }

    private fun showCounts(document: DistDocument, downloads: Boolean) {
        val tab = state.value.activeTab
        setState { copy(counts = CountsView(document, downloads = downloads)) }
        launch {
            val rows = repository.counts(tab, document.id).orError()
            setState { copy(counts = counts?.copy(rows = rows.orEmpty(), loading = false)) }
        }
    }

    private fun move() {
        val editor = state.value.move ?: return
        val target = editor.target ?: return
        val tab = state.value.activeTab
        setState { copy(move = editor.copy(saving = true), busy = true) }
        launch {
            when (val result = repository.move(tab, editor.document.id, target.key)) {
                is ZillitResult.Failure -> setState {
                    copy(busy = false, move = editor.copy(saving = false), error = result.error.localised())
                }
                is ZillitResult.Success -> {
                    setState { copy(busy = false, move = null, openFolder = null) }
                    sendEffect(DistributionEffect.Notice("Moved to ${target.key}"))
                    loadTab()
                }
            }
        }
    }

    private fun publish() {
        val document = state.value.confirmPublish ?: return
        val tab = state.value.activeTab
        setState { copy(busy = true) }
        launch {
            // Re-read with no action, so the raw storage key travels — the
            // web does the same before publishing.
            val fresh = repository.document(tab, document.id, ReadAction.None, state.value.mode).orError() ?: document
            when (val result = repository.publish(tool, tab, fresh, ymd(nowMillis()))) {
                is ZillitResult.Failure -> setState {
                    copy(busy = false, confirmPublish = null, error = result.error.localised())
                }
                is ZillitResult.Success -> {
                    setState { copy(busy = false, confirmPublish = null) }
                    sendEffect(DistributionEffect.Notice("Published to Document Distribution"))
                }
            }
        }
    }

    private inline fun guardPost(block: () -> Unit) {
        if (state.value.viewer.mayPost) {
            block()
        } else {
            setState { copy(error = "You do not have posting rights for ${tool.title}") }
        }
    }

    private fun <T> ZillitResult<T>.orError(): T? = when (this) {
        is ZillitResult.Success -> data
        is ZillitResult.Failure -> {
            val message = this.error.localised()
            setState { copy(error = message) }
            null
        }
    }

    private companion object {
        const val VIEWER_WIDTH_PX = 1100
        const val MAX_SCENE = 15
    }
}

/** The folder a document files under — its scene number, or its D.O.D name. */
internal fun DistDocument.folderKey(tab: DistributionTab): String =
    when ((tab.kind as? TabKind.Folders)?.folderKey) {
        FolderKey.Name -> name
        else -> sceneNumber
    }
