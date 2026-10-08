package com.zillit.desktop.feature.documentdistribution.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.documentdistribution.domain.FolderEntry
import com.zillit.desktop.feature.documentdistribution.domain.LocalFile
import com.zillit.desktop.feature.documentdistribution.domain.LocalFolderTree
import com.zillit.desktop.feature.documentdistribution.domain.UploadBatch
import com.zillit.desktop.feature.documentdistribution.domain.UploadPlan
import com.zillit.desktop.feature.documentdistribution.domain.buildUploadPlan
import com.zillit.desktop.feature.documentdistribution.domain.countPlannedUnder
import com.zillit.desktop.feature.documentdistribution.domain.partitionEntries
import com.zillit.desktop.feature.documentdistribution.domain.summariseFileNames

/**
 * Upload a folder, and drop one at the library root (web `Library.jsx`
 * `stageFolderUpload` / `runFolderUpload`, 2026-09-29..10-07).
 *
 * A folder upload creates server-side folders and then uploads one file at a
 * time, so it is confirmed first: the tree to be created, the totals, and what
 * will be skipped. A folder may be dropped at the root — it brings its own name,
 * so there is something to create there — while loose files still need a folder
 * and are refused at the root, because files live inside folders.
 */
internal class FolderUploadSection(
    private val vm: VmScope,
    private val library: LibrarySection,
) {

    fun pickAndUpload() {
        if (vm.refusesWrite()) return
        vm.run {
            val tree = vm.host.pickFolder() ?: return@run
            stage(tree.entries, tree.emptyFolders)
        }
    }

    fun drop(tree: LocalFolderTree) {
        vm.update { copy(dragHover = false) }
        // A composer that is open takes dropped FILES; a folder has no place there.
        if (vm.state.composer.open || tree.isEmpty) return
        if (vm.refusesWrite()) return
        val hasTree = tree.emptyFolders.isNotEmpty() || tree.entries.any { '/' in it.relativePath }
        if (vm.state.currentFolder == null && !hasTree) return vm.fail(str(S.desktop_docdist_drop_folder_hint))
        stage(tree.entries, tree.emptyFolders)
    }

    fun cancel() {
        // A run in flight owns the dialog's state; closing it would orphan the upload.
        if (vm.state.upload != null) return
        vm.update { copy(folderUpload = null) }
    }

    fun confirm() {
        val job = vm.state.folderUpload ?: return
        if (vm.state.upload != null || vm.refusesWrite()) return
        vm.update { copy(folderUpload = null) }
        vm.run { execute(job.plan, job.parentId, job.date) }
    }

    /**
     * Builds the plan. A flat one (no folders at all) uploads straight away — the
     * preview exists to show folders that are about to be CREATED.
     */
    private fun stage(entries: List<FolderEntry>, emptyFolders: Set<String>) {
        val (accepted, rejected) = partitionEntries(entries)
        val plan = buildUploadPlan(accepted, emptyFolders)
        val parent = vm.state.currentFolder
        if (plan.isFlat) {
            if (rejected.isNotEmpty()) {
                vm.fail(str(S.desktop_docdist_skipped_unsupported, summariseFileNames(rejected.map { it.name })))
            }
            if (accepted.isEmpty()) return
            vm.run { execute(plan, parent?.id, vm.today().toString()) }
            return
        }
        vm.update {
            copy(
                folderUpload = FolderUploadState(
                    plan = plan,
                    rejected = rejected,
                    parentId = parent?.id,
                    parentLabel = parent?.name ?: str(S.desktop_docdist_library_root),
                    date = parent?.folderDate?.takeIf { it.isNotBlank() } ?: vm.today().toString(),
                ),
            )
        }
    }

    /**
     * Creates the folders, then uploads each file into the one that holds it.
     *
     * Folders go shallowest-first because a child needs its parent's server id.
     * If one fails, its whole subtree is abandoned — the files underneath have
     * nowhere to go — but sibling branches carry on, and the skipped count is
     * reported rather than swallowed.
     */
    private suspend fun execute(plan: UploadPlan, parentId: String?, date: String) {
        val idByPath = HashMap<String, String>()
        val failedPaths = HashSet<String>()
        var createdFolders = 0
        // One batch for the whole action: every folder create AND every file upload
        // carries it, so a 400-folder drop raises ONE notification instead of 400+.
        val batch = UploadBatch.create(plan.requestCount)
        vm.update { copy(upload = UploadProgress(UploadProgress.Stage.Preparing)) }
        try {
            plan.folders.forEachIndexed { index, path ->
                val name = path.substringAfterLast('/')
                val parentPath = path.substringBeforeLast('/', "")
                if (parentPath.isNotEmpty() && parentPath in failedPaths) {
                    // Already off the total with its failed ancestor's subtree; not dropped twice.
                    failedPaths += path
                    return@forEachIndexed
                }
                val parent = if (parentPath.isEmpty()) parentId else idByPath[parentPath]
                vm.update {
                    copy(upload = UploadProgress(UploadProgress.Stage.Creating, name, index, plan.folders.size))
                }
                when (val made = vm.repository.createFolderForUpload(name, parent, date, batch)) {
                    is ZillitResult.Success -> {
                        idByPath[path] = made.data
                        createdFolders++
                    }
                    is ZillitResult.Failure -> {
                        failedPaths += path
                        // The create did not count server-side, and everything under it is now
                        // unreachable too: this request AND its whole subtree come off the total
                        // NOW, not as the loop reaches them — or a subtree at the end of the plan
                        // leaves the last successful request promising more than will arrive.
                        batch.drop(1 + countPlannedUnder(plan, path))
                        vm.fail(str(S.desktop_docdist_failed_create_folder, name) + ": " + made.error.userMessage)
                    }
                }
            }
            val (uploaded, skipped) = uploadFiles(plan, parentId, date, idByPath, batch)
            report(uploaded, createdFolders, skipped)
        } finally {
            vm.update { copy(upload = null) }
        }
        vm.loadLibrary()
    }

    private suspend fun uploadFiles(
        plan: UploadPlan,
        parentId: String?,
        date: String,
        idByPath: Map<String, String>,
        batch: UploadBatch,
    ): Pair<Int, Int> {
        var uploaded = 0
        var skipped = 0
        plan.files.forEachIndexed { index, entry ->
            val folderId = if (entry.directory.isEmpty()) parentId else idByPath[entry.directory]
            // Its folder failed to create, so this file is never sent; it already came off
            // the total with that folder's subtree.
            if (entry.directory.isNotEmpty() && folderId == null) {
                skipped++
                return@forEachIndexed
            }
            vm.update {
                copy(upload = UploadProgress(UploadProgress.Stage.Uploading, entry.name, index, plan.files.size))
            }
            val bytes = entry.read()
            if (bytes == null) {
                skipped++
                batch.drop()
                val why = str(S.desktop_docdist_that_could_not_be_done)
                vm.fail(str(S.desktop_docdist_failed_to_upload, entry.name, why))
                return@forEachIndexed
            }
            val file = LocalFile(entry.name, entry.contentType, bytes)
            when (val sent = library.sendFile(file, folderId, date, batch)) {
                is ZillitResult.Success -> uploaded++
                is ZillitResult.Failure -> {
                    skipped++
                    batch.drop()
                    vm.fail(str(S.desktop_docdist_failed_to_upload, entry.name, sent.error.userMessage))
                }
            }
        }
        return uploaded to skipped
    }

    private fun report(uploaded: Int, folders: Int, skipped: Int) {
        if (uploaded > 0 || folders > 0) {
            vm.notice(
                str(S.desktop_docdist_uploaded_into, count(uploaded, isFile = true), count(folders, isFile = false)),
            )
        }
        if (skipped > 0) vm.fail(str(S.desktop_docdist_could_not_upload_n, count(skipped, isFile = true)))
    }

    private fun count(n: Int, isFile: Boolean): String = str(
        when {
            isFile && n == 1 -> S.drive_count_file_singular
            isFile -> S.drive_count_file_plural
            n == 1 -> S.drive_count_folder_singular
            else -> S.drive_count_folder_plural
        },
        n,
    )
}
