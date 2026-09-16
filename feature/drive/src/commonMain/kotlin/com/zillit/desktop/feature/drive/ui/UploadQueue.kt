package com.zillit.desktop.feature.drive.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.drive.domain.NewFolder
import com.zillit.desktop.feature.drive.domain.QueuedUpload
import com.zillit.desktop.feature.drive.domain.UploadPlan
import com.zillit.desktop.feature.drive.domain.UploadState
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
            vm.reportFailure("You do not have permission to upload to this drive.")
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
            vm.notice("Started uploading ${accepted.size} file" + if (accepted.size == 1) "" else "s")
        }

        vm.run {
            val folderIds = if (directories.isEmpty()) {
                emptyMap()
            } else {
                createFolderTree(directories, target.folderId).also { vm.loadListing() }
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
                "$failures folder" + (if (failures == 1) "" else "s") +
                    " could not be created — those files will be placed at the destination root.",
            )
        }
        return ids.filterKeys { it.isNotEmpty() }
    }

    private fun start(queued: QueuedUpload, file: PickedFile, target: UploadTarget) {
        jobs[queued.id] = vm.run {
            permits.withPermit {
                if (vm.state.value.uploads.none { it.id == queued.id }) return@withPermit
                val result = uploader.upload(
                    file = file,
                    target = target,
                    onProgress = { uploaded, total ->
                        patch(queued.id) { copy(state = UploadState.InProgress(uploaded, total)) }
                    },
                )
                val settled = when (result) {
                    is ZillitResult.Success -> UploadState.Done(result.data.id)
                    is ZillitResult.Failure -> UploadState.Failed(result.error.localised())
                }
                patch(queued.id) { copy(state = settled) }
                jobs.remove(queued.id)
                // Only the scope the file landed in is refreshed, and only
                // while the user is still looking at it — a completed
                // background upload must not yank the listing out from
                // under someone browsing elsewhere.
                if (settled is UploadState.Done && !vm.state.value.showTrash) vm.loadListing()
                if (settled is UploadState.Failed) vm.reportFailure("Upload failed: ${file.name} — ${settled.reason}")
            }
        }
    }

    /** Stops one upload; the server's session is left for its 24-hour TTL to reap. */
    fun cancel(uploadId: String) {
        jobs.remove(uploadId)?.cancel()
        patch(uploadId) { copy(state = UploadState.Failed("Cancelled")) }
    }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    private fun patch(uploadId: String, change: QueuedUpload.() -> QueuedUpload) = vm.update {
        copy(uploads = uploads.map { row -> if (row.id == uploadId) row.change() else row })
    }

    private companion object {
        const val PARALLEL_FILES = 3
    }
}
