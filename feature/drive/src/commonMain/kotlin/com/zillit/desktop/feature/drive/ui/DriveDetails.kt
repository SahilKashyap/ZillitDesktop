package com.zillit.desktop.feature.drive.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveRef
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The docked details panel: what it loads when it opens, and the things that
 * can be changed from inside it — comments and tags.
 *
 * Its own collaborator because the panel answers five questions at once
 * (metadata, access, versions, comments, activity) and each answer is a
 * separate call. Keeping that away from the browse list's own loading is
 * what stops one slow request stalling the other.
 */
internal class DriveDetails(private val vm: DriveViewModel) {

    fun show(item: DriveItem?) {
        if (item == null) {
            vm.update { copy(details = DetailsState()) }
            return
        }
        vm.update { copy(details = DetailsState(item = item, loading = true), menu = null) }
        reload(item)
    }

    /** Re-reads whatever the panel is currently open on, if anything. */
    fun reloadOpen() {
        vm.state.value.details.item?.let(::reload)
    }

    fun reload(item: DriveItem) {
        vm.run {
            // Folders have neither comments nor versions; asking for them is
            // two guaranteed 404s every time a folder is inspected. Versions
            // are only meaningful for documents the editor can rewrite
            // (ZL-18509) — an image has no revisions to list.
            val comments = if (item.isFolder) null else vm.repo.comments(item.id)
            val versions = if (item.isEditableDocument) vm.repo.versions(item.id) else null
            val activity = vm.repo.activity(item.id)
            val ref = DriveRef(item.id, item.kind)
            val access = vm.repo.access(ref)
            val tags = vm.repo.itemTags(ref)
            vm.update {
                if (details.item?.id != item.id) return@update this
                copy(
                    details = details.copy(
                        loading = false,
                        comments = comments?.getOrNull().orEmpty(),
                        versions = versions?.getOrNull().orEmpty(),
                        activity = activity.getOrNull().orEmpty(),
                        access = access.getOrNull().orEmpty(),
                        tags = tags.getOrNull().orEmpty(),
                    ),
                )
            }
        }
    }

    // -- comments ------------------------------------------------------------

    fun postComment() {
        val open = vm.state.value.details
        val item = open.item ?: return
        val text = open.commentDraft.trim()
        if (text.isEmpty() || !vm.requirePosting()) return
        vm.update { copy(details = details.copy(commentDraft = "")) }
        vm.run {
            when (val result = vm.repo.addComment(item.id, text)) {
                is ZillitResult.Success -> reloadComments(item)
                is ZillitResult.Failure -> {
                    // The draft goes back in the box rather than being lost —
                    // retyping a paragraph because the network blinked is the
                    // worst thing a comment field can do.
                    vm.update { copy(details = details.copy(commentDraft = text)) }
                    vm.reportError(result.error)
                }
            }
        }
    }

    fun saveComment() {
        val open = vm.state.value.details
        val item = open.item ?: return
        val id = open.editingCommentId ?: return
        val text = open.editingText.trim()
        if (text.isEmpty() || !vm.requirePosting()) return
        vm.run {
            when (val result = vm.repo.updateComment(id, text)) {
                is ZillitResult.Success -> {
                    vm.update { copy(details = details.copy(editingCommentId = null, editingText = "")) }
                    reloadComments(item)
                }

                is ZillitResult.Failure -> vm.reportError(result.error)
            }
        }
    }

    fun deleteComment(commentId: String) {
        val item = vm.state.value.details.item ?: return
        if (!vm.requirePosting()) return
        vm.run {
            when (val result = vm.repo.deleteComment(commentId)) {
                is ZillitResult.Success -> {
                    vm.notice(str(S.desktop_drive_comment_deleted))
                    reloadComments(item)
                }

                is ZillitResult.Failure -> vm.reportError(result.error)
            }
        }
    }

    /** Only the thread re-reads after a comment change, so the panel does not jump. */
    private suspend fun reloadComments(item: DriveItem) {
        val fresh = vm.repo.comments(item.id)
        vm.update {
            if (details.item?.id != item.id) return@update this
            copy(details = details.copy(comments = fresh.getOrNull() ?: details.comments))
        }
    }

    // -- tags ----------------------------------------------------------------

    fun tagDraft(text: String) = vm.update { copy(details = details.copy(tagDraft = text)) }

    fun assignTag(tagId: String) = tagMutate { ref -> vm.repo.assignTag(tagId, ref) }

    fun removeTag(tagId: String) = tagMutate { ref -> vm.repo.removeTag(tagId, ref) }

    /**
     * Creates a tag and puts it on the open item in one gesture.
     *
     * The create answers with the tag when the service sends it; otherwise
     * the new one is found by re-reading the project list and matching the
     * name just sent — the *last* match, since nothing stops a production
     * having two tags with the same name and the newest is the one just made.
     */
    fun createAndAssignTag() {
        val item = vm.state.value.details.item ?: return
        val name = vm.state.value.details.tagDraft.trim()
        if (name.isEmpty() || !vm.requirePosting()) return
        vm.update { copy(details = details.copy(tagsBusy = true, tagDraft = "")) }
        vm.run {
            when (val created = vm.repo.createTag(name, "")) {
                is ZillitResult.Failure -> fail(created)
                is ZillitResult.Success -> assignNewlyMade(created.data?.id, name, DriveRef(item.id, item.kind))
            }
        }
    }

    private suspend fun assignNewlyMade(knownId: String?, name: String, ref: DriveRef) {
        vm.reloadTags()
        val id = knownId ?: vm.state.value.tags.lastOrNull { it.name.equals(name, ignoreCase = true) }?.id
        if (id == null) {
            settle()
            return
        }
        when (val assigned = vm.repo.assignTag(id, ref)) {
            is ZillitResult.Success -> refreshTags(ref)
            is ZillitResult.Failure -> fail(assigned)
        }
    }

    private fun tagMutate(block: suspend (DriveRef) -> ZillitResult<Unit>) {
        val item = vm.state.value.details.item ?: return
        if (!vm.requirePosting()) return
        vm.update { copy(details = details.copy(tagsBusy = true)) }
        vm.run {
            val ref = DriveRef(item.id, item.kind)
            when (val result = block(ref)) {
                is ZillitResult.Success -> refreshTags(ref)
                is ZillitResult.Failure -> fail(result)
            }
        }
    }

    /** A tag change re-reads only this item's tags, so the panel does not jump. */
    private suspend fun refreshTags(ref: DriveRef) {
        val fresh = vm.repo.itemTags(ref)
        vm.update {
            copy(details = details.copy(tagsBusy = false, tags = fresh.getOrNull() ?: details.tags))
        }
    }

    private fun settle() = vm.update { copy(details = details.copy(tagsBusy = false)) }

    private fun fail(result: ZillitResult.Failure) {
        settle()
        vm.reportError(result.error)
    }
}
