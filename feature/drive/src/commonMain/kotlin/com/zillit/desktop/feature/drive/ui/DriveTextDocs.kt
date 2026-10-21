package com.zillit.desktop.feature.drive.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.drive.domain.DriveFileVersion
import com.zillit.desktop.feature.drive.domain.DriveItem
import com.zillit.desktop.feature.drive.domain.TEXT_DOCUMENT_MAX_BYTES
import com.zillit.desktop.feature.drive.domain.driveMessage
import com.zillit.desktop.feature.drive.domain.isChangedSinceOpened
import com.zillit.desktop.feature.drive.domain.textByteLength

/**
 * Drive's own text and Markdown viewer — `MarkdownDocument.jsx`.
 *
 * `.md` and `.txt` are the two kinds the live editors refuse
 * (`file_type_not_editable`), so Drive reads and writes them itself over
 * `GET`/`PUT /files/{id}/text`. Everything that makes that safe is here: the
 * stamp the file was opened with, the conflict the server answers when
 * somebody else saved first, and the question on the way out about telling
 * the people it is shared with.
 *
 * Its own collaborator for the reason the others are: a file being edited has
 * a lifetime of its own, and keeping its load and save out of the listing's is
 * what stops a slow save holding up the browse list.
 */
internal class DriveTextDocs(private val vm: DriveViewModel) {

    /** Opens [item], rendered or straight into the editor. */
    fun open(item: DriveItem, edit: Boolean) {
        vm.markRead(item)
        vm.update {
            copy(
                menu = null,
                preview = null,
                textDoc = TextDocState(item = item, loading = true, editing = edit),
            )
        }
        load(item, intoEdit = edit)
    }

    /**
     * Closes it, asking once when there are unsaved changes, and then asks
     * whether to tell the others — but only if this person actually saved or
     * restored something while it was open.
     */
    fun close() {
        val open = vm.state.value.textDoc ?: return
        if (open.dirty && !open.confirmDiscard) {
            vm.update { copy(textDoc = textDoc?.copy(confirmDiscard = true)) }
            return
        }
        vm.update {
            copy(
                textDoc = null,
                notifyEdit = if (open.changed) NotifyEditState(open.item) else notifyEdit,
            )
        }
    }

    fun keepEditing() = vm.update { copy(textDoc = textDoc?.copy(confirmDiscard = false)) }

    fun draft(text: String) = vm.update { copy(textDoc = textDoc?.copy(text = text)) }

    fun editing(on: Boolean) = vm.update {
        copy(textDoc = textDoc?.takeIf { it.canEdit || !on }?.copy(editing = on) ?: textDoc)
    }

    fun closeVersion() = vm.update { copy(textDoc = textDoc?.copy(versionView = null)) }

    /** Re-reads the live file — the conflict bar's "Load the latest version". */
    fun reload() {
        val item = vm.state.value.textDoc?.item ?: return
        vm.update { copy(textDoc = textDoc?.copy(loading = true, conflict = null, versionView = null)) }
        load(item, intoEdit = vm.state.value.textDoc?.editing == true)
    }

    /**
     * Saves the text as a new version.
     *
     * The size is checked here because the server refuses a larger file and
     * the round trip is wasted; [overwrite] drops the stamp, which is the
     * conflict bar's "Save my version anyway".
     */
    fun save(overwrite: Boolean) {
        val open = vm.state.value.textDoc ?: return
        if (!open.canEdit || open.saving) return
        if (textByteLength(open.text) > TEXT_DOCUMENT_MAX_BYTES) {
            vm.reportFailure(str(S.desktop_drive_text_too_large))
            return
        }
        vm.update { copy(textDoc = textDoc?.copy(saving = true)) }
        vm.run {
            val answer = vm.repo.saveFileText(
                fileId = open.item.id,
                content = open.text,
                contentTime = open.contentTime.takeUnless { overwrite },
            )
            when (answer) {
                is ZillitResult.Success -> {
                    vm.update {
                        if (textDoc?.item?.id != open.item.id) return@update this
                        copy(
                            textDoc = textDoc.copy(
                                saving = false,
                                savedText = open.text,
                                contentTime = answer.data.contentTime.ifBlank { textDoc.contentTime },
                                conflict = null,
                                changed = textDoc.changed || answer.data.saved,
                            ),
                            notice = str(S.saved),
                        )
                    }
                    // A save is a version: an open history is now a version behind.
                    if (answer.data.saved) vm.versionsPanel.reloadOpen()
                }

                is ZillitResult.Failure -> vm.update {
                    if (textDoc?.item?.id != open.item.id) return@update this
                    val changed = answer.error.isChangedSinceOpened()
                    if (!changed) vm.reportError(answer.error)
                    copy(
                        textDoc = textDoc.copy(
                            saving = false,
                            conflict = answer.error.driveMessage().takeIf { changed },
                        ),
                    )
                }
            }
        }
    }

    /**
     * Shows one stored version of a text file, read-only — what the history
     * panel's "Open read-only" means for a file with no editor behind it.
     * Returns false when this is not a text file, so the caller falls through
     * to the editor window.
     */
    fun showVersion(item: DriveItem, version: DriveFileVersion): Boolean {
        vm.update {
            copy(
                versionHistory = versionHistory,
                textDoc = (textDoc ?: TextDocState(item = item, loading = true)).copy(loading = true),
            )
        }
        vm.run {
            when (val answer = vm.repo.fileText(item.id, versionId = version.id)) {
                is ZillitResult.Success -> vm.update {
                    copy(
                        textDoc = textDoc?.copy(
                            loading = false,
                            editing = false,
                            versionView = TextVersionView(version.versionNumber, answer.data.content),
                        ),
                    )
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(textDoc = textDoc?.copy(loading = false)) }
                    vm.reportError(answer.error)
                }
            }
        }
        return true
    }

    /**
     * The editor window for [fileId] has closed. Ask about telling the others
     * only if the file actually gained a version while it was open.
     *
     * The web knows the answer outright: its editor is a frame of its own page
     * and reports back that the document was edited. Here the editor is a
     * window loading the editors' own page, which saves by itself and tells
     * this client nothing — so the question is put to the file: is its newest
     * version newer than the moment the window opened? One call, on close, and
     * only for a session that could save at all.
     *
     * [since] is null when the session was read-only, and zero where no clock
     * was wired (tests, the render harness), in which case nothing is asked.
     */
    fun askNotifyAfterEditor(fileId: String, since: Long?) {
        if (since == null || since <= 0L) return
        val item = vm.state.value.listing.files.firstOrNull { it.id == fileId } ?: return
        vm.run {
            val versions = vm.repo.versions(fileId) as? ZillitResult.Success ?: return@run
            val newest = versions.data.maxOfOrNull { it.createdAt ?: 0L } ?: 0L
            if (newest > since) vm.update { copy(notifyEdit = notifyEdit ?: NotifyEditState(item)) }
        }
    }

    /** A restore from the history panel is a change this person made. */
    fun markRestored(fileId: String) = vm.update {
        if (textDoc?.item?.id != fileId) return@update this
        copy(textDoc = textDoc.copy(changed = true))
    }

    // -- "let others know you updated this file?" ---------------------------

    fun note(text: String) = vm.update { copy(notifyEdit = notifyEdit?.copy(note = text)) }

    fun dismissNotify() = vm.update { copy(notifyEdit = null) }

    /**
     * One notification to everyone with access, with the note if there is one.
     * A failure leaves the prompt up, so the person can try again or skip.
     */
    fun sendNotify() {
        val prompt = vm.state.value.notifyEdit ?: return
        if (prompt.sending) return
        vm.update { copy(notifyEdit = notifyEdit?.copy(sending = true)) }
        vm.run {
            when (val answer = vm.repo.announceEdit(prompt.item.id, prompt.note.trim())) {
                is ZillitResult.Success -> vm.update {
                    copy(
                        notifyEdit = null,
                        notice = answer.data.ifBlank { str(S.desktop_drive_notify_sent) },
                    )
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(notifyEdit = notifyEdit?.copy(sending = false)) }
                    vm.reportError(answer.error)
                }
            }
        }
    }

    // -- loading ------------------------------------------------------------

    private fun load(item: DriveItem, intoEdit: Boolean) {
        vm.run {
            when (val answer = vm.repo.fileText(item.id)) {
                is ZillitResult.Success -> vm.update {
                    if (textDoc?.item?.id != item.id) return@update this
                    val text = answer.data
                    copy(
                        textDoc = textDoc.copy(
                            loading = false,
                            error = null,
                            text = text.content,
                            savedText = text.content,
                            contentTime = text.contentTime,
                            canEdit = text.canEdit,
                            // Asked for the editor without the right to save:
                            // it opens read-only rather than pretending.
                            editing = intoEdit && text.canEdit,
                            conflict = null,
                            versionView = null,
                        ),
                    )
                }

                is ZillitResult.Failure -> vm.update {
                    if (textDoc?.item?.id != item.id) return@update this
                    copy(textDoc = textDoc.copy(loading = false, error = answer.error.driveMessage()))
                }
            }
        }
    }
}
