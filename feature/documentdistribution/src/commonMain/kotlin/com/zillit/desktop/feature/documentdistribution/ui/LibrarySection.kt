package com.zillit.desktop.feature.documentdistribution.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.permissions.RightsArea
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.documentdistribution.domain.FileKind
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LibraryFolder
import com.zillit.desktop.feature.documentdistribution.domain.LocalFile
import com.zillit.desktop.feature.documentdistribution.domain.PublishDraft
import com.zillit.desktop.feature.documentdistribution.domain.PublishMode
import com.zillit.desktop.feature.documentdistribution.domain.PublishedFile
import com.zillit.desktop.feature.documentdistribution.domain.PublishTarget
import com.zillit.desktop.feature.documentdistribution.domain.Thumbnails
import com.zillit.desktop.feature.documentdistribution.domain.UploadBatch
import com.zillit.desktop.feature.documentdistribution.domain.needsGeneratedThumbnail
import com.zillit.desktop.feature.documentdistribution.domain.thumbnailFileName
import com.zillit.desktop.feature.documentdistribution.domain.SupportedUploads
import com.zillit.desktop.feature.documentdistribution.domain.fileKindOf
import com.zillit.desktop.feature.documentdistribution.domain.isPdf
import com.zillit.desktop.feature.documentdistribution.domain.summariseFileNames
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The library's own actions: folders, uploads, the preview, the selection,
 * moving and publishing.
 *
 * Every write reloads the listing afterwards rather than patching it: the
 * server assigns ordering, date buckets and per-day counts, and a locally
 * patched row disagrees with all three until the next refresh.
 */
/**
 * The one server answer this screen acts on rather than merely reporting:
 * the live file picked for a replacement is gone.
 */
private fun ZillitError.isStaleReplaceTarget(): Boolean =
    this is ZillitError.Http && serverMessage == "publication_replace_target_not_found"

@Suppress("TooManyFunctions") // One handler per user action.
internal class LibrarySection(private val vm: VmScope) {

    // -- folders -----------------------------------------------------------

    fun openNewFolder() {
        if (vm.refusesWrite()) return
        val parent = vm.state.currentFolder
        vm.update {
            copy(
                folderEditor = FolderEditorState(
                    parent = parent,
                    // The parent's production day, else today — the web's default.
                    folderDate = parent?.folderDate?.takeIf { it.isNotBlank() } ?: vm.today().toString(),
                ),
            )
        }
    }

    fun openEditFolder(folderId: String) {
        if (vm.refusesWrite()) return
        val folder = vm.state.folders.firstOrNull { it.id == folderId } ?: return
        vm.update {
            copy(
                folderEditor = FolderEditorState(
                    folderId = folder.id,
                    name = folder.name,
                    description = folder.description,
                    folderDate = folder.folderDate,
                ),
            )
        }
    }

    fun editFolder(change: FolderEditorState.() -> FolderEditorState) =
        vm.update { copy(folderEditor = folderEditor?.change()) }

    fun saveFolder() {
        val editor = vm.state.folderEditor ?: return
        if (!editor.canSave) return
        if (vm.refusesWrite()) return
        vm.update { copy(folderEditor = editor.copy(saving = true)) }
        vm.run {
            val result = if (editor.isNew) {
                vm.repository.createFolder(
                    name = editor.name.trim(),
                    parentId = editor.parent?.id,
                    description = editor.description,
                    folderDate = editor.folderDate,
                )
            } else {
                vm.repository.updateFolder(editor.folderId.orEmpty(), editor.name.trim(), editor.description)
            }
            when (result) {
                is ZillitResult.Success -> {
                    vm.update { copy(folderEditor = null) }
                    vm.notice(str(if (editor.isNew) S.desktop_drive_activity_folder_created else S.saved))
                    vm.loadLibrary()
                }
                is ZillitResult.Failure -> {
                    vm.update { copy(folderEditor = folderEditor?.copy(saving = false)) }
                    vm.report(result.error)
                }
            }
        }
    }

    fun confirmDeleteFolder(folderId: String) {
        if (vm.refusesWrite()) return
        val folder = vm.state.folders.firstOrNull { it.id == folderId } ?: return
        vm.update {
            copy(
                prompt = DocDistPrompt(
                    title = str(S.drive_delete_item_title_format, folder.name),
                    message = str(S.desktop_docdist_delete_folder_message),
                    confirmLabel = str(S.delete),
                    event = DocDistEvent.DeleteFolder(folderId),
                ),
            )
        }
    }

    fun deleteFolder(folderId: String) {
        if (vm.refusesWrite()) return
        vm.run {
            vm.onSuccess(vm.repository.deleteFolder(folderId)) {
                // Standing inside a folder that no longer exists lists nothing
                // and offers no way out but the breadcrumb, so step up first.
                val parent = vm.state.currentFolder?.parentId
                vm.update {
                    copy(
                        currentFolderId = if (currentFolderId == folderId) parent else currentFolderId,
                        selectedFolderIds = selectedFolderIds - folderId,
                    )
                }
                vm.notice(str(S.desktop_drive_activity_folder_deleted))
                vm.loadLibrary()
            }
        }
    }

    // -- documents -----------------------------------------------------------

    fun confirmDeleteDocument(documentId: String) {
        if (vm.refusesWrite()) return
        val document = vm.state.documents.firstOrNull { it.id == documentId } ?: return
        vm.update {
            copy(
                prompt = DocDistPrompt(
                    title = str(S.drive_delete_item_title_format, document.name),
                    message = str(S.desktop_docdist_delete_file_message),
                    confirmLabel = str(S.delete),
                    event = DocDistEvent.DeleteDocument(documentId),
                ),
            )
        }
    }

    fun deleteDocument(documentId: String) {
        if (vm.refusesWrite()) return
        vm.run {
            vm.onSuccess(vm.repository.deleteDocument(documentId)) {
                vm.update {
                    copy(
                        selectedDocumentIds = selectedDocumentIds - documentId,
                        preview = preview?.takeIf { it.document.id != documentId },
                    )
                }
                vm.notice(str(S.drive_deleted_default))
                vm.loadLibrary()
            }
        }
    }

    fun confirmDeleteSelection() {
        if (vm.refusesWrite()) return
        val count = vm.state.selectionCount
        if (count == 0) return
        vm.update {
            copy(
                prompt = DocDistPrompt(
                    title = plural(count, S.drive_delete_count_title_singular, S.drive_delete_count_title_plural),
                    message = if (selectedFolderIds.isNotEmpty()) {
                        str(S.desktop_docdist_delete_selection_with_folders)
                    } else {
                        str(S.desktop_cannot_be_undone)
                    },
                    confirmLabel = str(S.delete),
                    event = DocDistEvent.DeleteSelection,
                ),
            )
        }
    }

    fun deleteSelection() {
        if (vm.refusesWrite()) return
        val folders = vm.state.selectedFolderIds.toList()
        val documents = vm.state.selectedDocumentIds.toList()
        val count = folders.size + documents.size
        if (count == 0) return
        vm.run {
            for (id in folders) {
                val result = vm.repository.deleteFolder(id)
                if (result is ZillitResult.Failure) return@run finishDelete(result.error)
            }
            for (id in documents) {
                val result = vm.repository.deleteDocument(id)
                if (result is ZillitResult.Failure) return@run finishDelete(result.error)
            }
            vm.update { copy(selectedFolderIds = emptySet(), selectedDocumentIds = emptySet()) }
            vm.notice(plural(count, S.desktop_docdist_deleted_one_item, S.desktop_docdist_deleted_items))
            vm.loadLibrary()
        }
    }

    private fun finishDelete(error: ZillitError) {
        vm.report(error)
        vm.loadLibrary()
    }

    // -- selection -----------------------------------------------------------

    fun selectAll(selected: Boolean) = vm.update {
        if (selected) {
            copy(
                selectedFolderIds = visibleSubfolders.map { it.id }.toSet(),
                selectedDocumentIds = documents.map { it.id }.toSet(),
            )
        } else {
            copy(selectedFolderIds = emptySet(), selectedDocumentIds = emptySet())
        }
    }

    /**
     * The documents a selection stands for: the ticked documents plus every
     * document under the ticked folders, fetched on demand because the paged
     * listing cannot be relied on to hold them.
     */
    suspend fun resolveSelection(): ZillitResult<List<LibraryDocument>> {
        val state = vm.state
        val folderIds = state.selectedFolderIds.flatMap { state.folderWithDescendants(it) }.toSet()
        val direct = state.selectedDocuments
        if (folderIds.isEmpty()) return ZillitResult.Success(direct)
        return when (val inFolders = vm.repository.documentsInFolders(folderIds)) {
            is ZillitResult.Failure -> inFolders
            is ZillitResult.Success -> ZillitResult.Success((inFolders.data + direct).distinctBy { it.id })
        }
    }

    suspend fun resolveFolder(folderId: String): ZillitResult<List<LibraryDocument>> =
        vm.repository.documentsInFolders(vm.state.folderWithDescendants(folderId))

    /** Runs a cross-folder fetch behind a "Preparing documents…" notice. */
    fun prepare(fetch: suspend () -> ZillitResult<List<LibraryDocument>>, then: (List<LibraryDocument>) -> Unit) {
        vm.update { copy(busy = str(S.desktop_docdist_preparing_documents)) }
        vm.run {
            val result = fetch()
            vm.update { copy(busy = null) }
            vm.onSuccess(result, then)
        }
    }

    // -- uploads ---------------------------------------------------------------

    fun pickAndUpload() {
        if (vm.refusesWrite()) return
        if (vm.state.currentFolder == null) {
            vm.fail(str(S.desktop_docdist_open_a_folder_first))
            return
        }
        vm.run { uploadFiles(vm.host.pickFiles()) }
    }

    fun dropFiles(files: List<LocalFile>) {
        vm.update { copy(dragHover = false) }
        if (vm.state.currentFolder == null) return vm.fail(str(S.desktop_docdist_drop_folder_hint))
        if (vm.refusesWrite()) return
        vm.run { uploadFiles(files) }
    }

    private suspend fun uploadFiles(raw: List<LocalFile>) {
        if (raw.isEmpty()) return
        val folderId = vm.state.currentFolderId
        val (accepted, rejected) = SupportedUploads.partition(raw)
        if (rejected.isNotEmpty()) {
            vm.fail(str(S.desktop_docdist_skipped_unsupported, summariseFileNames(rejected.map { it.name })))
        }
        if (accepted.isEmpty()) return
        vm.update { copy(upload = UploadProgress(UploadProgress.Stage.Preparing)) }
        // One batch for the whole action, so the backend raises ONE notification
        // instead of one per file.
        val batch = UploadBatch.create(accepted.size)
        var uploaded = 0
        try {
            accepted.forEachIndexed { index, file ->
                vm.update {
                    copy(upload = UploadProgress(UploadProgress.Stage.Uploading, file.name, index, accepted.size))
                }
                when (val result = sendFile(file, folderId, vm.today().toString(), batch)) {
                    is ZillitResult.Success -> uploaded++
                    is ZillitResult.Failure -> {
                        // Only SUCCESSFUL requests count towards the backend's tally, so a
                        // failure comes off the promised total or the notice waits for its fallback.
                        batch.drop()
                        vm.fail(str(S.desktop_docdist_failed_to_upload, file.name, result.error.userMessage))
                    }
                }
            }
        } finally {
            vm.update { copy(upload = null) }
        }
        if (uploaded > 0) {
            vm.notice(
                plural(uploaded, S.desktop_docdist_uploaded_one_file, S.desktop_docdist_uploaded_files),
            )
        }
        vm.loadLibrary()
    }

    /**
     * Puts one file into the library, rendering a PDF's first page as its card
     * cover on the way.
     *
     * Best-effort and under a deadline: a missing cover costs a card its
     * picture, but a thrown error — or a damaged PDF that never settles — would
     * cost the user their upload, so every failure here simply yields no cover.
     */
    suspend fun sendFile(
        file: LocalFile,
        folderId: String?,
        documentDate: String?,
        batch: UploadBatch?,
    ): ZillitResult<LibraryDocument> {
        val cover = if (needsGeneratedThumbnail(file.contentType, file.name)) {
            withTimeoutOrNull(Thumbnails.DEADLINE_MILLIS) { vm.host.pdfThumbnail(file.bytes) }
                ?.let { LocalFile(thumbnailFileName(file.name), "image/jpeg", it) }
        } else {
            null
        }
        return vm.repository.uploadDocument(file, folderId, documentDate, batch, cover)
    }

    // -- preview and download ----------------------------------------------------

    fun openPreview(documentId: String) {
        val document = vm.state.documents.firstOrNull { it.id == documentId }
            ?: return vm.fail(str(S.desktop_docdist_file_no_longer_in_folder))
        vm.update { copy(preview = PreviewState(document)) }
        vm.run {
            val bytes = when (val fetched = vm.repository.documentBytes(document)) {
                is ZillitResult.Success -> fetched.data
                is ZillitResult.Failure -> {
                    vm.update { copy(preview = preview?.copy(loading = false, error = fetched.error.userMessage)) }
                    return@run
                }
            }
            val kind = fileKindOf(document.contentType, document.name)
            val count = if (kind == FileKind.Pdf) vm.host.pdfPageCount(bytes) else 0
            // A host that cannot count draws every page at once, as before.
            val eager = if (kind == FileKind.Pdf && count == 0) {
                (vm.host.renderPdfPages(bytes, PREVIEW_PAGE_WIDTH) as? ZillitResult.Success)?.data.orEmpty()
            } else {
                emptyList()
            }
            val text = if (kind == FileKind.VCard) bytes.decodeToString() else null
            vm.update {
                // The dialog may have been closed, or moved to another file,
                // while the bytes were in flight.
                if (preview?.document?.id != document.id) this
                else copy(
                    preview = preview.copy(
                        loading = false,
                        bytes = bytes,
                        pages = eager,
                        pageCount = count,
                        text = text,
                    ),
                )
            }
            if (count > 0) drawPages(document.id, bytes, count)
        }
    }

    /**
     * Draws the pages one at a time, in order, each landing in the state as it is
     * done: the first is on screen in a moment and the rest follow while the
     * reader is already reading it. Stops when the dialog closes or moves on.
     */
    private suspend fun drawPages(documentId: String, pdf: ByteArray, count: Int) {
        for (page in 1..count) {
            if (vm.state.preview?.document?.id != documentId) return
            val png = vm.host.renderPdfPage(pdf, page, PREVIEW_PAGE_WIDTH) ?: return
            vm.update {
                if (preview?.document?.id != documentId) {
                    this
                } else {
                    copy(preview = preview.copy(pages = preview.pages + png))
                }
            }
        }
    }

    fun download(documentId: String) {
        if (vm.refusesDownload()) return
        val document = vm.state.documents.firstOrNull { it.id == documentId }
            ?: vm.state.preview?.document?.takeIf { it.id == documentId }
            ?: return vm.fail(str(S.desktop_docdist_file_no_longer_in_folder))
        vm.update { copy(
            preview = preview?.takeIf { it.document.id == documentId }?.copy(downloading = true) ?: preview,
        ) }
        vm.run {
            val bytes = vm.state.preview?.takeIf { it.document.id == documentId }?.bytes
                ?: when (val fetched = vm.repository.documentBytes(document)) {
                    is ZillitResult.Success -> fetched.data
                    is ZillitResult.Failure -> {
                        vm.update { copy(preview = preview?.copy(downloading = false)) }
                        vm.report(fetched.error)
                        return@run
                    }
                }
            saveAndOpen(document.name, bytes)
            vm.update { copy(preview = preview?.copy(downloading = false)) }
        }
    }

    /** Writes into Downloads, opens it, and says where it went. */
    suspend fun saveAndOpen(fileName: String, bytes: ByteArray) {
        when (val saved = vm.host.saveToDownloads(fileName, bytes)) {
            is ZillitResult.Success -> {
                vm.host.openFile(saved.data)
                vm.notice(str(S.docusign_bulk_example_saved, fileName))
            }
            is ZillitResult.Failure -> vm.report(saved.error)
        }
    }

    /** The preview's "Remove this record" for a file whose bytes are gone. */
    fun removeMissingRecord() {
        val document = vm.state.preview?.document ?: return
        if (vm.refusesWrite()) return
        vm.run {
            vm.onSuccess(vm.repository.deleteDocument(document.id)) {
                vm.update { copy(preview = null) }
                vm.notice(str(S.desktop_docdist_removed_broken_record))
                vm.loadLibrary()
            }
        }
    }

    // -- move ------------------------------------------------------------------

    fun openMove() {
        if (vm.refusesWrite()) return
        vm.update { copy(moveTarget = MoveTargetState(destinationId = currentFolderId)) }
    }

    fun moveSelection(folderId: String?, closesDialog: Boolean) {
        val folders = vm.state.selectedFolderIds.toList()
        val documents = vm.state.selectedDocumentIds.toList()
        val moved = folders.size + documents.size
        if (moved == 0) return
        if (vm.refusesWrite()) return
        if (folders.any { it == folderId }) {
            vm.fail(str(S.desktop_drive_folder_into_itself))
            return
        }
        // The dialog bars the root row when files are selected; this is the
        // backstop behind it, because the destination also arrives from paths
        // the dialog does not own.
        if (documents.isNotEmpty() && folderId == null) {
            vm.fail(str(S.desktop_docdist_files_must_be_in_folder))
            return
        }
        if (closesDialog) vm.update { copy(moveTarget = moveTarget?.copy(saving = true)) }
        vm.run {
            val failure = moveParts(folders, documents, folderId)
            if (failure != null) {
                vm.update { copy(moveTarget = moveTarget?.copy(saving = false)) }
                vm.report(failure)
                return@run
            }
            vm.update { copy(selectedFolderIds = emptySet(), selectedDocumentIds = emptySet(), moveTarget = null) }
            vm.notice(plural(moved, S.desktop_docdist_moved_one_item, S.dd_moved_ok))
            vm.loadLibrary()
        }
    }

    /**
     * Folders go first: reparenting a folder rewrites the tree the documents
     * are being placed into, and doing it the other way round can land a
     * document in a folder that is about to move out from under it.
     */
    private suspend fun moveParts(folders: List<String>, documents: List<String>, folderId: String?): ZillitError? {
        if (folders.isNotEmpty()) {
            val result = vm.repository.moveFolders(folders, folderId)
            if (result is ZillitResult.Failure) return result.error
        }
        if (documents.isNotEmpty()) {
            val result = vm.repository.moveDocuments(documents, folderId)
            if (result is ZillitResult.Failure) return result.error
        }
        return null
    }

    // -- publish ---------------------------------------------------------------

    fun publishSelection() {
        if (vm.refusesWrite()) return
        if (vm.state.selectionCount == 0) return vm.fail(str(S.desktop_docdist_choose_document_to_publish))
        val folder = vm.state.singleSelectedFolder()
        // Only documents ticked: nothing to fetch, so the dialog opens at once.
        if (vm.state.selectedFolderIds.isEmpty()) return openPublish(vm.state.selectedDocuments, null)
        prepare(::resolveSelection) { documents -> openPublish(documents, folder) }
    }

    fun publishDocument(documentId: String) {
        if (vm.refusesWrite()) return
        val document = vm.state.documents.firstOrNull { it.id == documentId } ?: return
        openPublish(listOf(document), null)
    }

    fun publishFolder(folderId: String) {
        if (vm.refusesWrite()) return
        val folder = vm.state.folders.firstOrNull { it.id == folderId } ?: return
        prepare({ resolveFolder(folderId) }) { documents ->
            if (documents.isEmpty()) vm.fail(str(S.desktop_docdist_folder_is_empty)) else openPublish(documents, folder)
        }
    }

    private fun openPublish(documents: List<LibraryDocument>, folder: LibraryFolder?) {
        if (documents.isEmpty()) return vm.fail(str(S.desktop_docdist_choose_document_to_publish))
        vm.update {
            copy(
                publish = PublishState(
                    draft = PublishDraft(
                        documentIds = documents.map { it.id },
                        folderId = folder?.id,
                    ),
                    files = documents,
                    folder = folder,
                ),
            )
        }
    }

    /**
     * Switching destination clears the fields the previous one collected.
     *
     * They are not interchangeable — a scene number typed for Pages is not a
     * D.O.D name — and carrying them across is how a stale value gets sent
     * to an endpoint that reads a different key.
     */
    fun choosePublishTarget(category: String) {
        val target = PublishTarget.of(category) ?: return
        val open = vm.state.publish ?: return
        // Every destination is offered, so a disabled one can be picked. Say
        // why here rather than letting the server answer it at the end of a
        // filled-in form — the inline message in the rail says the same thing.
        val access = vm.state.viewer.accessFor(category)
        if (!access.enabled) vm.fail(str(S.dd_publish_tool_not_enabled, target.label))
        vm.update {
            copy(
                publish = open.copy(
                    target = target,
                    // The ticks are re-derived against the new destination's
                    // own rules; everything else starts clean.
                    draft = PublishDraft(
                        documentIds = open.ticksFor(target),
                        folderId = open.draft.folderId,
                    ),
                    alreadyPublished = emptyList(),
                    loadingPublished = target.republishable,
                    openCard = null,
                ),
            )
        }
        if (!target.republishable) return
        vm.run {
            val published = vm.repository.publishedFiles(category)
            applyPublished(category, (published as? ZillitResult.Success)?.data.orEmpty())
        }
    }

    /**
     * What came back from the published-files read, and the mode it implies.
     *
     * Nothing live means a first publish, which sends no `mode` at all; one or
     * more means the options show and Addition is the default the web starts on.
     */
    private fun applyPublished(category: String, published: List<PublishedFile>) {
        vm.update {
            val open = publish?.takeIf { it.target?.category == category } ?: return@update this
            copy(
                publish = open.copy(
                    loadingPublished = false,
                    alreadyPublished = published,
                    draft = open.draft.copy(
                        mode = if (published.isEmpty()) null else PublishMode.Add,
                        replaceChatId = null,
                    ),
                ),
            )
        }
    }

    /** Opens or closes one rail tile's destination menu. */
    fun openPublishCard(key: String?) {
        vm.update { copy(publish = publish?.copy(openCard = key)) }
    }

    /**
     * Tick or untick one row.
     *
     * A single-file destination behaves like a radio: ticking a row replaces
     * the selection, ticking the ticked one clears it. A row the destination
     * does not accept cannot be ticked at all.
     */
    fun togglePublishFile(documentId: String) {
        val open = vm.state.publish ?: return
        val document = open.files.firstOrNull { it.id == documentId } ?: return
        if (!open.canTick(document)) return
        val current = open.draft.documentIds
        val next = when {
            open.target?.singleFile == true -> if (documentId in current) emptyList() else listOf(documentId)
            documentId in current -> current - documentId
            else -> current + documentId
        }
        setTicks(next)
    }

    /** Select all / Deselect all, over the rows the destination allows. */
    fun toggleAllPublishFiles() {
        val open = vm.state.publish ?: return
        val selectable = open.selectable.map { it.id }
        setTicks(if (open.draft.documentIds.size == selectable.size) emptyList() else selectable)
    }

    /**
     * Writes a new tick set, falling back off Replace when it stops being one
     * file for one file.
     *
     * The web says so out loud rather than disabling the radio silently: the
     * person ticked a second file on purpose, and a request the backend would
     * refuse with `publication_replace_single_only` is not the answer.
     */
    private fun setTicks(ids: List<String>) {
        val open = vm.state.publish ?: return
        val leavesReplace = open.draft.mode == PublishMode.Replace && ids.size != 1
        if (leavesReplace) {
            vm.notice(
                str(
                    S.dd_publish_replace_switched_to_addition,
                    PublishMode.Add.label(open.target?.label.orEmpty()),
                ),
            )
        }
        vm.update {
            copy(
                publish = publish?.copy(
                    draft = publish.draft.copy(
                        documentIds = ids,
                        mode = if (leavesReplace) PublishMode.Add else publish.draft.mode,
                        replaceChatId = if (leavesReplace) null else publish.draft.replaceChatId,
                    ),
                ),
            )
        }
    }

    /** Addition / Replace / Publish as a New File. */
    fun setPublishMode(mode: PublishMode) {
        vm.update {
            val open = publish ?: return@update this
            copy(
                publish = open.copy(
                    draft = open.draft.copy(
                        mode = mode,
                        // Leaving a target picked behind an Addition would
                        // send a replacement the server acts on.
                        replaceChatId = if (mode == PublishMode.Replace) open.draft.replaceChatId else null,
                    ),
                ),
            )
        }
    }

    fun chooseReplaceTarget(chatId: String?) {
        vm.update { copy(publish = publish?.copy(draft = publish.draft.copy(replaceChatId = chatId))) }
    }

    fun setPublishDisplayName(documentId: String, name: String) {
        vm.update {
            val open = publish ?: return@update this
            val names = open.draft.displayNames.toMutableMap()
            if (name.isBlank()) names.remove(documentId) else names[documentId] = name.take(DISPLAY_NAME_MAX)
            copy(publish = open.copy(draft = open.draft.copy(displayNames = names)))
        }
    }

    /**
     * The ticks that survive a change of destination.
     *
     * A PDF-only destination drops the rest, and a single-file one keeps the
     * first — so a selection made under Info cannot be carried into Script
     * and refused there.
     */
    private fun PublishState.ticksFor(target: PublishTarget): List<String> {
        val kept = draft.documentIds.filter { id ->
            val document = files.firstOrNull { it.id == id } ?: return@filter false
            !target.pdfOnly || document.isPdf()
        }
        return if (target.singleFile) kept.take(1) else kept
    }

    fun confirmPublish() {
        val open = vm.state.publish
        val target = open?.target ?: return
        if (refusesDestination(target)) return
        val problem = open.problem(vm.state.viewer.isTelevision)
        if (problem != null) return vm.fail(problem)
        if (vm.refusesWrite()) return
        val draft = if (open.offersMode) open.draft else open.draft.copy(mode = null, replaceChatId = null)
        vm.update { copy(publish = publish?.copy(saving = true)) }
        vm.run {
            when (val result = vm.repository.publish(target.category, draft)) {
                is ZillitResult.Success -> {
                    val count = draft.documentIds.size
                    vm.update { copy(publish = null, selectedDocumentIds = emptySet(), selectedFolderIds = emptySet()) }
                    vm.notice(
                        str(
                            if (count == 1) {
                                S.desktop_docdist_published_one_file_to
                            } else {
                                S.desktop_docdist_published_files_to
                            },
                            count,
                            target.label,
                        ),
                    )
                    vm.reload()
                }
                is ZillitResult.Failure -> {
                    vm.update { copy(publish = publish?.copy(saving = false)) }
                    vm.report(result.error)
                    refetchAfterStaleTarget(result.error, target.category)
                }
            }
        }
    }

    /**
     * Somebody replaced or deleted the picked file while this form was open.
     *
     * The pick is cleared and the list re-read, so the next press chooses from
     * what is actually there instead of sending the same dead id again.
     */
    private fun refetchAfterStaleTarget(error: ZillitError, category: String) {
        if (!error.isStaleReplaceTarget()) return
        vm.update { copy(publish = publish?.copy(loadingPublished = true, alreadyPublished = emptyList())) }
        vm.run {
            val published = vm.repository.publishedFiles(category)
            applyPublished(category, (published as? ZillitResult.Success)?.data.orEmpty())
        }
    }

    /**
     * The destination's own posting right, not this tool's.
     *
     * The button stays pressable without it so the press can offer the ask —
     * and the ask has to name the *receiving* tool, not Document
     * Distribution. A module the production never switched on has no row to
     * grant, so there the honest answer is why, not an ask nobody can meet.
     */
    private fun refusesDestination(target: PublishTarget): Boolean {
        val access = vm.state.viewer.accessFor(target.category)
        if (access.canPost) return false
        if (access.enabled) requestPublishRights() else vm.fail(str(S.dd_publish_tool_not_enabled, target.label))
        return true
    }

    /** Ask an admin for posting rights on whichever destination is selected. */
    fun requestPublishRights() {
        val target = vm.state.publish?.target ?: return
        vm.askPostingRightsOn(
            target.label,
            // The Call Sheet destination is a home unit, and its row is on the
            // grid's Home tab; every other destination is a film tool.
            if (target.toolIdentifier == null) RightsArea.Home else RightsArea.Tools,
        )
    }

    private companion object {
        /** The web's `maxLength` on the display-name field. */
        const val DISPLAY_NAME_MAX = 120

        /** Wide enough to read a call sheet, small enough to rasterise quickly. */
        /** Wide enough to stay sharp on a high-density screen at full window width. */
        const val PREVIEW_PAGE_WIDTH = 1800
    }
}
