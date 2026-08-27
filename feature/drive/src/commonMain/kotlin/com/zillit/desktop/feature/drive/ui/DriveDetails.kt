package com.zillit.desktop.feature.drive.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.DriveRef

/**
 * The docked details panel: what it loads when it opens, and the two things
 * that can be changed from inside it — comments and tags.
 *
 * Its own collaborator because the panel answers five questions at once
 * (metadata, access, versions, comments, activity) and each answer is a
 * separate call. Keeping that away from the browse list's own loading is
 * what stops one slow request stalling the other.
 *
 * Tags are here rather than in the list because the browse view has filtered
 * by tag since this tool shipped while nothing could *apply* one — so those
 * filters only ever matched tags made on a phone or the web.
 */
internal class DriveDetails(private val vm: DriveViewModel) {

    fun show(item: DriveItem?) {
        if (item == null) {
            vm.update { copy(details = DetailsState()) }
            return
        }
        vm.update { copy(details = DetailsState(item = item, loading = true)) }
        reload(item)
    }

    /** Re-reads whatever the panel is currently open on, if anything. */
    fun reloadOpen() {
        vm.state.value.details.item?.let(::reload)
    }

    fun reload(item: DriveItem) {
        vm.run {
            // Folders have neither comments nor versions; asking for them is
            // two guaranteed 404s every time a folder is inspected.
            val comments = if (item.isFolder) null else vm.repo.comments(item.id)
            val versions = if (item.isFolder) null else vm.repo.versions(item.id)
            val activity = vm.repo.activity(item.id)
            val ref = DriveRef(item.id, item.kind)
            val access = vm.repo.access(ref)
            val tags = vm.repo.itemTags(ref)
            vm.update {
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

    fun postComment() {
        val open = vm.state.value.details
        val item = open.item ?: return
        val text = open.commentDraft.trim()
        if (text.isEmpty()) return
        vm.update { copy(details = details.copy(commentDraft = "")) }
        vm.run {
            when (val result = vm.repo.addComment(item.id, text)) {
                is ZillitResult.Success -> reload(item)
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

    // -- tags ----------------------------------------------------------------

    fun tagDraft(text: String) = vm.update { copy(details = details.copy(tagDraft = text)) }

    fun assignTag(tagId: String) = tagMutate { ref -> vm.repo.assignTag(tagId, ref) }

    fun removeTag(tagId: String) = tagMutate { ref -> vm.repo.removeTag(tagId, ref) }

    /**
     * Creates a tag and puts it on the open item in one gesture.
     *
     * The create call answers with no body, so the new tag is found by
     * re-reading the project list and matching the name just sent — the
     * *last* match, since nothing stops a production having two tags with
     * the same name and the newest is the one just made.
     */
    fun createAndAssignTag() {
        val item = vm.state.value.details.item ?: return
        val name = vm.state.value.details.tagDraft.trim()
        if (name.isEmpty() || !allowed()) return
        vm.update { copy(details = details.copy(tagsBusy = true, tagDraft = "")) }
        vm.run {
            when (val created = vm.repo.createTag(name, "")) {
                is ZillitResult.Failure -> fail(created)
                is ZillitResult.Success -> assignNewlyMade(name, DriveRef(item.id, item.kind))
            }
        }
    }

    private suspend fun assignNewlyMade(name: String, ref: DriveRef) {
        vm.reloadTags()
        val made = vm.state.value.tags.lastOrNull { it.name.equals(name, ignoreCase = true) }
        if (made == null) {
            settle()
            return
        }
        when (val assigned = vm.repo.assignTag(made.id, ref)) {
            is ZillitResult.Success -> refreshTags(ref)
            is ZillitResult.Failure -> fail(assigned)
        }
    }

    private fun tagMutate(block: suspend (DriveRef) -> ZillitResult<Unit>) {
        val item = vm.state.value.details.item ?: return
        if (!allowed()) return
        vm.update { copy(details = details.copy(tagsBusy = true)) }
        vm.run {
            val ref = DriveRef(item.id, item.kind)
            when (val result = block(ref)) {
                is ZillitResult.Success -> refreshTags(ref)
                is ZillitResult.Failure -> fail(result)
            }
        }
    }

    /**
     * Tagging is a posting action on the drive tool rather than a per-item
     * edit right — the same gate the web applies, so a coordinator who may
     * post can tag anything they can already see.
     */
    private fun allowed(): Boolean {
        if (vm.state.value.viewer.canCreate) return true
        vm.reportFailure("You do not have permission to change tags.")
        return false
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
