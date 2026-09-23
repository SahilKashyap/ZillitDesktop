package com.zillit.desktop.feature.documentdistribution.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.documentdistribution.domain.FileKind
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LibraryFolder
import com.zillit.desktop.feature.documentdistribution.domain.LocalFile
import com.zillit.desktop.feature.documentdistribution.domain.PublishDraft
import com.zillit.desktop.feature.documentdistribution.domain.PublishTarget
import com.zillit.desktop.feature.documentdistribution.domain.SupportedUploads
import com.zillit.desktop.feature.documentdistribution.domain.fileKindOf
import com.zillit.desktop.feature.documentdistribution.domain.summariseFileNames

/**
 * The library's own actions: folders, uploads, the preview, the selection,
 * moving and publishing.
 *
 * Every write reloads the listing afterwards rather than patching it: the
 * server assigns ordering, date buckets and per-day counts, and a locally
 * patched row disagrees with all three until the next refresh.
 */
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
        if (vm.state.currentFolder == null) return
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
        var uploaded = 0
        try {
            accepted.forEachIndexed { index, file ->
                vm.update {
                    copy(upload = UploadProgress(UploadProgress.Stage.Uploading, file.name, index, accepted.size))
                }
                when (val result = vm.repository.uploadDocument(file, folderId, vm.today().toString())) {
                    is ZillitResult.Success -> uploaded++
                    is ZillitResult.Failure ->
                        vm.fail(str(S.desktop_docdist_failed_to_upload, file.name, result.error.userMessage))
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
            val pages = if (kind == FileKind.Pdf) {
                (vm.host.renderPdfPages(bytes, PREVIEW_PAGE_WIDTH) as? ZillitResult.Success)?.data.orEmpty()
            } else {
                emptyList()
            }
            val text = if (kind == FileKind.VCard) bytes.decodeToString() else null
            vm.update {
                // The dialog may have been closed, or moved to another file,
                // while the bytes were in flight.
                if (preview?.document?.id != document.id) this
                else copy(preview = preview.copy(loading = false, bytes = bytes, pages = pages, text = text))
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
                    draft = PublishDraft(documentIds = documents.map { it.id }),
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
        vm.update {
            copy(
                publish = open.copy(
                    target = target,
                    draft = PublishDraft(documentIds = open.draft.documentIds),
                    alreadyPublished = emptyList(),
                    loadingPublished = target.republishable,
                ),
            )
        }
        if (!target.republishable) return
        vm.run {
            val published = vm.repository.publishedFiles(category)
            vm.update {
                copy(
                    publish = publish?.copy(
                        loadingPublished = false,
                        alreadyPublished = (published as? ZillitResult.Success)?.data.orEmpty(),
                    ),
                )
            }
        }
    }

    fun confirmPublish() {
        val open = vm.state.publish ?: return
        val target = open.target ?: return
        val problem = open.problem(vm.state.viewer.isTelevision)
        if (problem != null) return vm.fail(problem)
        if (vm.refusesWrite()) return
        val draft = if (open.offersMode) open.draft else open.draft.copy(replaceChatIds = emptyList())
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
                }
            }
        }
    }

    private companion object {
        /** Wide enough to read a call sheet, small enough to rasterise quickly. */
        const val PREVIEW_PAGE_WIDTH = 1100
    }
}
