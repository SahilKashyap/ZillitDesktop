@file:Suppress("TooManyFunctions") // One handler per user act.

package com.zillit.desktop.feature.location.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.location.domain.GroupBy
import com.zillit.desktop.feature.location.domain.Folders
import com.zillit.desktop.feature.location.domain.LocationBadges
import com.zillit.desktop.feature.location.domain.LatLng
import com.zillit.desktop.feature.location.domain.LocationFolder
import com.zillit.desktop.feature.location.domain.LocationInfo
import com.zillit.desktop.feature.location.domain.LocationMedia
import com.zillit.desktop.feature.location.domain.LocationPick
import com.zillit.desktop.feature.location.domain.LocationPin
import com.zillit.desktop.feature.location.domain.LocationRepository
import com.zillit.desktop.feature.location.domain.LocationStatus
import com.zillit.desktop.feature.location.domain.LocationTransfer
import com.zillit.desktop.feature.location.domain.LocationUnread
import com.zillit.desktop.feature.location.domain.LocationViewer
import com.zillit.desktop.feature.location.domain.MediaAttachment
import com.zillit.desktop.feature.location.domain.MovePlace
import com.zillit.desktop.feature.location.domain.PickedLocationFile
import kotlinx.coroutines.flow.conflate
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The location library: three shortlists of folders, a gallery per folder,
 * uploads with the place's details, moves, deletes, and a PDF of a pick.
 *
 * [fileCabinet] makes it the File Cabinet's read-only viewer of the same
 * library: every read asks for the cabinet's records, no socket or badge
 * seam is listened to or written, and every event that would change a
 * record is ignored here as well as hidden by the screen.
 */
class LocationViewModel(
    private val repository: LocationRepository,
    private val transfer: LocationTransfer,
    private val resolveViewer: () -> LocationViewer,
    private val nowMillis: () -> Long,
    private val badges: LocationBadges = LocationBadges.None,
    private val fileCabinet: Boolean = false,
    /**
     * A picture of the map around a pin.
     *
     * The web snapshots its live mini-map with html2canvas; this client has
     * no live map in a dialog, so the host fetches the same still the chat
     * location share and Recce use. It is shown in the form and then stored
     * as the record's `link_attachment`, so a pin costs one fetch, not two.
     * A host without a maps key answers null and the place is saved without
     * a picture.
     */
    private val mapSnapshot: suspend (LatLng) -> PickedLocationFile? = { null },
    /**
     * A picture of several pins at once — the map view of a tab.
     *
     * The same seam Recce draws its route picture with. A host without it
     * leaves the map view to its numbered list, which is still the whole
     * answer to "which places are pinned, and where".
     */
    private val pinsMap: suspend (pins: List<LatLng>) -> ByteArray? = { null },
) : ZillitViewModel<LocationUiState, LocationEvent, LocationEffect>(LocationUiState()) {

    fun start() {
        val viewer = resolveViewer()
        // Television productions group by episode by default, as the web does.
        setState {
            copy(
                viewer = viewer,
                groupBy = if (viewer.isTelevision) GroupBy.EpisodeNo else groupBy,
                // A cabinet window reopens on the folders, not on the last gallery.
                gallery = if (fileCabinet) null else gallery,
                browsing = if (fileCabinet) null else browsing,
                viewing = if (fileCabinet) null else viewing,
                error = if (fileCabinet) null else error,
            )
        }
        refresh()
        if (!fileCabinet) listenOnce()
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
        if (fileCabinet && event.writes()) return
        when (event) {
            is LocationEvent.SelectStatus -> {
                setState { copy(status = event.status, gallery = null, browsing = null) }
                refresh()
                // The map view belongs to a tab; the other tab's pins are not
                // this one's, so they are re-read rather than left on screen.
                if (currentState.map.open) loadPins()
            }
            is LocationEvent.SelectGroupBy -> setState { copy(groupBy = event.by) }
            is LocationEvent.Search -> setState { copy(query = event.query) }
            LocationEvent.Refresh -> refresh()
            LocationEvent.DismissError -> setState { copy(error = null) }
            LocationEvent.ToggleMapView -> toggleMapView()
            is LocationEvent.OpenPin -> openPin(event.pin)
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
            is LocationEvent.MovePlaceChanged -> movePlaceChanged(event.place)
            LocationEvent.ConfirmMove -> confirmMove()
            LocationEvent.CancelMove -> setState { copy(moving = null) }
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
            is LocationEvent.OpenUrl -> sendEffect(LocationEffect.OpenUrl(event.url))
            LocationEvent.PickFile -> guardPost { sendEffect(LocationEffect.PickFile) }
            is LocationEvent.FilePicked -> {
                if (!event.file.isImage && !event.file.isVideo) {
                    setState { copy(error = str(S.only_video_image_allowed)) }
                } else {
                    setState { copy(editor = seededEditor().copy(file = event.file)) }
                }
            }
            LocationEvent.NewLink -> guardPost { setState { copy(editor = seededEditor()) } }
            is LocationEvent.Edit -> guardPost { setState { copy(editor = LocationEditor.from(event.record)) } }
            is LocationEvent.EditorChanged -> editorChanged(event.editor)
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

    /** Swaps the folder grid for the map of this tab's pinned locations. */
    private fun toggleMapView() {
        val opening = !currentState.map.open
        setState { copy(map = map.copy(open = opening)) }
        if (opening) loadPins()
    }

    private fun loadPins() {
        val status = currentState.status
        setState { copy(map = map.copy(loading = true, picture = null)) }
        launch {
            val pins = repository.mapPins(status).orError().orEmpty()
            // A tab switched while this ran; those pins are the other tab's.
            if (currentState.status != status) return@launch
            setState { copy(map = map.copy(loading = false, pins = pins)) }
            val picture = runCatching { pinsMap(pins.map { it.pin }) }.getOrNull()
            setState {
                if (status != this.status || map.pins !== pins) this else copy(map = map.copy(picture = picture))
            }
        }
    }

    /**
     * A pin opens its folder — the web's View on a shoot location.
     *
     * The pin names its own shortlist, which need not be the open one, so the
     * tab moves with it before the gallery is read.
     */
    private fun openPin(pin: LocationPin) {
        if (pin.status != currentState.status) {
            setState { copy(status = pin.status, browsing = null) }
            refresh()
        }
        setState { copy(map = map.copy(open = false)) }
        openGallery(LocationPick(pin.location, pin.sceneNumber))
    }

    private fun refresh() {
        setState { copy(loading = true) }
        launch {
            val rows = repository.info(state.value.status, fileCabinet).orError()
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
            .forEach { badges.readGallery(s.status, it, sweep = true) }
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
        // The gallery on screen is the folder read — iOS `readToolBagdes`,
        // scoped to the place, scene and episode and to the created/updated
        // rows, so each photo's comments stay on its own dot — plus the
        // place's rows under scenes no gallery lists any more, which would
        // otherwise badge the folder for good (see `strayScenes`).
        if (!closeIfEmpty) {
            if (s.unread.galleryReads(s.status, pick) > 0) badges.readGallery(s.status, pick)
            val listed = s.info.filter { it.location.trim() == pick.location.trim() }
                .flatMap { it.sceneNumbers }
                .map(Folders::normaliseScene)
            s.unread.strayScenes(s.status, pick.location, listed).forEach { stray ->
                badges.readGallery(s.status, LocationPick(pick.location, stray), sweep = true)
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
                fileCabinet = fileCabinet,
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
                fileCabinet = fileCabinet,
            ).orError()
            setState {
                val known = gallery?.records.orEmpty()
                val fresh = rows.orEmpty().filter { r -> known.none { it.id == r.id } }
                copy(gallery = gallery?.copy(records = known + fresh, loadingMore = false, exhausted = fresh.isEmpty()))
            }
        }
    }

    /**
     * Moving the ticked records.
     *
     * A move to Shortlist or Final asks where the location is first: the Map
     * tool draws the shortlisted and final places, and a move otherwise
     * leaves them behind, since the server drops `link`, `location` and
     * `link_attachment` on a move (web PR #6521). Picking is optional — the
     * form's Move button moves with whatever it has.
     */
    private fun moveSelected(to: LocationStatus) {
        val s = state.value
        val open = s.gallery ?: return
        if (open.records.none { it.id in open.selected }) return
        if (to in PLACE_ON_MOVE) {
            // The folder's own pin, if one of its records already has one:
            // the Move form opens on where the location is, not on nothing.
            val known = open.records.firstNotNullOfOrNull { it.pin }
            setState { copy(moving = MoveRequest(to, PlaceEditor(pin = known, address = open.records
                .firstOrNull { it.pin != null }?.address.orEmpty()))) }
            if (known != null) fetchMapForMove(known)
            return
        }
        moveNow(to, null)
    }

    private fun movePlaceChanged(place: PlaceEditor) {
        val request = currentState.moving ?: return
        val moved = place.pin != request.place.pin
        setState {
            copy(
                moving = request.copy(
                    place = place.copy(
                        moved = request.place.moved || moved,
                        map = if (moved) null else request.place.map,
                        mapKnown = if (moved) place.pin == null else request.place.mapKnown,
                    ),
                ),
            )
        }
        if (moved) place.pin?.let(::fetchMapForMove)
    }

    private fun fetchMapForMove(pin: LatLng) {
        launch {
            val picture = runCatching { mapSnapshot(pin) }.getOrNull()
            setState {
                val request = moving ?: return@setState this
                if (request.place.pin != pin) return@setState this
                copy(moving = request.copy(place = request.place.copy(map = picture?.bytes, mapKnown = true)))
            }
        }
    }

    private fun confirmMove() {
        val request = currentState.moving ?: return
        setState { copy(moving = null) }
        moveNow(request.to, request.place.takeIf { it.moved && it.hasPin })
    }

    /** The move itself, with a place when the Move form picked one. */
    private fun moveNow(to: LocationStatus, place: PlaceEditor?) {
        val s = state.value
        val open = s.gallery ?: return
        val records = open.records.filter { it.id in open.selected }
        if (records.isEmpty()) return
        run(str(S.desktop_email_moved_to, to.label)) {
            val pin = place?.pin
            val picked = if (pin == null) {
                null
            } else {
                MovePlace(pin = pin, link = place.link, mapImage = storeMap(place))
            }
            repository.move(records, s.status, to, picked)
        }
    }

    private fun deleteSelected() {
        val s = state.value
        val ids = s.confirmDelete ?: return
        val open = s.gallery
        val uploaders = open?.records?.filter { it.id in ids }?.map { it.uploadedBy }.orEmpty()
        if (!s.viewer.mayDelete(uploaders)) {
            setState { copy(confirmDelete = null, error = str(S.desktop_location_admin_delete_only)) }
            return
        }
        setState { copy(confirmDelete = null) }
        run(str(S.drive_deleted_default)) { repository.delete(ids, s.status) }
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
            setState { copy(error = str(S.desktop_location_no_download_rights)) }
            return
        }
        val attachment = record.attachment ?: run {
            setState { copy(error = str(S.desktop_location_no_file_to_download)) }
            return
        }
        setState { copy(busy = true) }
        launch {
            when (val outcome = openAttachment(attachment, attachment.name.ifBlank { "location" })) {
                is ZillitResult.Failure -> setState { copy(busy = false, error = outcome.error.localised()) }
                is ZillitResult.Success -> {
                    setState { copy(busy = false) }
                    sendEffect(LocationEffect.Notice(str(S.docusign_signing_attachment_saved)))
                }
            }
        }
    }

    private suspend fun openAttachment(attachment: MediaAttachment, fallbackName: String): ZillitResult<Unit> =
        when (val bytes = transfer.fetch(attachment)) {
            is ZillitResult.Failure -> bytes
            is ZillitResult.Success -> transfer.saveAndOpen(attachment.name.ifBlank { fallbackName }, bytes.data)
        }

    /**
     * The form's place field changed.
     *
     * A pin that moved is remembered as moved — only then is anything
     * place-shaped sent — and its picture is fetched once, to be shown in
     * the form now and stored with the record on save.
     */
    private fun editorChanged(next: LocationEditor) {
        val previous = currentState.editor
        val moved = previous != null && next.place.pin != previous.place.pin
        val place = if (moved) {
            next.place.copy(moved = true, map = null, mapKnown = next.place.pin == null)
        } else {
            next.place
        }
        setState {
            copy(
                editor = next.copy(
                    place = place,
                    // The pin's address fills an empty Address field, as the web does.
                    address = next.address.ifBlank { if (moved) place.address else "" },
                ),
            )
        }
        if (moved) place.pin?.let(::fetchMapForEditor)
    }

    private fun fetchMapForEditor(pin: LatLng) {
        launch {
            val picture = runCatching { mapSnapshot(pin) }.getOrNull()
            setState {
                val open = editor ?: return@setState this
                if (open.place.pin != pin) return@setState this
                copy(editor = open.copy(place = open.place.copy(map = picture?.bytes, mapKnown = true)))
            }
        }
    }

    /** Puts a picked place's map picture in the store; null when there is none to store. */
    private suspend fun storeMap(place: PlaceEditor): MediaAttachment? {
        val bytes = place.map ?: return null
        val file = PickedLocationFile(MAP_FILE_NAME, "image/png", bytes)
        return (transfer.upload(file) as? ZillitResult.Success)?.data
    }

    /** The web's form checks, in its order; a shortlisted/rejected record needs a file or a link. */
    private fun saveProblem(editor: LocationEditor, status: LocationStatus): String? {
        val bare = editor.id == null && editor.file == null && !editor.place.hasPin
        return when {
            editor.location.isBlank() -> str(S.desktop_location_name_required)
            bare && status != LocationStatus.Selected -> str(S.desktop_location_media_required)
            // A link entry is only its link, so it cannot lose its pin.
            editor.isLinkOnly && !editor.place.hasPin -> str(S.desktop_location_link_required)
            editor.episodes.isNotBlank() && !editor.episodes.matches(EPISODES) ->
                str(S.desktop_location_episodes_format)
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
            // A pin set or moved in this form sends its coordinates and a
            // fresh picture of the map; one left alone sends neither, and
            // the record keeps the place it had.
            val mapImage = if (editor.place.moved) storeMap(editor.place) else null
            val outcome = if (id != null) {
                repository.update(id, editor.toDraft(s.status), mapImage)
            } else {
                createNew(editor, s.status, mapImage)
            }
            when (outcome) {
                is ZillitResult.Failure -> setState {
                    copy(busy = false, editor = editor.copy(saving = false), error = outcome.error.localised())
                }
                is ZillitResult.Success -> {
                    setState { copy(busy = false, editor = null) }
                    sendEffect(LocationEffect.Notice(
                        str(if (id == null) S.desktop_location_added else S.desktop_location_updated),
                    ))
                    val open = state.value.gallery
                    if (open != null) openGallery(open.pick)
                    refresh()
                }
            }
        }
    }

    /**
     * Upload first (if there is a file), then create the record pointing at
     * it.
     *
     * A record may carry a file *and* a place: the file in `attachment`, the
     * map picture in `link_attachment`. With neither it is a place on its
     * own — the web's link entry, whose card is the map picture.
     */
    private suspend fun createNew(
        editor: LocationEditor,
        status: LocationStatus,
        mapImage: MediaAttachment?,
    ): ZillitResult<Unit> {
        val stored = editor.file?.let { file ->
            when (val up = transfer.upload(file)) {
                is ZillitResult.Failure -> return up
                is ZillitResult.Success -> up.data
            }
        }
        return repository.create(editor.toDraft(status), stored, mapImage).map { }
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
            .mayPost) block() else setState { copy(error = str(S.desktop_location_no_posting_rights)) }
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

    /** The acts that create, change, move, delete or post — none of which a cabinet viewer does. */
    private fun LocationEvent.writes(): Boolean = when (this) {
        is LocationEvent.MoveSelected, LocationEvent.DeleteSelected, LocationEvent.ConfirmDelete,
        LocationEvent.PdfSelected, LocationEvent.ToggleSelecting, is LocationEvent.ToggleSelect,
        is LocationEvent.DiscussionDraftChanged, LocationEvent.SendDiscussion,
        LocationEvent.PickFile, is LocationEvent.FilePicked, LocationEvent.NewLink, is LocationEvent.Edit,
        is LocationEvent.EditorChanged, LocationEvent.Save,
        is LocationEvent.MovePlaceChanged, LocationEvent.ConfirmMove, LocationEvent.CancelMove -> true
        LocationEvent.ToggleMapView, is LocationEvent.OpenPin -> false
        else -> false
    }

    private companion object {
        const val PAGE = 50
        val EPISODES = Regex("[0-9,\\s]+")

        /** The shortlists the Map tool draws, and so the ones a move asks a place for. */
        val PLACE_ON_MOVE = setOf(LocationStatus.Shortlisted, LocationStatus.Published)

        /** What the map picture is stored under; the server only reads the extension. */
        const val MAP_FILE_NAME = "location-map.png"
    }
}
