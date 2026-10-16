package com.zillit.desktop.feature.drive.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.drive.domain.CANCELLED
import com.zillit.desktop.feature.drive.domain.NewFolder
import com.zillit.desktop.feature.drive.domain.isDuplicateName
import com.zillit.desktop.feature.drive.domain.keepExtension
import com.zillit.desktop.feature.drive.domain.QueuedUpload
import com.zillit.desktop.feature.drive.domain.UploadCheckpoint
import com.zillit.desktop.feature.drive.domain.UploadPlan
import com.zillit.desktop.feature.drive.domain.UploadState
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The background uploads — the web's `useFileUpload` plus the folder-tree
 * step from `ensureFolderTreeForUpload`.
 *
 * ## Folders first, then files
 *
 * A folder pick or drop arrives as files with relative paths. The directory
 * paths implied by those are created top-down, parents before children, and
 * the listing is refreshed so the new folders appear *before* any bytes move
 * (the web's "Phase 1"). Each file then uploads into the folder its path
 * resolved to; a path whose folder could not be created falls back to the
 * destination root rather than failing the file.
 *
 * ## Three at a time
 *
 * The web's `MAX_PARALLEL_FILE_UPLOADS`. More and a 200-file drop opens 200
 * sessions at once; fewer and a folder of small files crawls.
 */
internal class UploadQueue(
    private val vm: DriveViewModel,
    private val uploader: DriveUploader,
    private val newUploadId: () -> String,
) {

    private val jobs = mutableMapOf<String, Job>()
    private val entries = mutableMapOf<String, Entry>()
    private val permits = Semaphore(PARALLEL_FILES)

    /**
     * Queues [files] into [target] and starts them.
     *
     * Refused up front on size and name (see [UploadPlan.rejectionReason]) so
     * an oversized file is rejected in the queue rather than after its first
     * chunk has been pushed and the completion has failed.
     */
    fun enqueue(files: List<PickedFile>, target: UploadTarget) {
        if (!vm.state.value.viewer.canCreate) {
            vm.reportFailure(str(S.desktop_drive_no_upload_permission))
            return
        }
        val accepted = files.filter { file ->
            val reason = UploadPlan.rejectionReason(file.name, file.sizeBytes)
            if (reason != null) vm.reportFailure(reason)
            reason == null
        }
        val directories = directoriesOf(accepted)
        if (accepted.isEmpty() && directories.isEmpty()) return

        val rows = accepted.map { file ->
            QueuedUpload(
                id = newUploadId(),
                fileName = file.name,
                sizeBytes = file.sizeBytes,
                destinationFolderId = target.folderId,
            )
        }
        vm.update { copy(uploads = uploads + rows, operationsExpanded = true) }
        if (accepted.isNotEmpty()) {
            vm.notice(
                if (accepted.size == 1) {
                    str(S.desktop_drive_started_uploading_one)
                } else {
                    str(S.desktop_drive_started_uploading_many, accepted.size)
                },
            )
        }

        vm.run {
            val folderIds = if (directories.isEmpty()) {
                emptyMap()
            } else {
                createFolderTree(directories, target.folderId).also {
                    vm.loadListing()
                    vm.mergeFolder(target.folderId)
                }
            }
            accepted.zip(rows).forEach { (file, row) ->
                val folder = folderIds[file.relativeDirectory] ?: target.folderId
                start(row.copy(destinationFolderId = folder), file, target.copy(folderId = folder))
            }
        }
    }

    /**
     * Every directory path the files imply, and each path's ancestors,
     * shallowest first — `collectUniqueUploadDirectories`.
     */
    private fun directoriesOf(files: List<PickedFile>): List<String> {
        val paths = mutableSetOf<String>()
        files.forEach { file ->
            val segments = file.relativeDirectory.split('/').filter { it.isNotBlank() }
            for (depth in 1..segments.size) paths += segments.take(depth).joinToString("/")
        }
        return paths.sortedWith(compareBy<String> { it.count { c -> c == '/' } }.thenBy { it })
    }

    /**
     * Creates the folders top-down, answering path → folder id. A parent
     * that failed cascades: its children are not attempted, and their files
     * land at the destination root.
     */
    private suspend fun createFolderTree(paths: List<String>, rootId: String?): Map<String, String?> {
        val ids = mutableMapOf<String, String?>("" to rootId)
        var failures = 0
        paths.forEach { path ->
            val parentPath = path.substringBeforeLast('/', "")
            val parentId = ids[parentPath]
            if (parentPath.isNotEmpty() && parentId == null) {
                ids[path] = null
                failures++
                return@forEach
            }
            val made = vm.repo.createFolder(NewFolder(name = path.substringAfterLast('/'), parentId = parentId))
            ids[path] = (made as? ZillitResult.Success)?.data?.id
            if (ids[path] == null) failures++
        }
        if (failures > 0) {
            vm.reportFailure(
                if (failures == 1) {
                    str(S.desktop_drive_folders_not_created_one)
                } else {
                    str(S.desktop_drive_folders_not_created_many, failures)
                },
            )
        }
        return ids.filterKeys { it.isNotEmpty() }
    }

    /** What a row needs to be sent again: the file, where it goes, and how far it got. */
    private class Entry(var file: PickedFile, val target: UploadTarget) {
        var checkpoint: UploadCheckpoint? = null
    }

    private fun start(queued: QueuedUpload, file: PickedFile, target: UploadTarget) {
        entries[queued.id] = Entry(file, target)
        launch(queued.id)
    }

    /**
     * Sends the row — from the start, or from its checkpoint after a pause or a
     * failure. Every settle goes through [patchLive]: a row the user has since
     * paused or cancelled keeps the state they gave it, whatever the cancelled
     * coroutine still manages to report on its way out.
     */
    private fun launch(id: String) {
        val entry = entries[id] ?: return
        jobs[id] = vm.run {
            permits.withPermit {
                if (vm.state.value.uploads.none { it.id == id }) return@withPermit
                val result = uploader.upload(
                    file = entry.file,
                    target = entry.target,
                    resume = entry.checkpoint,
                    onCheckpoint = { entry.checkpoint = it },
                    onProgress = { uploaded, total ->
                        patchLive(id) { copy(state = UploadState.InProgress(uploaded, total)) }
                    },
                )
                val settled = when (result) {
                    is ZillitResult.Success -> UploadState.Done(result.data.id)
                    is ZillitResult.Failure ->
                        UploadState.Failed(result.error.localised(), duplicateName = result.error.isDuplicateName())
                }
                patchLive(id) { copy(state = settled) }
                jobs.remove(id)
                // A finished upload has nothing left to resume. A failed one
                // keeps its entry — and its open session — for Retry.
                if (settled is UploadState.Done) entries.remove(id)
                // Only the scope the file landed in is refreshed, and only
                // while the user is still looking at it — a completed
                // background upload must not yank the listing out from
                // under someone browsing elsewhere.
                if (settled is UploadState.Done && !vm.state.value.showTrash) {
                    vm.loadListing()
                    // A file uploaded into someone else's folder is in no
                    // scope of this user's — it is not shared *with* them and
                    // it is not in their My Drive — and the server does not
                    // announce it back to the device that uploaded it either.
                    // Without this the uploader cannot see their own file.
                    vm.mergeFolder(entry.target.folderId)
                }
                if (settled is UploadState.Failed && settled.duplicateName) {
                    // The name is the only thing wrong, and the dialog says so and fixes it.
                    vm.update { copy(renameUploads = renameUploads.copy(open = true)) }
                } else if (settled is UploadState.Failed) {
                    vm.reportFailure(str(S.desktop_drive_upload_failed, entry.file.name, settled.reason))
                }
            }
        }
    }

    /**
     * Stops one upload where it is, keeping every part S3 has accepted.
     *
     * The part in flight is abandoned — the web does the same — so a pause
     * costs at most one part's worth of bytes. Refused once the completion call
     * is under way: cutting that off could leave the file assembled on the
     * server while the row says "paused", and a resume would then upload it twice.
     */
    fun pause(uploadId: String) {
        val row = vm.state.value.uploads.firstOrNull { it.id == uploadId } ?: return
        if (!row.canPause) return
        jobs.remove(uploadId)?.cancel()
        val checkpoint = entries[uploadId]?.checkpoint
        val total = checkpoint?.plan?.totalParts ?: (row.state as? UploadState.InProgress)?.totalParts ?: 0
        patch(uploadId) { copy(state = UploadState.Paused(checkpoint?.uploadedParts ?: 0, total)) }
    }

    /** Carries a paused upload on from its last accepted part. */
    fun resume(uploadId: String) {
        val row = vm.state.value.uploads.firstOrNull { it.id == uploadId } ?: return
        if (!row.isPaused || uploadId !in entries) return
        patch(uploadId) { copy(state = UploadState.Queued) }
        launch(uploadId)
    }

    /**
     * Tries a failed upload again — from its checkpoint when it had one, so a
     * dropped connection at part 40 of 50 costs ten parts, not fifty. A
     * cancelled upload is not retried: cancelling aborted its session.
     */
    fun retry(uploadId: String) {
        val row = vm.state.value.uploads.firstOrNull { it.id == uploadId } ?: return
        if (!row.canRetry || uploadId !in entries) return
        patch(uploadId) { copy(state = UploadState.Queued) }
        launch(uploadId)
    }

    /**
     * Sends the uploads the server refused for their names again, each under
     * the name chosen for it — from its checkpoint when it has one, so a file
     * whose bytes are all up costs a completion call, not an upload.
     */
    fun rename(renames: Map<String, String>) {
        renames.forEach { (uploadId, name) ->
            val entry = entries[uploadId] ?: return@forEach
            val row = vm.state.value.uploads.firstOrNull { it.id == uploadId } ?: return@forEach
            if (!row.needsRename) return@forEach
            entry.file = entry.file.copy(name = name)
            patch(uploadId) { copy(fileName = name, state = UploadState.Queued) }
            launch(uploadId)
        }
    }

    /**
     * Takes the dialog's names. Anything blank, unchanged or chosen twice for the
     * same folder stays in the dialog with a note against it; the rest go.
     */
    fun submitRenames() {
        val state = vm.state.value
        val rows = state.uploads.filter { it.needsRename }
        val chosen = rows.associate { it.id to keepExtension(state.renameValue(it), it.fileName) }
        val seen = mutableSetOf<Pair<String?, String>>()
        val errors = mutableMapOf<String, String>()
        rows.forEach { row ->
            val name = chosen.getValue(row.id)
            val repeated = !seen.add(row.destinationFolderId to name.lowercase())
            when {
                name.isBlank() -> errors[row.id] = str(S.drive_name_is_required)
                name.equals(row.fileName, ignoreCase = true) || repeated -> errors[row.id] = str(S.drive_duplicate_name)
            }
        }
        // The good names go; the others stay, with a note, for another try.
        vm.update {
            copy(
                renameUploads = if (errors.isEmpty()) {
                    RenameUploadsState()
                } else {
                    renameUploads.copy(errors = errors, names = renameUploads.names.filterKeys { it in errors })
                },
            )
        }
        rename(chosen - errors.keys)
    }

    /** "Cancel all": the uploads waiting on a name are given up, and their sessions dropped. */
    fun cancelRenames() {
        vm.state.value.uploads.filter { it.needsRename }.forEach { cancel(it.id) }
        vm.update { copy(renameUploads = RenameUploadsState()) }
    }

    /** Stops one upload and tells the server to drop its session. */
    fun cancel(uploadId: String) {
        val row = vm.state.value.uploads.firstOrNull { it.id == uploadId }
        jobs.remove(uploadId)?.cancel()
        patch(uploadId) { copy(state = UploadState.Failed(CANCELLED)) }
        // Not while finishing: the file may already be assembled, and an abort
        // arriving after that is either ignored or undoes it.
        val finishing = (row?.state as? UploadState.InProgress)?.isFinishing == true
        if (finishing) entries.remove(uploadId) else abortSession(uploadId)
    }

    /**
     * Rows removed from the panel without being retried: their sessions are
     * dropped rather than left open for the server's 24-hour reaper.
     */
    fun discard(uploadIds: Collection<String>) = uploadIds.forEach(::abortSession)

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        entries.clear()
    }

    private fun abortSession(uploadId: String) {
        val session = entries.remove(uploadId)?.checkpoint ?: return
        vm.run { vm.repo.abortUpload(session.uploadId) }
    }

    private fun patch(uploadId: String, change: QueuedUpload.() -> QueuedUpload) = vm.update {
        copy(uploads = uploads.map { row -> if (row.id == uploadId) row.change() else row })
    }

    /** As [patch], but only for a row that is still waiting or sending. */
    private fun patchLive(uploadId: String, change: QueuedUpload.() -> QueuedUpload) = vm.update {
        copy(
            uploads = uploads.map { row ->
                val live = row.state is UploadState.Queued || row.state is UploadState.InProgress ||
                    row.state is UploadState.Completing
                if (row.id == uploadId && live) row.change() else row
            },
        )
    }

    private companion object {
        const val PARALLEL_FILES = 3
    }
}
