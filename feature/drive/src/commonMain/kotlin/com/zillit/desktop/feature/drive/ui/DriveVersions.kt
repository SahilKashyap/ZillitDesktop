package com.zillit.desktop.feature.drive.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.drive.domain.DriveFileVersion
import com.zillit.desktop.feature.drive.domain.DriveItem

/**
 * The version history panel: what it loads, and the four things it can do —
 * name a version, filter the changes to one person, open a version read-only
 * and restore one.
 *
 * Its own collaborator for the same reason [DriveDetails] is: the panel asks
 * two questions of its own (the history, then one version's changes) and
 * keeping them away from the listing's loading is what stops a slow comparison
 * holding up the browse list.
 */
// One function per thing the panel can do, the way the other collaborators
// in this package are built.
@Suppress("TooManyFunctions")
internal class DriveVersions(private val vm: DriveViewModel) {

    fun open(item: DriveItem) {
        vm.update {
            copy(
                menu = null,
                versionHistory = VersionHistoryState(item = item, loading = true),
            )
        }
        load(item)
    }

    fun close() = vm.update { copy(versionHistory = VersionHistoryState()) }

    /** Re-reads the open panel, if any — what a `drive:version:*` pulse calls. */
    fun reloadOpen() {
        vm.state.value.versionHistory.item?.let(::load)
    }

    fun refresh() {
        val item = vm.state.value.versionHistory.item ?: return
        vm.update { copy(versionHistory = versionHistory.copy(loading = true, failed = false)) }
        load(item)
    }

    private fun load(item: DriveItem) {
        vm.run {
            when (val result = vm.repo.versionHistory(item.id)) {
                is ZillitResult.Success -> vm.update {
                    if (versionHistory.item?.id != item.id) return@update this
                    copy(
                        versionHistory = versionHistory.copy(
                            history = result.data,
                            loading = false,
                            failed = false,
                        ),
                    )
                }

                is ZillitResult.Failure -> vm.update {
                    if (versionHistory.item?.id != item.id) return@update this
                    // The panel says so itself and offers "Try again"; a toast
                    // as well would say the same thing twice.
                    copy(
                        versionHistory = versionHistory.copy(
                            loading = false,
                            // A refresh that fails keeps what is on screen; a
                            // first load that fails has nothing to keep.
                            failed = versionHistory.history == null,
                        ),
                    )
                }
            }
            // A version's changes are per version, so an open detail re-reads
            // its own: the comparison may be what just finished.
            if (vm.state.value.versionHistory.detailVersionId != null) loadChanges()
        }
    }

    // -- the list ------------------------------------------------------------

    fun namedOnly(on: Boolean) = vm.update { copy(versionHistory = versionHistory.copy(namedOnly = on)) }

    fun toggleSession(sessionId: String) = vm.update {
        val open = versionHistory.expanded
        copy(
            versionHistory = versionHistory.copy(
                expanded = if (sessionId in open) open - sessionId else open + sessionId,
            ),
        )
    }

    // -- one version's changes -----------------------------------------------

    /**
     * Opens a version's detail.
     *
     * The changes are cleared first, not left showing the previous version's
     * while the new ones load — a cell list that belongs to another save is
     * worse than an empty panel.
     */
    fun openDetail(versionId: String) {
        vm.update {
            copy(
                versionHistory = versionHistory.copy(
                    detailVersionId = versionId,
                    changes = null,
                    changesLoading = true,
                    changesFailed = false,
                    personKey = null,
                    toggledGroups = emptySet(),
                    fullGroups = emptySet(),
                ),
            )
        }
        vm.run { loadChanges() }
    }

    fun closeDetail() = vm.update {
        copy(
            versionHistory = versionHistory.copy(
                detailVersionId = null,
                changes = null,
                changesLoading = false,
                changesFailed = false,
                personKey = null,
            ),
        )
    }

    fun reloadChanges() {
        vm.update { copy(versionHistory = versionHistory.copy(changesLoading = true, changesFailed = false)) }
        vm.run { loadChanges() }
    }

    private suspend fun loadChanges() {
        val panel = vm.state.value.versionHistory
        val item = panel.item ?: return
        val versionId = panel.detailVersionId ?: return
        // A synthetic row stands for the file as it is now and has no stored
        // object behind it, so there is nothing to compare it with.
        if (panel.detail?.synthetic == true) {
            vm.update { copy(versionHistory = versionHistory.copy(changesLoading = false)) }
            return
        }
        val result = vm.repo.versionChanges(item.id, versionId)
        vm.update {
            if (versionHistory.detailVersionId != versionId) return@update this
            copy(
                versionHistory = versionHistory.copy(
                    changes = result.getOrNull() ?: versionHistory.changes,
                    changesLoading = false,
                    changesFailed = result is ZillitResult.Failure && versionHistory.changes == null,
                ),
            )
        }
    }

    fun filterBy(personKey: String?) =
        vm.update { copy(versionHistory = versionHistory.copy(personKey = personKey)) }

    fun toggleGroup(groupName: String) = vm.update {
        val flipped = versionHistory.toggledGroups
        copy(
            versionHistory = versionHistory.copy(
                toggledGroups = if (groupName in flipped) flipped - groupName else flipped + groupName,
            ),
        )
    }

    fun showAllChanges(groupName: String) = vm.update {
        copy(versionHistory = versionHistory.copy(fullGroups = versionHistory.fullGroups + groupName))
    }

    // -- naming --------------------------------------------------------------

    fun startNaming(version: DriveFileVersion) = vm.update {
        copy(versionHistory = versionHistory.copy(naming = version, nameDraft = version.name))
    }

    fun nameDraft(text: String) = vm.update { copy(versionHistory = versionHistory.copy(nameDraft = text)) }

    fun cancelNaming() =
        vm.update { copy(versionHistory = versionHistory.copy(naming = null, nameDraft = "")) }

    /**
     * Saves the name, or clears it when the box was emptied.
     *
     * Gated on the history route's own `can_edit` rather than on the listing
     * row's rights: a file shared straight with someone carries no permissions
     * on the rows the tab-wide lists return, and naming a version is an edit
     * to the file.
     */
    fun saveName() {
        val panel = vm.state.value.versionHistory
        val item = panel.item ?: return
        val version = panel.naming ?: return
        if (!panel.canEdit || !vm.requirePosting()) return
        val name = panel.nameDraft.trim()
        vm.update { copy(versionHistory = versionHistory.copy(savingName = true)) }
        vm.run {
            when (val result = vm.repo.nameVersion(item.id, version.id, name)) {
                is ZillitResult.Success -> {
                    vm.update {
                        copy(versionHistory = versionHistory.copy(naming = null, nameDraft = "", savingName = false))
                    }
                    vm.notice(
                        if (name.isEmpty()) {
                            str(S.desktop_drive_version_name_removed)
                        } else {
                            str(S.desktop_drive_version_named)
                        },
                    )
                    load(item)
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(versionHistory = versionHistory.copy(savingName = false)) }
                    vm.reportError(result.error)
                }
            }
        }
    }

    // -- opening a version ---------------------------------------------------

    /**
     * Opens one stored version in the editor window, read-only.
     *
     * The same window as the live file — the server hands back the same config
     * shape pointed at the version — so the title says which version it is,
     * and nothing offers to switch it to editing: the way to edit an old
     * version is to restore it.
     */
    fun preview(version: DriveFileVersion) {
        val panel = vm.state.value.versionHistory
        val item = panel.item ?: return
        if (version.synthetic) {
            // The file as it is now: open the file, not a version of it.
            vm.openEditor(item, editable = false)
            return
        }
        vm.run {
            when (val session = vm.repo.versionPreview(item.id, version.id)) {
                is ZillitResult.Success -> {
                    val config = session.data
                    vm.effect(
                        DriveEffect.OpenEditor(
                            url = config.address(),
                            fileName = versionTitle(item, version, config.fileName),
                            fileId = item.id,
                            viewOnly = true,
                        ),
                    )
                    vm.notice(str(S.desktop_drive_opened_version_read_only, version.versionNumber))
                }

                is ZillitResult.Failure -> vm.reportError(session.error)
            }
        }
    }

    /** "Budget.xlsx — Version 4", so two windows on one file are told apart. */
    private fun versionTitle(item: DriveItem, version: DriveFileVersion, configName: String): String {
        val name = configName.ifBlank { item.name }
        if (version.versionNumber <= 0) return name
        return "$name — ${str(S.desktop_version_number, version.versionNumber)}"
    }
}
