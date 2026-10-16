package com.zillit.desktop.feature.drive.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.feature.drive.domain.isDuplicateName
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.drive.domain.DriveAction
import com.zillit.desktop.feature.drive.domain.DriveActivityPage
import com.zillit.desktop.feature.drive.domain.DriveAccessEntry
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveRole
import com.zillit.desktop.feature.drive.domain.DriveShareLink
import com.zillit.desktop.feature.drive.domain.DriveShareLinkDraft
import com.zillit.desktop.feature.drive.domain.FileAccessLevel
import com.zillit.desktop.feature.drive.domain.NewFolder
import com.zillit.desktop.feature.drive.domain.PreviewKind
import com.zillit.desktop.feature.drive.domain.UploadPlan
import com.zillit.desktop.feature.drive.domain.eligible
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The Drive's drawers and dialogs: upload, create folder, edit info, share,
 * move to, preview and the activity log — each a draft in the state and the
 * write that submits it.
 *
 * Kept apart from [DriveViewModel] so the view model stays navigation and
 * dispatch; every drawer here is one open/edit/submit triple.
 */
@Suppress("TooManyFunctions") // One open/submit pair per drawer.
internal class DriveDrawers(private val vm: DriveViewModel, private val queue: UploadQueue) {

    private val state get() = vm.state.value

    // -- upload --------------------------------------------------------------

    fun openUpload() {
        if (!vm.requirePosting()) return
        vm.update { copy(upload = UploadDraft(), menu = null) }
        vm.refreshPeople()
        // Into a folder: its members keep their access on every file inside
        // it, so the permissions step shows them locked at that level rather
        // than offering a grant it cannot take away.
        loadFolderFloors { floors -> updateUpload { copy(access = access.copy(inherited = floors)) } }
    }

    /**
     * Picking a destination in the drawer: the floors follow it.
     *
     * The upload may be aimed at a folder other than the one on screen, and
     * it is *that* folder whose members keep their access on every file in
     * it — so a change of destination reloads them, and the Drive root clears
     * them.
     */
    fun uploadDestination(pickExisting: Boolean, folderId: String?) {
        updateUpload {
            copy(
                pickExisting = pickExisting,
                destinationFolderId = folderId,
                access = access.copy(inherited = emptyMap()),
            )
        }
        loadFolderFloors(folderId) { floors -> updateUpload { copy(access = access.copy(inherited = floors)) } }
    }

    /**
     * The roles a folder gives, for the pickers that grant on a file inside
     * it. Nothing happens at the root, where there is no folder to inherit
     * from; [folderId] defaults to the folder on screen.
     */
    private fun loadFolderFloors(
        folderId: String? = state.folderId,
        apply: (Map<String, DriveRole>) -> Unit,
    ) {
        val folder = folderId?.let { state.folderById[it] } ?: return
        vm.run {
            val rows = vm.repo.access(folder.ref).getOrNull().orEmpty()
            if (rows.isNotEmpty()) apply(rows.floors())
        }
    }

    fun updateUpload(change: UploadDraft.() -> UploadDraft) =
        vm.update { copy(upload = upload?.change()) }

    /**
     * Adds picked files, sorting the unsupported ones into their own list
     * so the user sees what was skipped and why — the web's
     * `unsupportedFiles` panel.
     */
    fun addUploadFiles(files: List<PickedFile>) {
        if (state.upload == null) {
            // Picked with the drawer closed — the compact widget's sheet —
            // so the files go straight to the queue as before.
            enqueueDirect(files)
            return
        }
        val (ok, skipped) = files.partition { isAccepted(it) }
        updateUpload {
            copy(
                files = (this.files + ok).distinctBy { it.path },
                unsupported = (unsupported + skipped).distinctBy { it.path },
            )
        }
    }

    private fun enqueueDirect(files: List<PickedFile>) {
        if (!vm.requirePosting()) return
        val target = UploadTarget(folderId = state.folderId)
        queue.enqueue(files.filter { isAccepted(it) }, target)
    }

    fun submitUpload() {
        val draft = state.upload ?: return
        if (!draft.canSubmit) {
            if (draft.pickExisting && draft.destinationFolderId == null) {
                vm.reportFailure(str(S.drive_pick_please_select_destination))
            } else {
                vm.reportFailure(str(S.please_select_files))
            }
            return
        }
        val destination = state.folderId ?: draft.destinationFolderId.takeIf { draft.pickExisting }
        val target = UploadTarget(
            folderId = destination,
            description = draft.description.trim(),
            // Nothing is sent for the people the folder already covers: that
            // grant is the folder's, and the server keeps it whatever a file's
            // own share says. More than the folder gives still goes.
            fileAccess = draft.access.fileEntries(state.sharePeople.exceptMe(), skipCovered = true),
        )
        vm.update { copy(upload = null) }
        queue.enqueue(draft.files, target)
    }

    // -- drop from the OS ------------------------------------------------------

    /**
     * Files dropped onto the page. Validated like the drawer — extension,
     * size, count — then parked behind the "Set File Permissions" step the
     * web shows before a dropped upload starts.
     */
    fun dropFiles(files: List<PickedFile>) {
        if (!vm.requirePosting()) return
        // Not "which tab is this": a folder shared with this person as Editor
        // takes files too (ZL-21871). The root of Shared with me does not.
        if (!state.canAddHere) {
            vm.reportFailure(str(S.desktop_drive_upload_my_drive_only))
            return
        }
        if (files.size > MAX_DROP_COUNT) {
            vm.reportFailure(str(S.desktop_drive_drop_max, MAX_DROP_COUNT, files.size))
            return
        }
        val rejected = mutableListOf<String>()
        val valid = files.filter { file ->
            when {
                !isAccepted(file) -> { rejected += str(S.desktop_drive_rejected_unsupported, file.name); false }
                file.sizeBytes > UploadPlan.MAX_FILE_BYTES -> {
                    rejected += str(S.desktop_drive_rejected_too_large, file.name)
                    false
                }
                file.sizeBytes == 0L -> { rejected += str(S.desktop_drive_rejected_empty, file.name); false }
                else -> true
            }
        }
        if (rejected.isNotEmpty()) {
            vm.reportFailure(
                str(
                    S.desktop_drive_files_skipped,
                    rejected.size,
                    rejected.take(REJECTED_SHOWN).joinToString(", ") + if (rejected.size > REJECTED_SHOWN) "…" else "",
                ),
            )
        }
        if (valid.isEmpty()) return
        vm.update { copy(dropUpload = DropUploadState(valid)) }
        loadFolderFloors { floors ->
            vm.update { copy(dropUpload = dropUpload?.copy(access = dropUpload.access.copy(inherited = floors))) }
        }
    }

    fun confirmDrop(withAccess: Boolean) {
        val drop = state.dropUpload ?: return
        vm.update { copy(dropUpload = null) }
        val target = UploadTarget(
            folderId = state.folderId,
            fileAccess = if (withAccess) {
                drop.access.fileEntries(state.sharePeople.exceptMe(), skipCovered = true)
            } else {
                emptyList()
            },
        )
        queue.enqueue(drop.files, target)
    }

    // -- create folder ---------------------------------------------------------

    fun openNewFolder() {
        if (!vm.requirePosting()) return
        vm.update { copy(newFolder = NewFolderDraft(), menu = null) }
        vm.refreshPeople()
    }

    fun updateNewFolder(change: NewFolderDraft.() -> NewFolderDraft) =
        vm.update { copy(newFolder = newFolder?.change()) }

    fun submitNewFolder() {
        val draft = state.newFolder ?: return
        if (!draft.canSubmit) {
            if (draft.pickExisting && draft.destinationFolderId == null) {
                vm.reportFailure(str(S.drive_pick_please_select_destination))
            }
            return
        }
        val parent = state.folderId ?: draft.destinationFolderId.takeIf { draft.pickExisting }
        val folder = NewFolder(
            name = draft.name.trim(),
            parentId = parent,
            description = draft.description.trim(),
            access = draft.access.folderEntries(state.sharePeople.exceptMe()),
        )
        updateNewFolder { copy(submitting = true) }
        vm.run {
            when (val made = vm.repo.createFolder(folder)) {
                is ZillitResult.Success -> {
                    vm.update { copy(newFolder = null, notice = str(S.desktop_drive_activity_folder_created)) }
                    vm.loadListing()
                    // A subfolder made inside someone else's folder is in no
                    // tab-wide list of this user's; the folder itself is asked.
                    vm.mergeFolder(parent)
                }

                is ZillitResult.Failure -> {
                    // A taken name is the name field's problem, said where the user is looking.
                    val taken = made.error.isDuplicateName()
                    updateNewFolder { copy(submitting = false, nameError = made.error.localised().takeIf { taken }) }
                    if (!taken) vm.reportError(made.error)
                }
            }
        }
    }

    // -- edit info -------------------------------------------------------------

    /** ZL-18310: a file's name is edited without its extension so the format cannot change. */
    fun openEdit(item: DriveItem) {
        vm.update { copy(menu = null) }
        if (!vm.requirePosting()) return
        if (!state.viewer.may(DriveAction.Edit, item)) {
            vm.reportFailure(str(S.desktop_drive_no_edit_permission))
            return
        }
        val name = if (item.isFolder) item.name else item.name.substringBeforeLast('.', item.name)
        vm.update { copy(edit = EditDraft(item = item, name = name, description = item.description)) }
    }

    fun updateEdit(change: EditDraft.() -> EditDraft) = vm.update { copy(edit = edit?.change()) }

    fun submitEdit() {
        val draft = state.edit ?: return
        if (!draft.canSubmit) return
        val item = draft.item
        var name = draft.name.trim()
        if (!item.isFolder) {
            val ext = item.name.substringAfterLast('.', "")
            if (ext.isNotBlank() && !name.lowercase().endsWith(".${ext.lowercase()}")) name = "$name.$ext"
        }
        updateEdit { copy(submitting = true) }
        vm.run {
            when (val done = vm.repo.rename(item.ref, name, draft.description.trim())) {
                is ZillitResult.Success -> {
                    vm.update {
                        copy(
                            edit = null,
                            notice = if (item.isFolder) {
                                str(S.dd_folder_updated)
                            } else {
                                str(S.desktop_drive_activity_file_updated)
                            },
                            details = if (details.item?.id == item.id) {
                                details.copy(item = item.copy(name = name, description = draft.description.trim()))
                            } else {
                                details
                            },
                        )
                    }
                    vm.loadListing()
                }

                is ZillitResult.Failure -> {
                    val taken = done.error.isDuplicateName()
                    updateEdit { copy(submitting = false, nameError = done.error.localised().takeIf { taken }) }
                    if (!taken) vm.reportError(done.error)
                }
            }
        }
    }

    // -- share -----------------------------------------------------------------

    fun openShare(item: DriveItem) {
        vm.update { copy(menu = null) }
        if (!vm.requirePosting()) return
        if (!state.viewer.may(DriveAction.Share, item)) {
            vm.reportFailure(
                if (item.isFolder) {
                    str(S.desktop_drive_only_owner_manages_access)
                } else {
                    str(S.desktop_drive_cannot_share_file)
                },
            )
            return
        }
        vm.update {
            copy(share = ShareState(item = item, loading = true, link = ShareLinkForm(loadingLinks = !item.isFolder)))
        }
        vm.refreshPeople()
        vm.run {
            val rows = vm.repo.access(item.ref).getOrNull().orEmpty()
            val draft = AccessDraft(
                roles = if (item.isFolder) rows.associate { it.userId to it.role } else emptyMap(),
                levels = if (item.isFolder) {
                    emptyMap()
                } else {
                    rows.associate { it.userId to FileAccessLevel.of(it.permissions) }
                },
                // The rows a folder above put there: shown ticked, and locked
                // at the level that folder gives (see [AccessDraft.inherited]).
                inherited = rows.floors(),
            ).liftedToFloors(item.isFolder)
            updateShare { copy(access = draft, loading = false) }
            if (!item.isFolder) reloadLinks(item.id)
        }
    }

    fun updateShare(change: ShareState.() -> ShareState) = vm.update { copy(share = share?.change()) }

    fun updateLink(change: ShareLinkForm.() -> ShareLinkForm) = updateShare { copy(link = link.change()) }

    fun submitShare() {
        val share = state.share ?: return
        val item = share.item
        val people = state.sharePeople.exceptMe()
        val entries = if (item.isFolder) share.access.folderEntries(people) else share.access.fileEntries(people)
        if (entries.isEmpty()) {
            vm.reportFailure(str(S.desktop_drive_select_user_to_share))
            return
        }
        updateShare { copy(submitting = true) }
        vm.run {
            when (val done = vm.repo.updateAccess(item.ref, entries)) {
                is ZillitResult.Success -> {
                    vm.update {
                        copy(
                            share = null,
                            // The server's own word for what happened
                            // (`folder_access_updated`), translated. Counting
                            // the entries ourselves is what made removing one
                            // of two people say "shared with 1 user".
                            notice = done.data.takeIf { it.isNotBlank() }?.localisedMessage()
                                ?: str(S.desktop_drive_activity_access_updated),
                        )
                    }
                    vm.loadListing()
                    if (state.details.item?.id == item.id) {
                        val fresh = vm.repo.access(item.ref)
                        vm.update { copy(details = details.copy(access = fresh.getOrNull() ?: details.access)) }
                    }
                }

                is ZillitResult.Failure -> {
                    updateShare { copy(submitting = false) }
                    vm.reportError(done.error)
                }
            }
        }
    }

    private suspend fun reloadLinks(fileId: String) {
        updateLink { copy(loadingLinks = true) }
        val links = vm.repo.shareLinks(fileId)
        updateLink { copy(loadingLinks = false, links = links.getOrNull().orEmpty()) }
    }

    /**
     * Makes a tracked link — emailed when recipients were given, otherwise
     * copied straight to the clipboard, as `ShareViaLink.handleGenerate` does.
     */
    fun generateShareLink() {
        val share = state.share ?: return
        val form = share.link
        val recipients = form.recipients.split(Regex("[\\s,;]+")).map { it.trim() }.filter { it.isNotEmpty() }
        recipients.firstOrNull { !EMAIL.matches(it) }?.let {
            vm.reportFailure(str(S.desktop_drive_invalid_email, it))
            return
        }
        updateLink { copy(submitting = true) }
        vm.run {
            val draft = DriveShareLinkDraft(
                recipients = recipients,
                permission = form.permission,
                expiresInMillis = form.expiresInMillis,
                maxViews = form.maxViews,
                message = form.message,
            )
            when (val made = vm.repo.createShareLink(share.item.id, draft)) {
                is ZillitResult.Success -> {
                    if (recipients.isNotEmpty()) {
                        vm.notice(
                            if (recipients.size == 1) {
                                str(S.desktop_drive_link_sent_one)
                            } else {
                                str(S.desktop_drive_link_sent_many, recipients.size)
                            },
                        )
                    } else if (made.data.url.isNotBlank()) {
                        vm.effect(DriveEffect.CopyToClipboard(made.data.url, str(S.desktop_drive_share_link_copied)))
                    } else {
                        vm.notice(str(S.desktop_drive_share_link_created))
                    }
                    updateLink { copy(submitting = false, recipients = "", message = "") }
                    reloadLinks(share.item.id)
                }

                is ZillitResult.Failure -> {
                    updateLink { copy(submitting = false) }
                    vm.reportError(made.error)
                }
            }
        }
    }

    fun revokeShareLink(link: DriveShareLink) {
        val share = state.share ?: return
        vm.run {
            when (val done = vm.repo.revokeShareLink(link.id)) {
                is ZillitResult.Success -> {
                    vm.notice(str(S.drive_link_revoked))
                    reloadLinks(share.item.id)
                }

                is ZillitResult.Failure -> vm.reportError(done.error)
            }
        }
    }

    // -- move to ---------------------------------------------------------------

    fun openMoveTo(items: List<DriveItem>) {
        vm.update { copy(menu = null) }
        if (items.isEmpty() || !vm.requirePosting()) return
        val allowed = state.viewer.eligible(DriveAction.Edit, items)
        if (allowed.isEmpty()) {
            vm.reportFailure(str(S.desktop_drive_no_move_permission))
            return
        }
        vm.update { copy(moveTo = MoveToState(items = allowed)) }
    }

    // -- preview ---------------------------------------------------------------

    /**
     * What opening a file does — `handleFilePreview`.
     *
     * Office documents open in the editor read-only; audio goes to the host's
     * player at its streaming address; video plays in the dialog from that
     * address, so the arrows can step through images and clips alike; images,
     * PDFs and plain text are fetched and shown in the dialog; anything else
     * gets a card with a way out to the browser.
     */
    fun preview(item: DriveItem) {
        vm.update { copy(menu = null) }
        if (item.isFolder) return
        if (item.isEditableDocument) {
            vm.openEditor(item, editable = false)
            return
        }
        vm.markRead(item)
        if (!state.viewer.may(DriveAction.View, item)) {
            vm.reportFailure(str(S.desktop_drive_no_open_permission))
            return
        }
        when (val kind = item.previewKind) {
            PreviewKind.Video -> previewVideo(item)
            PreviewKind.Audio -> vm.run {
                when (val url = vm.repo.streamUrl(item.id)) {
                    is ZillitResult.Success -> vm.effect(DriveEffect.OpenMedia(url.data, item.name))
                    is ZillitResult.Failure -> vm.reportError(url.error)
                }
            }

            else -> previewFetched(item, kind)
        }
    }

    /** Images, PDFs and text: the bytes are fetched and drawn in the dialog. */
    private fun previewFetched(item: DriveItem, kind: PreviewKind) {
        vm.update { copy(preview = PreviewState(item = item, kind = kind)) }
        vm.run {
            val url = when (val got = vm.repo.previewUrl(item.id)) {
                is ZillitResult.Success -> got.data
                is ZillitResult.Failure -> {
                    settlePreview(item) { copy(loading = false, error = got.error.userMessage) }
                    return@run
                }
            }
            settlePreview(item) { copy(url = url) }
            when (kind) {
                PreviewKind.Image -> fetchInto(item, url, INLINE_IMAGE_MAX) { copy(imageBytes = it) }
                PreviewKind.Text -> fetchInto(item, url, INLINE_TEXT_MAX) { copy(text = it.decodeToString()) }
                PreviewKind.Pdf -> fetchInto(item, url, INLINE_DOCUMENT_MAX) { bytes ->
                    val pages = vm.previews.renderPdfPages(bytes, PDF_PAGE_WIDTH)
                    when (pages) {
                        is ZillitResult.Success -> copy(pages = pages.data)
                        is ZillitResult.Failure -> copy(error = pages.error.userMessage)
                    }
                }

                else -> settlePreview(item) { copy(loading = false) }
            }
        }
    }

    /** A clip plays in the dialog from its streaming address — nothing is fetched up front. */
    private fun previewVideo(item: DriveItem) {
        vm.update { copy(preview = PreviewState(item = item, kind = PreviewKind.Video)) }
        vm.run {
            when (val url = vm.repo.streamUrl(item.id)) {
                is ZillitResult.Success -> settlePreview(item) { copy(loading = false, url = url.data) }
                is ZillitResult.Failure -> settlePreview(item) {
                    copy(loading = false, error = url.error.userMessage)
                }
            }
        }
    }

    private suspend fun fetchInto(
        item: DriveItem,
        url: String,
        maxBytes: Long,
        apply: suspend PreviewState.(ByteArray) -> PreviewState,
    ) {
        if (item.sizeBytes > maxBytes) {
            settlePreview(item) { copy(loading = false, error = str(S.desktop_drive_preview_too_large)) }
            return
        }
        when (val bytes = vm.previews.fetchBytes(url, maxBytes)) {
            is ZillitResult.Success -> {
                // Decoding happens off the state: a rasterised PDF is slow,
                // and the preview it belongs to may have been closed meanwhile.
                val current = vm.state.value.preview?.takeIf { it.item.id == item.id } ?: return
                val applied = current.apply(bytes.data)
                settlePreview(item) { applied.copy(loading = false) }
            }

            is ZillitResult.Failure -> settlePreview(item) { copy(loading = false, error = bytes.error.userMessage) }
        }
    }

    /**
     * The preview's way out of the dialog. A clip the in-app player could not
     * play goes to the host's player, as clips did before they played here;
     * anything else goes to the browser. The dialog closes first, so a dead
     * player is not left standing behind the window it handed off to.
     */
    fun openPreviewOutside() {
        val preview = state.preview ?: return
        val url = preview.url ?: return
        if (preview.kind == PreviewKind.Video) {
            vm.update { copy(preview = null) }
            vm.effect(DriveEffect.OpenMedia(url, preview.item.name))
        } else {
            vm.effect(DriveEffect.OpenUrl(url))
        }
    }

    /** Applies to the preview only while it is still the one that was opened. */
    private fun settlePreview(item: DriveItem, change: PreviewState.() -> PreviewState) = vm.update {
        if (preview?.item?.id == item.id) copy(preview = preview.change()) else this
    }

    // -- activity log ------------------------------------------------------------

    fun openActivityLog() {
        vm.update { copy(activityLog = ActivityLogState(open = true, loading = true), menu = null) }
        vm.run {
            when (val page = vm.repo.activityPage(ACTIVITY_PAGE, 0)) {
                is ZillitResult.Success -> applyActivity(page.data, append = false)
                is ZillitResult.Failure -> {
                    vm.update { copy(activityLog = activityLog.copy(loading = false)) }
                    vm.reportError(page.error)
                }
            }
        }
    }

    fun loadMoreActivity() {
        val log = state.activityLog
        val busy = log.loading || log.loadingMore
        if (!log.open || busy || !log.hasMore) return
        vm.update { copy(activityLog = activityLog.copy(loadingMore = true)) }
        vm.run {
            when (val page = vm.repo.activityPage(ACTIVITY_PAGE, log.items.size)) {
                is ZillitResult.Success -> applyActivity(page.data, append = true)
                is ZillitResult.Failure -> {
                    vm.update { copy(activityLog = activityLog.copy(loadingMore = false)) }
                    vm.reportError(page.error)
                }
            }
        }
    }

    private fun applyActivity(page: DriveActivityPage, append: Boolean) = vm.update {
        if (!activityLog.open) return@update this
        val rows = if (append) (activityLog.items + page.items).distinctBy { it.id } else page.items
        copy(
            activityLog = activityLog.copy(
                loading = false,
                loadingMore = false,
                items = rows,
                // A page shorter than asked for ends the log whatever the
                // total claims — a total that overstates would otherwise
                // leave "Scroll for more" pointing at nothing.
                total = if (page.items.size < ACTIVITY_PAGE) rows.size else maxOf(page.total, rows.size),
            ),
        )
    }

    // -- shared ------------------------------------------------------------------

    /** Sharing with yourself is meaningless; the pickers leave the viewer out. */
    private fun List<com.zillit.desktop.feature.drive.domain.DrivePerson>.exceptMe() =
        filterNot { it.id == state.viewer.userId }

    private fun isAccepted(file: PickedFile): Boolean = file.extension in ACCEPTED_EXTENSIONS

    private companion object {
        const val MAX_DROP_COUNT = 100
        const val REJECTED_SHOWN = 3
        const val ACTIVITY_PAGE = 50
        const val PDF_PAGE_WIDTH = 1100
        const val INLINE_IMAGE_MAX = 45L * 1024 * 1024
        const val INLINE_DOCUMENT_MAX = 80L * 1024 * 1024
        const val INLINE_TEXT_MAX = 2L * 1024 * 1024
        val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}

/**
 * The web's `UPLOAD_ACCEPTED_TYPES`, as bare extensions, plus compressed
 * archives — a production hands over whole folders of paperwork as a .zip,
 * and refusing it sent people off to unzip and re-upload file by file.
 */
val ACCEPTED_EXTENSIONS: Set<String> = setOf(
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "ods", "odp",
    "csv", "xml", "json", "md",
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif",
    "mp4", "mov", "avi", "mkv", "wmv", "flv", "webm", "m4v",
    "mp3", "wav", "aac", "flac", "ogg", "m4a", "mpeg",
    "zip", "rar", "7z", "tar", "gz", "tgz",
)

/**
 * The type an archive is uploaded as. The JVM's name-based guess knows .zip
 * and nothing else here, so a .rar or .7z went up as a bare octet-stream.
 */
fun archiveMimeType(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
    "zip" -> "application/zip"
    "rar" -> "application/vnd.rar"
    "7z" -> "application/x-7z-compressed"
    "tar" -> "application/x-tar"
    "gz", "tgz" -> "application/gzip"
    else -> null
}

/**
 * The floor each person's access sits on, out of an access list.
 *
 * A row the server marked `inherited` came from a folder above, and
 * `inherited_role` is what that folder gives. A file's inherited rows carry
 * the flags instead of a role, so those are read back as the role they amount
 * to — read as a role they would all be Viewer. Rows the item granted itself
 * set no floor: those are this screen's to change.
 */
internal fun List<DriveAccessEntry>.floors(): Map<String, DriveRole> = filter { it.inherited }
    .associate { it.userId to (it.inheritedRole ?: DriveRole.of(it.permissions)) }
