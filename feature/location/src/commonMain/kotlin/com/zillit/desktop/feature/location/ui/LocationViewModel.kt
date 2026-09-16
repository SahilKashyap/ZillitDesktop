@file:Suppress("TooManyFunctions") // One handler per user act.

package com.zillit.desktop.feature.location.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.location.domain.GroupBy
import com.zillit.desktop.feature.location.domain.Folders
import com.zillit.desktop.feature.location.domain.LocationBadges
import com.zillit.desktop.feature.location.domain.LocationFolder
import com.zillit.desktop.feature.location.domain.LocationInfo
import com.zillit.desktop.feature.location.domain.LocationMedia
import com.zillit.desktop.feature.location.domain.LocationPick
import com.zillit.desktop.feature.location.domain.LocationRepository
import com.zillit.desktop.feature.location.domain.LocationStatus
import com.zillit.desktop.feature.location.domain.LocationTransfer
import com.zillit.desktop.feature.location.domain.LocationUnread
import com.zillit.desktop.feature.location.domain.LocationViewer
import com.zillit.desktop.feature.location.domain.MediaAttachment
import kotlinx.coroutines.flow.conflate

/**
 * The location library: three shortlists of folders, a gallery per folder,
 * uploads with the place's details, moves, deletes, and a PDF of a pick.
 */
class LocationViewModel(
    private val repository: LocationRepository,
    private val transfer: LocationTransfer,
    private val resolveViewer: () -> LocationViewer,
    private val nowMillis: () -> Long,
    private val badges: LocationBadges = LocationBadges.None,
) : ZillitViewModel<LocationUiState, LocationEvent, LocationEffect>(LocationUiState()) {

    fun start() {
        val viewer = resolveViewer()
        // Television productions group by episode by default, as the web does.
        setState { copy(viewer = viewer, groupBy = if (viewer.isTelevision) GroupBy.EpisodeNo else groupBy) }
        refresh()
        listenOnce()
    }

    /**
     * Reloads the open shortlist when the socket says another client
     * created, edited, or deleted a record — the web's
     * `location_created/updated/deleted` handlers re-run `getLocationList`.
     * Guarded so a second start (the window reopening) does not stack
     * collectors; `conflate()` folds a burst into one reload.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes.conflate().collect { refresh() }
        }
        launch {
            // The open thread only. Refreshed quietly — `loadDiscussion`
            // raises a spinner, which is right when opening a record and
            // wrong for every line that arrives after.
            repository.discussionRefreshes.conflate().collect { refreshDiscussion() }
        }
        launch {
            badges.leaves.collect { leaves ->
                setState { copy(unread = LocationUnread(leaves)) }
                // A comment landing on the open record is read as it lands —
                // the web re-reads on every line while the thread is on screen.
                val open = currentState.viewing?.id ?: return@collect
                if (LocationUnread(leaves).record(open) > 0) badges.readRecord(open)
            }
        }
    }

    /**
     * Re-reads the open thread without disturbing it.
     *
     * No spinner: the lines stay on screen and are replaced when the new ones
     * arrive. A failure leaves what is there — a dropped refresh must not
     * empty a discussion somebody is reading.
     */
    private fun refreshDiscussion() {
        val recordId = currentState.viewing?.id ?: return
        launch {
            val rows = repository.messages(recordId, nowMillis())
            if (currentState.viewing?.id != recordId) return@launch
            (rows as? ZillitResult.Success)?.data?.let { loaded ->
                setState { copy(discussion = loaded.reversed()) }
            }
        }
    }

    private var listening = false

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out: one line per act.
    override fun onEvent(event: LocationEvent) {
        when (event) {
            is LocationEvent.SelectStatus -> {
                setState { copy(status = event.status, gallery = null, browsing = null) }
                refresh()
            }
            is LocationEvent.SelectGroupBy -> setState { copy(groupBy = event.by) }
            is LocationEvent.Search -> setState { copy(query = event.query) }
            LocationEvent.Refresh -> refresh()
            LocationEvent.DismissError -> setState { copy(error = null) }
            is LocationEvent.OpenFolder -> openFolder(event.folder)
            is LocationEvent.OpenPick -> openGallery(event.pick)
            LocationEvent.Back -> setState { copy(gallery = null) }
            LocationEvent.CloseFolder -> {
                setState { copy(gallery = null, browsing = null) }
                refresh()
            }
            LocationEvent.LoadMore -> loadMore()
            LocationEvent.ToggleSelecting -> setState {
                copy(gallery = gallery?.copy(selecting = !gallery.selecting, selected = emptySet()))
            }
            is LocationEvent.ToggleSelect -> setState {
                copy(
                    gallery = gallery?.copy(
                        selected = if (event.id in gallery.selected) gallery.selected - event.id else gallery
                            .selected + event.id,
                    ),
                )
            }
            is LocationEvent.MoveSelected -> moveSelected(event.to)
            LocationEvent.DeleteSelected -> {
                val ids = state.value.gallery?.selected.orEmpty().toList()
                if (ids.isNotEmpty()) setState { copy(confirmDelete = ids) }
            }
            LocationEvent.ConfirmDelete -> deleteSelected()
            LocationEvent.CancelDelete -> setState { copy(confirmDelete = null) }
            LocationEvent.PdfSelected -> pdfSelected()
            is LocationEvent.View -> {
                setState { copy(viewing = event.record, discussion = emptyList(), discussionDraft = "") }
                // The record's thread on screen reads its comment rows.
                if (currentState.unread.record(event.record.id) > 0) badges.readRecord(event.record.id)
                loadDiscussion(event.record.id)
            }
            LocationEvent.CloseView -> setState {
                copy(viewing = null, discussion = emptyList(), discussionDraft = "")
            }
            is LocationEvent.DiscussionDraftChanged -> setState { copy(discussionDraft = event.text) }
            LocationEvent.SendDiscussion -> sendDiscussion()
            is LocationEvent.Download -> download(event.record)
            LocationEvent.PickFile -> guardPost { sendEffect(LocationEffect.PickFile) }
            is LocationEvent.FilePicked -> {
                if (!event.file.isImage && !event.file.isVideo) {
                    setState { copy(error = "Only images and videos are allowed") }
                } else {
                    setState { copy(editor = seededEditor().copy(file = event.file)) }
                }
            }
            LocationEvent.NewLink -> guardPost { setState { copy(editor = seededEditor()) } }
            is LocationEvent.Edit -> guardPost { setState { copy(editor = LocationEditor.from(event.record)) } }
            is LocationEvent.EditorChanged -> setState { copy(editor = event.editor) }
            LocationEvent.Save -> save()
            LocationEvent.CancelEdit -> setState { copy(editor = null) }
        }
    }

    /** A new record starts in the open gallery, or the open folder, if any. */
    private fun seededEditor(): LocationEditor {
        val s = state.value
        s.gallery?.pick?.let {
            return LocationEditor(location = it.location, sceneNumber = it.scene, episodes = it.episode)
        }
        val folder = s.browsing?.folder ?: return LocationEditor()
        return LocationEditor(
            location = folder.locations.singleOrNull().orEmpty(),
            sceneNumber = folder.sceneNumbers.singleOrNull().orEmpty(),
            episodes = folder.episodes.singleOrNull().orEmpty(),
        )
    }

    private fun refresh() {
        setState { copy(loading = true) }
        launch {
            val rows = repository.info(state.value.status).orError()
            setState { copy(loading = false, info = rows ?: info) }
            if (rows != null) sweepOrphans(rows)
        }
    }

    /**
     * Rows of the loaded list filed under places no folder shows are read —
     * a deleted or renamed location's rows would badge the tab for good.
     */
    private fun sweepOrphans(rows: List<LocationInfo>) {
        val s = currentState
        s.unread.orphans(s.status, rows.map { it.location })
            .map { LocationPick(it.location, it.scene, it.episode) }
            .distinct()
            .forEach { badges.readGallery(s.status, it) }
    }

    /**
     * The web's ZL-16395 rule: a folder holding one gallery opens it straight
     * away; one holding several shows their tiles first.
     */
    private fun openFolder(folder: LocationFolder) {
        val s = state.value
        val picks = Folders.picks(folder, s.groupBy, s.info)
        val only = picks.singleOrNull()
        if (only != null) {
            setState { copy(browsing = null) }
            openGallery(only)
        } else {
            setState { copy(browsing = OpenFolder(folder, s.groupBy, picks)) }
        }
    }

    /** [closeIfEmpty]: after a move or delete, a gallery with nothing left in it closes rather than sits blank. */
    private fun openGallery(pick: LocationPick, closeIfEmpty: Boolean = false) {
        val s = state.value
        setState { copy(gallery = OpenGallery(pick), loading = true) }
        // The gallery on screen is the folder read — Android's `markReadFolder`
        // on the library page, scoped to the place, scene and episode — plus
        // the place's rows under scenes no gallery lists any more, which
        // would otherwise badge the folder for good (see `strayScenes`).
        if (!closeIfEmpty) {
            if (s.unread.pick(s.status, pick) > 0) badges.readGallery(s.status, pick)
            val listed = s.info.filter { it.location.trim() == pick.location.trim() }
                .flatMap { it.sceneNumbers }
                .map(Folders::normaliseScene)
            s.unread.strayScenes(s.status, pick.location, listed).forEach { stray ->
                badges.readGallery(s.status, LocationPick(pick.location, stray))
            }
        }
        launch {
            val rows = repository.media(
                status = s.status,
                location = pick.location,
                sceneNumber = pick.scene,
                episode = pick.episode,
                beforeMs = nowMillis(),
                next = false,
            ).orError()
            setState {
                val fetched = rows.orEmpty()
                copy(
                    loading = false,
                    gallery = if (closeIfEmpty && rows != null && fetched.isEmpty()) {
                        null
                    } else {
                        gallery?.copy(records = fetched, exhausted = fetched.size < PAGE)
                    },
                )
            }
        }
    }

    /**
     * The open record's thread.
     *
     * Newest-first on the wire, oldest-first on screen: a discussion reads
     * downwards. A failure costs the thread, not the record — the picture and
     * its details are what the reader opened.
     */
    private fun loadDiscussion(recordId: String) {
        setState { copy(discussionLoading = true) }
        launch {
            val rows = repository.messages(recordId, nowMillis())
            setState {
                copy(
                    discussionLoading = false,
                    discussion = (rows as? ZillitResult.Success)?.data?.reversed().orEmpty(),
                )
            }
        }
    }

    /** Posts the draft, then re-reads the thread so the line appears as saved. */
    private fun sendDiscussion() {
        val record = currentState.viewing ?: return
        val body = currentState.discussionDraft.trim()
        if (body.isEmpty() || currentState.discussionSending) return
        setState { copy(discussionSending = true) }
        launch {
            when (val answer = repository.sendMessage(record.id, body)) {
                is ZillitResult.Success -> {
                    setState { copy(discussionSending = false, discussionDraft = "") }
                    loadDiscussion(record.id)
                }

                is ZillitResult.Failure ->
                    setState { copy(discussionSending = false, error = answer.error.localised()) }
            }
        }
    }

    private fun loadMore() {
        val s = state.value
        val open = s.gallery ?: return
        if (open.loadingMore || open.exhausted) return
        val last = open.records.maxOfOrNull { it.createdMs } ?: return
        setState { copy(gallery = gallery?.copy(loadingMore = true)) }
        launch {
            val rows = repository.media(
                status = s.status,
                location = open.pick.location,
                sceneNumber = open.pick.scene,
                episode = open.pick.episode,
                beforeMs = last,
                next = true,
            ).orError()
            setState {
                val known = gallery?.records.orEmpty()
                val fresh = rows.orEmpty().filter { r -> known.none { it.id == r.id } }
                copy(gallery = gallery?.copy(records = known + fresh, loadingMore = false, exhausted = fresh.isEmpty()))
            }
        }
    }

    private fun moveSelected(to: LocationStatus) {
        val s = state.value
        val open = s.gallery ?: return
        val records = open.records.filter { it.id in open.selected }
        if (records.isEmpty()) return
        run("Moved to ${to.label}") { repository.move(records, s.status, to) }
    }

    private fun deleteSelected() {
        val s = state.value
        val ids = s.confirmDelete ?: return
        val open = s.gallery
        val uploaders = open?.records?.filter { it.id in ids }?.map { it.uploadedBy }.orEmpty()
        if (!s.viewer.mayDelete(uploaders)) {
            setState { copy(confirmDelete = null, error = "Only an admin can delete other people's records") }
            return
        }
        setState { copy(confirmDelete = null) }
        run("Deleted") { repository.delete(ids, s.status) }
    }

    private fun pdfSelected() {
        val open = state.value.gallery ?: return
        val ids = open.selected.toList()
        if (ids.isEmpty()) return
        setState { copy(busy = true) }
        launch {
            val outcome = when (val file = repository.pdf(ids, includeDetails = true)) {
                is ZillitResult.Failure -> file
                is ZillitResult.Success -> openAttachment(file.data, "location-${ids.size}.pdf")
            }
            when (outcome) {
                is ZillitResult.Failure -> setState { copy(busy = false, error = outcome.error.localised()) }
                is ZillitResult.Success -> {
                    setState { copy(busy = false) }
                    sendEffect(LocationEffect.Notice("PDF generated"))
                }
            }
        }
    }

    private fun download(record: LocationMedia) {
        if (!state.value.viewer.mayDownload) {
            setState { copy(error = "You do not have download rights for Location") }
            return
        }
        val attachment = record.attachment ?: run {
            setState { copy(error = "This record has no file to download") }
            return
        }
        setState { copy(busy = true) }
        launch {
            when (val outcome = openAttachment(attachment, attachment.name.ifBlank { "location" })) {
                is ZillitResult.Failure -> setState { copy(busy = false, error = outcome.error.localised()) }
                is ZillitResult.Success -> {
                    setState { copy(busy = false) }
                    sendEffect(LocationEffect.Notice("Saved to Downloads"))
                }
            }
        }
    }

    private suspend fun openAttachment(attachment: MediaAttachment, fallbackName: String): ZillitResult<Unit> =
        when (val bytes = transfer.fetch(attachment)) {
            is ZillitResult.Failure -> bytes
            is ZillitResult.Success -> transfer.saveAndOpen(attachment.name.ifBlank { fallbackName }, bytes.data)
        }

    /** The web's form checks, in its order; a shortlisted/rejected record needs a file or a link. */
    private fun saveProblem(editor: LocationEditor, status: LocationStatus): String? {
        val bare = editor.id == null && editor.file == null && editor.link.isBlank()
        return when {
            editor.location.isBlank() -> "A location name is required"
            bare && status != LocationStatus.Selected -> "A photo, video or link is required here"
            editor.episodes.isNotBlank() && !editor.episodes.matches(EPISODES) ->
                "Episodes are numbers separated by commas"
            else -> null
        }
    }

    private fun save() {
        val s = state.value
        val editor = s.editor ?: return
        val problem = saveProblem(editor, s.status)
        if (problem != null) {
            setState { copy(error = problem) }
            return
        }
        setState { copy(editor = editor.copy(saving = true), busy = true) }
        launch {
            val id = editor.id
            val outcome =
                if (id != null) repository.update(id, editor.toDraft(s.status)) else createNew(editor, s.status)
            when (outcome) {
                is ZillitResult.Failure -> setState {
                    copy(busy = false, editor = editor.copy(saving = false), error = outcome.error.localised())
                }
                is ZillitResult.Success -> {
                    setState { copy(busy = false, editor = null) }
                    sendEffect(LocationEffect.Notice(if (id == null) "Location added" else "Location updated"))
                    val open = state.value.gallery
                    if (open != null) openGallery(open.pick)
                    refresh()
                }
            }
        }
    }

    /** Upload first (if there is a file), then create the record pointing at it. */
    private suspend fun createNew(editor: LocationEditor, status: LocationStatus): ZillitResult<Unit> {
        val stored = editor.file?.let { file ->
            when (val up = transfer.upload(file)) {
                is ZillitResult.Failure -> return up
                is ZillitResult.Success -> up.data
            }
        }
        return repository.create(editor.toDraft(status), stored, null).map { }
    }

    private fun run(notice: String, block: suspend () -> ZillitResult<Unit>) {
        setState { copy(busy = true) }
        launch {
            when (val result = block()) {
                is ZillitResult.Failure -> setState { copy(busy = false, error = result.error.localised()) }
                is ZillitResult.Success -> {
                    setState { copy(busy = false, gallery = gallery?.copy(selected = emptySet(), selecting = false)) }
                    sendEffect(LocationEffect.Notice(notice))
                    val open = state.value.gallery
                    if (open != null) openGallery(open.pick, closeIfEmpty = true)
                    refresh()
                }
            }
        }
    }

    private inline fun guardPost(block: () -> Unit) {
        if (state.value.viewer
            .mayPost) block() else setState { copy(error = "You do not have posting rights for Location") }
    }

    private fun <T> ZillitResult<T>.orError(): T? = when (this) {
        is ZillitResult.Success -> data
        is ZillitResult.Failure -> {
            val message = this.error.localised()
            setState { copy(error = message) }
            null
        }
    }

    private fun <T> ZillitResult<T>.map(transform: (T) -> Unit): ZillitResult<Unit> = when (this) {
        is ZillitResult.Success -> ZillitResult.Success(transform(data))
        is ZillitResult.Failure -> ZillitResult.Failure(error as ZillitError)
    }

    private companion object {
        const val PAGE = 50
        val EPISODES = Regex("[0-9,\\s]+")
    }
}
