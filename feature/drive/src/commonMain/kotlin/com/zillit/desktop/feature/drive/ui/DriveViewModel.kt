package com.zillit.desktop.feature.drive.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.drive.domain.DriveAction
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveItemKind
import com.zillit.desktop.feature.drive.domain.DrivePage
import com.zillit.desktop.feature.drive.domain.DriveQuery
import com.zillit.desktop.feature.drive.domain.DriveRef
import com.zillit.desktop.feature.drive.domain.DriveRepository
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.domain.PreviewKind
import com.zillit.desktop.feature.drive.domain.QueuedUpload
import com.zillit.desktop.feature.drive.domain.UploadPlan
import com.zillit.desktop.feature.drive.domain.UploadState
import com.zillit.desktop.feature.drive.domain.eligible
import kotlinx.coroutines.Job

/**
 * The Drive's one view model.
 *
 * ## Loading is per destination, not per screen
 *
 * [load] decides what a page needs and fetches exactly that. Screens never
 * fetch, which is what lets a delete on the browser correct the trash count
 * behind it: every mutation ends by reloading the current destination.
 *
 * ## The viewer is resolved before anything else
 *
 * Rights decide which tabs exist and whether the tool renders at all, so
 * [start] reads them first. The view model is built once for the whole app —
 * before any production is open — so the viewer is a lambda resolved in
 * [start], never a constructor snapshot.
 */
@Suppress("TooManyFunctions") // One handler per user action; see detekt.yml.
class DriveViewModel(
    private val repository: DriveRepository,
    /** Who is looking, read at start rather than at construction. See the class doc. */
    private val viewer: () -> DriveViewer,
    /**
     * Performs the actual byte transfer for an upload.
     *
     * A port because common code has no file API and no way to read a 10 GB
     * file in chunks. The default does nothing so tests and the render harness
     * can build this view model without a host — an upload simply never starts.
     */
    private val uploader: DriveUploader = DriveUploader.Unsupported,
    /** Ids for queued uploads. Injected so a test can predict them. */
    private val newUploadId: () -> String = { "upload-" + (uploadCounter++) },
) : ZillitViewModel<DriveUiState, DriveEvent, DriveEffect>(DriveUiState(viewer = viewer())) {

    private var loadJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        val identity = viewer()
        setState { copy(viewer = identity, destination = DriveDestination.landing(identity)) }
        if (!identity.isBlocked) {
            load(currentState.destination)
            launch { primeFavourites() }
            launch { primeTags() }
        }
        listenOnce()
    }

    /**
     * Reloads the open destination when the socket announces a delete from
     * another client — the web's refetch on the same events
     * (`DriveManagement.jsx:1591-1596`, ZL-18490), targeted at whatever
     * page is showing so a vanished row or a corrected trash count lands
     * without a manual refresh. Guarded separately from [started]: a
     * project switch resets [started] but must not stack a collector.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes.collect {
                if (started && !currentState.viewer.isBlocked) load(currentState.destination)
            }
        }
    }

    private var listening = false

    /**
     * The host could not open the document editor.
     *
     * Routed back through the view model rather than surfaced by the host, so
     * it reaches the user on the same toast as every other failure in this
     * tool — a second, differently-shaped error report for one case is how two
     * error styles end up in one window.
     */
    fun onEditorUnavailable(reason: String) {
        sendEffect(DriveEffect.Failed(reason))
    }

    /** Re-reads rights when the open production changes. */
    fun onProjectChanged() {
        started = false
        setState { DriveUiState(viewer = viewer()) }
        start()
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod") // One branch per user action.
    override fun onEvent(event: DriveEvent) {
        when (event) {
            DriveEvent.Refresh -> load(currentState.destination)
            DriveEvent.ClearNotice -> setState { copy(notice = null) }
            DriveEvent.DismissPrompt -> setState { copy(prompt = null) }
            DriveEvent.ConfirmPrompt -> currentState.prompt?.let { prompt ->
                setState { copy(prompt = null) }
                onEvent(prompt.event)
            }

            is DriveEvent.Open -> {
                setState { copy(destination = event.destination, error = null) }
                load(event.destination)
            }

            DriveEvent.ToggleViewMode -> setState { copy(viewMode = viewMode.toggled()) }

            // -- browsing --------------------------------------------------

            is DriveEvent.OpenFolder -> {
                setState {
                    copy(
                        folderId = event.folderId,
                        // Selection is per folder: carrying it across a
                        // navigation means a bulk delete hits rows the user can
                        // no longer see.
                        selected = emptySet(),
                        search = "",
                        details = DetailsState(),
                    )
                }
                loadBrowse()
            }

            is DriveEvent.OpenItem ->
                if (event.item.isFolder) {
                    onEvent(DriveEvent.OpenFolder(event.item.id))
                } else {
                    openFile(event.item)
                }

            DriveEvent.LoadMore -> loadMore()

            is DriveEvent.Search -> {
                setState { copy(search = event.text) }
                loadBrowse()
            }

            is DriveEvent.SortBy -> {
                setState { copy(sort = event.sort) }
                loadBrowse()
            }

            is DriveEvent.GroupBy -> setState { copy(grouping = event.grouping) }

            is DriveEvent.Filter -> {
                setState { copy(quickFilter = event.filter) }
                loadBrowse()
            }

            is DriveEvent.FilterByTag -> {
                setState { copy(tagFilterId = event.tagId) }
                loadBrowse()
            }

            is DriveEvent.ToggleSelection -> setState {
                copy(selected = selected.toggled(event.itemId))
            }

            is DriveEvent.SelectAll -> setState {
                copy(selected = if (event.selected) items.map { it.id }.toSet() else emptySet())
            }

            DriveEvent.ClearSelection -> setState { copy(selected = emptySet()) }

            // -- acting on items -------------------------------------------

            is DriveEvent.CreateFolder -> mutate(
                { repository.createFolder(event.name, currentState.folderId).asUnit() },
                "Folder created",
            )

            is DriveEvent.Rename -> mutate(
                { repository.rename(event.ref, event.name, event.description) },
                "Renamed",
            )

            is DriveEvent.MoveTo -> {
                if (event.refs.isEmpty()) return
                mutate(
                    {
                        if (event.refs.size == 1) {
                            repository.move(event.refs.first(), event.targetFolderId)
                        } else {
                            repository.bulkMove(event.refs, event.targetFolderId)
                        }
                    },
                    "Moved ${event.refs.size}",
                ) { setState { copy(selected = emptySet()) } }
            }

            is DriveEvent.RequestDelete -> requestDelete(event.refs)

            is DriveEvent.Delete -> {
                if (event.refs.isEmpty()) return
                mutate(
                    {
                        if (event.refs.size == 1) {
                            repository.delete(event.refs.first())
                        } else {
                            repository.bulkDelete(event.refs)
                        }
                    },
                    "Moved ${event.refs.size} to trash",
                ) { setState { copy(selected = emptySet(), details = DetailsState()) } }
            }

            is DriveEvent.ToggleFavourite -> toggleFavourite(event.ref)

            is DriveEvent.Download -> download(event.item)
            DriveEvent.DownloadSelection -> downloadSelection()
            is DriveEvent.ShareLink -> shareLink(event.item)
            is DriveEvent.OpenInEditor -> openEditor(event.item, event.editable)

            // -- details ---------------------------------------------------

            is DriveEvent.ShowDetails -> showDetails(event.item)
            is DriveEvent.CommentDraft -> setState {
                copy(details = details.copy(commentDraft = event.text))
            }

            DriveEvent.PostComment -> postComment()
            is DriveEvent.DeleteComment -> mutate(
                { repository.deleteComment(event.commentId) },
                "Comment deleted",
            ) { currentState.details.item?.let(::reloadDetails) }

            is DriveEvent.RestoreVersion -> mutate(
                { repository.restoreVersion(event.fileId, event.versionId) },
                "Version restored",
            ) { currentState.details.item?.let(::reloadDetails) }

            is DriveEvent.UpdateAccess -> mutate(
                { repository.updateAccess(event.ref, event.entries, event.applyToChildren) },
                if (event.applyToChildren) {
                    "Access updated — inheriting to subfolders"
                } else {
                    "Access updated"
                },
            )

            // -- uploads ---------------------------------------------------

            DriveEvent.PickFiles -> sendEffect(DriveEffect.PickFiles)
            is DriveEvent.Upload -> enqueue(event.files)
            is DriveEvent.CancelUpload -> cancelUpload(event.uploadId)
            DriveEvent.ClearFinishedUploads -> setState {
                copy(uploads = uploads.filterNot { it.isSettled })
            }

            // -- trash -----------------------------------------------------

            is DriveEvent.Restore -> mutate({ repository.restore(event.ref) }, "Restored")
            is DriveEvent.Purge -> mutate(
                { repository.purge(event.ref) },
                "Permanently deleted",
            )

            DriveEvent.RequestEmptyTrash -> setState {
                copy(
                    prompt = DrivePrompt(
                        title = "Empty the trash?",
                        message = "${trashItems.size} item" +
                            (if (trashItems.size == 1) "" else "s") +
                            " will be permanently deleted. This cannot be undone.",
                        confirmLabel = "Empty trash",
                        event = DriveEvent.EmptyTrash,
                    ),
                )
            }

            DriveEvent.EmptyTrash -> mutate({ repository.emptyTrash() }, "Trash emptied")
        }
    }

    // -- loading -----------------------------------------------------------

    private fun load(destination: DriveDestination) {
        when (destination) {
            DriveDestination.Browse -> loadBrowse()
            DriveDestination.Favourites -> fetch({ repository.favourites() }) {
                copy(favourites = it)
            }

            DriveDestination.Trash -> fetch({ repository.trash() }) { copy(trashItems = it) }
            DriveDestination.Activity -> fetch({ repository.activity(null) }) {
                copy(activity = it)
            }

            DriveDestination.Storage -> fetch({ repository.storage() }) { copy(storage = it) }
        }
    }

    /**
     * The current folder's contents, plus its breadcrumb.
     *
     * Cancels whatever was in flight first: typing in the search box fires one
     * of these per keystroke, and without cancellation an early reply can land
     * after a late one and repopulate the listing with a stale query's rows.
     */
    private fun loadBrowse() {
        loadJob?.cancel()
        setState { copy(loading = true, error = null) }
        loadJob = launch {
            val page = repository.contents(query(offset = 0))
            // Only when inside a folder — the root has no chain to fetch, and
            // asking for one is a 404 on every drive open.
            val crumbs = currentState.folderId?.let { repository.breadcrumb(it) }
            setState {
                copy(
                    loading = false,
                    breadcrumb = crumbs?.getOrNull().orEmpty(),
                    error = (page as? ZillitResult.Failure)?.error?.userMessage,
                )
            }
            (page as? ZillitResult.Success)?.let { applyPage(it.data, append = false) }
        }
    }

    private fun loadMore() {
        val state = currentState
        if (state.loadingMore || state.loading || !state.hasMore) return
        setState { copy(loadingMore = true) }
        launch {
            val page = repository.contents(query(offset = state.items.size))
            setState { copy(loadingMore = false) }
            when (page) {
                is ZillitResult.Success -> applyPage(page.data, append = true)
                is ZillitResult.Failure -> report(page.error)
            }
        }
    }

    private fun query(offset: Int) = DriveQuery(
        folderId = currentState.folderId,
        search = currentState.search,
        sort = currentState.sort,
        grouping = currentState.grouping,
        quickFilter = currentState.quickFilter,
        tagId = currentState.tagFilterId,
        offset = offset,
    )

    private fun applyPage(page: DrivePage, append: Boolean) {
        setState {
            copy(
                // Deduplicated by id on append: a row that moved between pages
                // while the user was reading appears in both, and a duplicate
                // key takes a LazyColumn down rather than merely repeating.
                items = if (append) (items + page.items).distinctBy { it.id } else page.items,
                total = page.total,
            )
        }
    }

    private fun <T> fetch(
        block: suspend () -> ZillitResult<T>,
        apply: DriveUiState.(T) -> DriveUiState,
    ) {
        loadJob?.cancel()
        setState { copy(loading = true, error = null) }
        loadJob = launch {
            when (val result = block()) {
                is ZillitResult.Success -> setState { apply(result.data).copy(loading = false) }
                is ZillitResult.Failure -> setState {
                    copy(loading = false, error = result.error.localised())
                }
            }
        }
    }

    private suspend fun primeFavourites() {
        (repository.favouriteIds() as? ZillitResult.Success)?.let { loaded ->
            setState { copy(favouriteIds = loaded.data) }
        }
    }

    private suspend fun primeTags() {
        (repository.tags() as? ZillitResult.Success)?.let { loaded ->
            setState { copy(tags = loaded.data) }
        }
    }

    // -- mutations ---------------------------------------------------------

    /**
     * Runs a write, then reloads the page it changed.
     *
     * Reloading rather than patching the list in place: the server assigns
     * ordering, per-item permissions and the folder counts, and a locally
     * patched row disagrees with all three until the next refresh.
     */
    private fun mutate(
        block: suspend () -> ZillitResult<Unit>,
        success: String,
        onDone: () -> Unit = {},
    ) {
        launch {
            when (val result = block()) {
                is ZillitResult.Success -> {
                    onDone()
                    setState { copy(notice = success) }
                    load(currentState.destination)
                }

                is ZillitResult.Failure -> report(result.error)
            }
        }
    }

    /**
     * Raises the delete confirmation, naming what will and will not go.
     *
     * The count is what the viewer may actually delete, not what they selected:
     * the server checks each item, so a mixed selection partially succeeds, and
     * a dialog that promises twenty and removes seventeen is a dialog that lied.
     */
    private fun requestDelete(refs: List<DriveRef>) {
        val state = currentState
        val items = state.items.filter { item -> refs.any { it.id == item.id } }
        val allowed = state.viewer.eligible(DriveAction.Delete, items)
        if (allowed.isEmpty()) {
            sendEffect(DriveEffect.Failed("You do not have permission to delete that."))
            return
        }
        val blocked = items.size - allowed.size
        setState {
            copy(
                prompt = DrivePrompt(
                    title = if (allowed.size == 1) "Move to trash?" else "Move ${allowed.size} to trash?",
                    message = buildString {
                        append(
                            if (allowed.size == 1) {
                                "\"${allowed.first().name}\" will be moved to the trash."
                            } else {
                                "${allowed.size} items will be moved to the trash."
                            },
                        )
                        // Folders cascade. Someone deleting a folder with two
                        // hundred files inside is entitled to know that before
                        // they confirm, not after.
                        if (allowed.any { it.isFolder }) {
                            append(" Everything inside the selected folders goes too.")
                        }
                        if (blocked > 0) {
                            append(" $blocked item")
                            append(if (blocked == 1) " is" else "s are")
                            append(" skipped — you do not have delete rights for ")
                            append(if (blocked == 1) "it." else "them.")
                        }
                        append(" You can restore from the trash.")
                    },
                    confirmLabel = "Move to trash",
                    event = DriveEvent.Delete(allowed.map { DriveRef(it.id, it.kind) }),
                ),
            )
        }
    }

    /**
     * Stars or unstars, updating the set before the server answers.
     *
     * The one optimistic update in this module: a star that waits for a round
     * trip feels broken, and the cost of being wrong is a star in the wrong
     * state until the next refresh rather than a lost file.
     */
    private fun toggleFavourite(ref: DriveRef) {
        setState { copy(favouriteIds = favouriteIds.toggled(ref.id)) }
        launch {
            when (val result = repository.toggleFavourite(ref)) {
                is ZillitResult.Success ->
                    if (currentState.destination == DriveDestination.Favourites) {
                        load(DriveDestination.Favourites)
                    }

                is ZillitResult.Failure -> {
                    setState { copy(favouriteIds = favouriteIds.toggled(ref.id)) }
                    report(result.error)
                }
            }
        }
    }

    // -- opening files -----------------------------------------------------

    /**
     * What a double-click does to a file.
     *
     * An editable document opens in the editor when the viewer may edit it and
     * read-only when they may not — never nothing, which is what a plain
     * permission check would do. Everything else opens its preview, and video
     * and audio ask for the streaming URL instead so seeking works.
     */
    private fun openFile(item: DriveItem) {
        if (item.isEditableDocument) {
            openEditor(item, editable = currentState.viewer.may(DriveAction.Edit, item))
            return
        }
        if (!currentState.viewer.may(DriveAction.View, item)) {
            sendEffect(DriveEffect.Failed("You do not have permission to open that file."))
            return
        }
        launch {
            val url = when (item.previewKind) {
                PreviewKind.Video, PreviewKind.Audio -> repository.streamUrl(item.id)
                else -> repository.previewUrl(item.id)
            }
            when (url) {
                is ZillitResult.Success -> sendEffect(DriveEffect.OpenUrl(url.data))
                is ZillitResult.Failure -> report(url.error)
            }
        }
    }

    private fun openEditor(item: DriveItem, editable: Boolean) {
        launch {
            when (val url = repository.editorUrl(item.id, editable)) {
                is ZillitResult.Success ->
                    sendEffect(DriveEffect.OpenEditor(url.data, item.name))

                is ZillitResult.Failure -> report(url.error)
            }
        }
    }

    private fun download(item: DriveItem) {
        if (!currentState.viewer.may(DriveAction.Download, item)) {
            sendEffect(DriveEffect.Failed("You do not have download rights for that file."))
            return
        }
        launch {
            when (val url = repository.downloadUrl(item.id)) {
                is ZillitResult.Success -> sendEffect(DriveEffect.OpenUrl(url.data))
                is ZillitResult.Failure -> report(url.error)
            }
        }
    }

    private fun downloadSelection() {
        val state = currentState
        val files = state.viewer
            .eligible(DriveAction.Download, state.selectedItems)
            .filterNot { it.isFolder }
        if (files.isEmpty()) {
            sendEffect(DriveEffect.Failed("Nothing in the selection can be downloaded."))
            return
        }
        launch {
            when (val urls = repository.bulkDownloadUrls(files.map { it.id })) {
                is ZillitResult.Success -> urls.data.forEach {
                    sendEffect(DriveEffect.OpenUrl(it))
                }

                is ZillitResult.Failure -> report(urls.error)
            }
        }
    }

    private fun shareLink(item: DriveItem) {
        if (!currentState.viewer.may(DriveAction.Share, item)) {
            sendEffect(DriveEffect.Failed("Only an owner can share this file."))
            return
        }
        launch {
            when (val link = repository.shareLink(item.id)) {
                is ZillitResult.Success -> sendEffect(
                    // Said explicitly: a public link that expires in a day is
                    // not what most people assume "share" means.
                    DriveEffect.CopyToClipboard(link.data, "Share link copied — expires in 24 hours"),
                )

                is ZillitResult.Failure -> report(link.error)
            }
        }
    }

    // -- details panel -----------------------------------------------------

    private fun showDetails(item: DriveItem?) {
        if (item == null) {
            setState { copy(details = DetailsState()) }
            return
        }
        setState { copy(details = DetailsState(item = item, loading = true)) }
        reloadDetails(item)
    }

    private fun reloadDetails(item: DriveItem) {
        launch {
            // Folders have neither comments nor versions; asking for them is
            // two guaranteed 404s every time a folder is inspected.
            val comments = if (item.isFolder) null else repository.comments(item.id)
            val versions = if (item.isFolder) null else repository.versions(item.id)
            val activity = repository.activity(item.id)
            val access = repository.access(DriveRef(item.id, item.kind))
            setState {
                copy(
                    details = details.copy(
                        loading = false,
                        comments = comments?.getOrNull().orEmpty(),
                        versions = versions?.getOrNull().orEmpty(),
                        activity = activity.getOrNull().orEmpty(),
                        access = access.getOrNull().orEmpty(),
                    ),
                )
            }
        }
    }

    private fun postComment() {
        val details = currentState.details
        val item = details.item ?: return
        val text = details.commentDraft.trim()
        if (text.isEmpty()) return
        setState { copy(details = this.details.copy(commentDraft = "")) }
        launch {
            when (val result = repository.addComment(item.id, text)) {
                is ZillitResult.Success -> reloadDetails(item)
                is ZillitResult.Failure -> {
                    // The draft goes back in the box rather than being lost —
                    // retyping a paragraph because the network blinked is the
                    // worst thing a comment field can do.
                    setState { copy(details = this.details.copy(commentDraft = text)) }
                    report(result.error)
                }
            }
        }
    }

    // -- uploads -----------------------------------------------------------

    /**
     * Queues files and starts them.
     *
     * Refused up front on size and name (see [UploadPlan.rejectionReason]) so
     * an oversized file is rejected in the queue rather than after its first
     * chunk has been pushed and the completion has failed.
     */
    private fun enqueue(files: List<PickedFile>) {
        if (!currentState.viewer.canCreate) {
            sendEffect(DriveEffect.Failed("You do not have permission to upload to this drive."))
            return
        }
        val folder = currentState.folderId
        files.forEach { file ->
            val reason = UploadPlan.rejectionReason(file.name, file.sizeBytes)
            if (reason != null) {
                sendEffect(DriveEffect.Failed(reason))
                return@forEach
            }
            val queued = QueuedUpload(
                id = newUploadId(),
                fileName = file.name,
                sizeBytes = file.sizeBytes,
                destinationFolderId = folder,
            )
            setState { copy(uploads = uploads + queued) }
            startUpload(queued, file)
        }
    }

    private fun startUpload(queued: QueuedUpload, file: PickedFile) {
        launch {
            val result = uploader.upload(
                file = file,
                folderId = queued.destinationFolderId,
                onProgress = { uploaded, total ->
                    setState {
                        copy(
                            uploads = uploads.map { row ->
                                if (row.id == queued.id) {
                                    row.copy(state = UploadState.InProgress(uploaded, total))
                                } else {
                                    row
                                }
                            },
                        )
                    }
                },
            )
            val settled = when (result) {
                is ZillitResult.Success -> UploadState.Done(result.data.id)
                is ZillitResult.Failure -> UploadState.Failed(result.error.localised())
            }
            setState {
                copy(
                    uploads = uploads.map { row ->
                        if (row.id == queued.id) row.copy(state = settled) else row
                    },
                )
            }
            // Only the folder the file landed in is refreshed, and only when
            // the user is still looking at it — a completed background upload
            // must not yank the listing out from under someone browsing
            // elsewhere.
            if (settled is UploadState.Done &&
                currentState.folderId == queued.destinationFolderId &&
                currentState.destination == DriveDestination.Browse
            ) {
                loadBrowse()
            }
        }
    }

    private fun cancelUpload(uploadId: String) {
        setState {
            copy(
                uploads = uploads.map { row ->
                    if (row.id == uploadId) {
                        row.copy(state = UploadState.Failed("Cancelled"))
                    } else {
                        row
                    }
                },
            )
        }
    }

    // -- shared ------------------------------------------------------------

    private fun report(error: ZillitError) {
        sendEffect(DriveEffect.Failed(error.localised()))
    }

    private companion object {
        var uploadCounter = 0
    }
}

/**
 * Puts one file's bytes into the drive.
 *
 * A port because common code has no file API: reading a 10 GB file a chunk at a
 * time is a platform concern, and the presigned PUTs it drives are ordinary
 * HTTP the host already has a client for. Kept as an interface so the view
 * model, the render tests and the eventual drag-and-drop host all talk to the
 * same contract.
 */
interface DriveUploader {

    /**
     * Uploads [file] and returns the created drive item.
     *
     * [onProgress] receives (uploaded parts, total parts) as each part is
     * acknowledged — parts rather than bytes, because that is what the client
     * can honestly observe: a part is either accepted by S3 or it is not.
     */
    suspend fun upload(
        file: PickedFile,
        folderId: String?,
        onProgress: (uploaded: Int, total: Int) -> Unit,
    ): ZillitResult<DriveItem>

    /**
     * The uploader before a host has supplied one.
     *
     * Fails rather than silently doing nothing: an upload that reports success
     * without moving a byte is the worst possible default, and this way a
     * missing wiring shows up as a message the first time anyone tries.
     */
    object Unsupported : DriveUploader {
        override suspend fun upload(
            file: PickedFile,
            folderId: String?,
            onProgress: (Int, Int) -> Unit,
        ): ZillitResult<DriveItem> = ZillitResult.Failure(
            ZillitError.Validation("Uploading is not available in this build."),
        )
    }
}

/** Adds or removes, whichever the current membership implies. */
private fun <T> Set<T>.toggled(value: T): Set<T> =
    if (value in this) this - value else this + value

/** A created-item result the caller only needs the success of. */
private fun ZillitResult<DriveItem>.asUnit(): ZillitResult<Unit> = when (this) {
    is ZillitResult.Success -> ZillitResult.Success(Unit)
    is ZillitResult.Failure -> this
}

/** The kind a bare id refers to, when only the id is to hand. */
internal fun DriveItem.ref(): DriveRef = DriveRef(id, kind)

/** Files only — folders have no bytes to fetch. */
internal fun List<DriveItem>.filesOnly(): List<DriveItem> = filterNot { it.isFolder }

/** Every selected row as a ref, for the bulk routes. */
internal fun List<DriveItem>.refs(): List<DriveRef> = map { DriveRef(it.id, it.kind) }

/** Unused today; kept beside the other converters so the set stays together. */
@Suppress("unused")
internal fun DriveRef.isFolder(): Boolean = kind == DriveItemKind.Folder
