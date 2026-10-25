package com.zillit.desktop.feature.documentdistribution.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.documentdistribution.domain.AppliedMergeList
import com.zillit.desktop.feature.documentdistribution.domain.MergeDirectory
import com.zillit.desktop.feature.documentdistribution.domain.MergePlan
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkStyle
import com.zillit.desktop.feature.documentdistribution.domain.buildMergePlan
import com.zillit.desktop.feature.documentdistribution.domain.filteredFor
import com.zillit.desktop.feature.documentdistribution.domain.mergeDirectory
import com.zillit.desktop.feature.documentdistribution.domain.mergedFileName
import com.zillit.desktop.feature.documentdistribution.domain.releaseOnListRemove
import com.zillit.desktop.feature.documentdistribution.domain.resolveListPicks
import com.zillit.desktop.feature.documentdistribution.domain.stampedRecipients
import com.zillit.desktop.feature.documentdistribution.domain.withDefaults

/**
 * "Merge PDFs to download or print" (web `MergePrintModal`, 2026-10-06/07).
 *
 * The selected PDFs come back from the server as ONE file — optionally with a
 * stamped copy of the whole set per chosen person, grouped so each person's
 * copies sit together. The app never reads a document's bytes: the server
 * fetches and joins them, which is what retired the browser's own merge on the
 * web. Gated on download rights, the flag the rest of the tool uses for bytes
 * leaving the system.
 *
 * Print is "open in the PDF viewer": the desktop has no print-from-memory path
 * that is better than the viewer's own dialog, which also shows the pages.
 */
internal class MergeSection(
    private val vm: VmScope,
    private val library: LibrarySection,
) {

    fun open() {
        if (vm.refusesDownload()) return
        library.prepare(library::resolveSelection) { documents ->
            if (documents.isEmpty()) return@prepare vm.fail(str(S.desktop_docdist_nothing_selected))
            vm.update {
                copy(merge = MergeState(documents = documents, folderName = currentFolder?.name), crew = vm.host.crew())
            }
            primeContacts()
            primeLists()
        }
    }

    /**
     * A production's crew mostly live in the address book and may never sign
     * in, so the picker needs the book as well as the project's users.
     */
    private fun primeContacts() {
        if (vm.state.contacts.isNotEmpty()) return
        vm.run {
            (vm.repository.contacts() as? ZillitResult.Success)?.let { loaded ->
                vm.update { copy(contacts = loaded.data) }
            }
        }
    }

    /**
     * The project's distribution lists, refreshed every time the dialog opens
     * rather than cached, so a list edited a moment ago on the Lists tab is
     * picked up with its current members (the web refetches on open too). A
     * failure leaves the picker empty; ticking people by hand still works.
     */
    private fun primeLists() {
        vm.run {
            (vm.repository.lists() as? ZillitResult.Success)?.let { loaded ->
                vm.update { copy(lists = loaded.data) }
            }
        }
    }

    /**
     * Picking a list ADDS everyone on it; nobody already chosen is unticked,
     * so several lists combine and hand-picked people survive. The same rule
     * as the composer's and the watermark ZIP's list pickers.
     */
    fun applyList(listId: String) {
        val open = vm.state.merge ?: return
        val list = vm.state.lists.firstOrNull { it.id == listId } ?: return
        val directory = vm.state.peopleForMerge()
        val picks = resolveListPicks(list, directory.people, directory.me.email)
        val added = picks.emails.filter { it.lowercase() !in open.picked }
        val selfAdded = picks.includesSelf && !open.includeSelf
        val newRows = picks.extra.filter { person ->
            open.listPeople.none { it.email.equals(person.email, ignoreCase = true) }
        }
        edit {
            copy(
                picked = picked + added.map { it.lowercase() },
                listPeople = listPeople + newRows,
                includeSelf = includeSelf || selfAdded,
                appliedLists = if (appliedLists.any { it.id == listId }) {
                    appliedLists
                } else {
                    appliedLists + AppliedMergeList(listId, list.name, picks.emails, picks.includesSelf)
                },
                listOwned = listOwned + added.map { it.lowercase() },
                selfOwnedByList = selfOwnedByList || selfAdded,
            )
        }
        val count = added.size + if (selfAdded) 1 else 0
        vm.notice(
            when {
                picks.emails.isEmpty() && !picks.includesSelf ->
                    str(S.desktop_docdist_merge_list_empty, list.name)
                count == 0 -> str(S.desktop_docdist_merge_list_all_selected, list.name)
                else -> str(S.desktop_docdist_merge_list_added, count, list.name)
            },
        )
    }

    /**
     * Removing a chosen list unticks the people IT brought in — not anyone the
     * user ticked by hand, and not anyone another chosen list still covers.
     */
    fun removeList(listId: String) {
        val open = vm.state.merge ?: return
        val removed = open.appliedLists.firstOrNull { it.id == listId } ?: return
        val remaining = open.appliedLists.filter { it.id != listId }
        val (release, releaseSelf) = releaseOnListRemove(
            removed = removed,
            remaining = remaining,
            listOwned = open.listOwned,
            selfOwned = open.selfOwnedByList,
        )
        edit {
            copy(
                appliedLists = remaining,
                picked = picked - release,
                listOwned = listOwned - release,
                // A list-only person has no reason to stay listed once their
                // list is gone; crew and contacts keep their row regardless.
                listPeople = listPeople.filterNot { it.email.lowercase() in release },
                includeSelf = if (releaseSelf) false else includeSelf,
                selfOwnedByList = if (releaseSelf) false else selfOwnedByList,
            )
        }
        val count = release.size + if (releaseSelf) 1 else 0
        if (count > 0) vm.notice(str(S.desktop_docdist_merge_list_removed, count, removed.name))
    }

    fun close() {
        // A build in flight cannot be taken back, and closing would orphan its result.
        if (vm.state.merge?.busy != null) return
        vm.update { copy(merge = null) }
    }

    fun edit(change: MergeState.() -> MergeState) = vm.update { copy(merge = merge?.change()) }

    fun toggle(email: String) {
        val key = email.lowercase()
        edit { copy(picked = if (key in picked) picked - key else picked + key) }
    }

    /**
     * Select-all follows what is ON SCREEN, not the whole crew: ticking it
     * while a filter is on picks the people shown and never silently adds the
     * ones it is hiding.
     */
    fun toggleShown(on: Boolean) {
        val open = vm.state.merge ?: return
        val shown = vm.state.peopleForMerge().people.filteredFor(open.source, open.search).map { it.email.lowercase() }
        edit { copy(picked = if (on) picked + shown else picked - shown.toSet()) }
    }

    fun run(action: MergeAction) {
        val open = vm.state.merge ?: return
        if (open.busy != null || vm.refusesDownload()) return
        val plan = vm.state.mergePlan() ?: return
        if (!plan.isReady) return
        edit { copy(busy = action, error = null, progress = null) }
        vm.run {
            when (val built = build(plan)) {
                is ZillitResult.Success -> finish(action, plan, open, built.data)
                is ZillitResult.Failure -> fail(built.error.localised())
            }
        }
    }

    /**
     * One call, or two and a join. `watermark-merged` stamps everything it is
     * given recipients for, so a clean copy cannot ride along with stamped
     * ones: asked for both, the halves are built separately and the finished
     * PDFs joined — needing no document bytes, only the two results.
     */
    private suspend fun build(plan: MergePlan): ZillitResult<ByteArray> {
        val ids = plan.documents.map { it.id }
        val style = WatermarkStyle().withDefaults(vm.state.watermarkDefaults)
        // Until the project's settings have loaded, `style` is the built-in
        // look, a placeholder rather than a choice. Sending nothing lets the
        // server fill the appearance from the saved settings instead.
        val styleToSend = style.takeIf { vm.state.watermarkDefaultsLoaded }
        val parts = mutableListOf<ByteArray>()
        if (plan.plainCopy) {
            progress(
                if (plan.needsJoin) S.desktop_docdist_merge_generating_yours else S.desktop_docdist_merge_generating,
            )
            when (val own = vm.repository.mergedPdf(ids, recipients = null, style = null)) {
                is ZillitResult.Success -> parts += own.data
                is ZillitResult.Failure -> return own
            }
        }
        if (plan.stamped.isNotEmpty()) {
            progress(
                if (plan.needsJoin) S.desktop_docdist_merge_generating_crew else S.desktop_docdist_merge_generating,
            )
            when (val crew = vm.repository.mergedPdf(ids, plan.stampedRecipients(style), styleToSend)) {
                is ZillitResult.Success -> parts += crew.data
                is ZillitResult.Failure -> return crew
            }
        }
        if (parts.size == 1) return ZillitResult.Success(parts.single())
        progress(S.desktop_docdist_merge_joining)
        return vm.host.joinPdfs(parts)
    }

    private suspend fun finish(action: MergeAction, plan: MergePlan, open: MergeState, bytes: ByteArray) {
        val fileName = mergedFileName(plan.stamped.isNotEmpty(), open.folderName, vm.today().toString())
        when (action) {
            MergeAction.Download -> {
                vm.update { copy(merge = null) }
                library.saveAndOpen(fileName, bytes)
            }
            MergeAction.Print -> when (val shown = vm.host.openForPrinting(fileName, bytes)) {
                is ZillitResult.Success -> {
                    vm.update { copy(merge = null) }
                    vm.notice(str(S.desktop_docdist_merge_sent_to_viewer))
                }
                is ZillitResult.Failure -> fail(shown.error.localised())
            }
        }
    }

    private fun progress(key: String) = edit { copy(progress = str(key)) }

    /** Surfaced in place: the dialog stays open, so the selection is not lost and the run can be retried. */
    private fun fail(message: String) = edit { copy(busy = null, progress = null, error = message) }
}

/** Everyone the merge picker offers, with the signed-in person set apart. */
/**
 * Everyone the merge picker offers, with the signed-in person set apart.
 *
 * The open dialog's list-only people are appended, never substituted: a row is
 * what makes a ticked address count as a recipient, so a distribution-list
 * member who is neither crew nor a contact would otherwise be ticked and then
 * silently dropped from the merge. Anyone the directory already knows keeps
 * their own richer row.
 */
internal fun DocDistUiState.peopleForMerge(): MergeDirectory {
    val directory = mergeDirectory(crew, contacts, viewer.userId, viewer.userEmail)
    val extra = merge?.listPeople.orEmpty()
    if (extra.isEmpty()) return directory
    val known = directory.people.map { it.email.lowercase() }.toMutableSet()
    known += directory.me.email.lowercase()
    return directory.copy(people = directory.people + extra.filter { known.add(it.email.lowercase()) })
}

/** What the dialog's current choices would build; null when it is closed. */
internal fun DocDistUiState.mergePlan(): MergePlan? {
    val open = merge ?: return null
    val directory = peopleForMerge()
    return buildMergePlan(
        selection = open.documents,
        me = directory.me,
        includeSelf = open.includeSelf,
        watermarkSelf = open.watermarkSelf,
        chosen = directory.people.filter { it.email.lowercase() in open.picked },
    )
}
