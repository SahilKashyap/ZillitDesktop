package com.zillit.desktop.feature.documentdistribution.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.documentdistribution.domain.Csv
import com.zillit.desktop.feature.documentdistribution.domain.DistributionList
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.domain.fileNameStem
import com.zillit.desktop.feature.documentdistribution.domain.isValidEmail

/**
 * Distribution lists: the overview, one list's editor, and the composer's
 * inline "create new list". Saving a list replaces its whole membership —
 * the endpoint replaces rather than patches — so the editor holds every
 * member and sends them all.
 */
@Suppress("TooManyFunctions") // One handler per user action.
internal class ListsSection(private val vm: VmScope, private val library: LibrarySection) {

    fun load() {
        vm.update { copy(loading = true, error = null) }
        vm.run {
            when (val result = vm.repository.lists()) {
                is ZillitResult.Success -> vm.update {
                    copy(
                        loading = false,
                        lists = result.data,
                        // The open editor follows the fresh copy — a socket
                        // refresh under an editor must not revert typing, so
                        // only the id is checked and a deleted list closes it.
                        listDetail = listDetail?.takeIf { open -> result.data.any { it.id == open.listId } },
                    )
                }
                is ZillitResult.Failure -> vm.update { copy(loading = false, error = result.error.userMessage) }
            }
            // The overview's smart list counts the address book.
            if (vm.state.contacts.isEmpty()) {
                (vm.repository.contacts() as? ZillitResult.Success)?.let { c -> vm.update { copy(contacts = c.data) } }
            }
        }
    }

    // -- overview ----------------------------------------------------------

    fun newListRow(open: Boolean) {
        if (open && vm.refusesWrite()) return
        vm.update { copy(newListName = if (open) "" else null) }
    }

    fun createInline() {
        val name = vm.state.newListName?.trim().orEmpty()
        if (name.isEmpty()) return vm.fail(str(S.desktop_docdist_enter_list_name))
        if (vm.refusesWrite()) return
        vm.update { copy(creatingList = true) }
        vm.run {
            when (val result = vm.repository.createList(name, emptyList())) {
                is ZillitResult.Success -> {
                    vm.update { copy(creatingList = false, newListName = null, lists = lists + result.data) }
                    vm.notice(str(S.desktop_docdist_list_created))
                    openList(result.data.id)
                }
                is ZillitResult.Failure -> {
                    vm.update { copy(creatingList = false) }
                    vm.report(result.error)
                }
            }
        }
    }

    fun openList(listId: String) {
        val list = vm.state.lists.firstOrNull { it.id == listId } ?: return
        vm.update {
            copy(
                listDetail = ListDetailState(
                    listId = list.id,
                    name = list.name,
                    description = list.description,
                    recipients = list.recipients,
                ),
            )
        }
        if (vm.state.contacts.isEmpty()) {
            vm.run {
                (vm.repository.contacts() as? ZillitResult.Success)?.let { c -> vm.update { copy(contacts = c.data) } }
            }
        }
    }

    fun closeList() = vm.update { copy(listDetail = null) }

    fun exportList(listId: String) {
        if (vm.refusesDownload()) return
        if (vm.state.exportingListId != null) return
        val list = vm.state.lists.firstOrNull { it.id == listId } ?: return
        vm.update { copy(exportingListId = listId) }
        vm.run {
            // The server's CSV when it answers; the same columns built here
            // when the route is not deployed, so the button always works.
            val bytes = (vm.repository.exportList(listId) as? ZillitResult.Success)?.data
                ?: Csv.recipients(list.recipients).encodeToByteArray()
            vm.update { copy(exportingListId = null) }
            library.saveAndOpen("${fileNameStem(list.name, "distribution-list")}.csv", bytes)
        }
    }

    fun downloadTemplate() = vm.run {
        library.saveAndOpen("distribution-list-template.csv", Csv.template().encodeToByteArray())
    }

    // -- detail editor -------------------------------------------------------

    private inline fun editDetail(crossinline change: ListDetailState.() -> ListDetailState) =
        vm.update { copy(listDetail = listDetail?.change()) }

    fun editName(text: String) = editDetail { copy(name = text) }

    fun editInput(email: String, name: String, job: String) =
        editDetail { copy(emailInput = email, nameInput = name, jobInput = job) }

    fun pickContact(email: String) {
        val contact = vm.state.contacts.firstOrNull { it.email.equals(email, ignoreCase = true) } ?: return
        editDetail { copy(emailInput = contact.email, nameInput = contact.name, jobInput = contact.jobTitle) }
    }

    fun addRecipient() {
        val detail = vm.state.listDetail ?: return
        val added = recipientFrom(detail.emailInput, detail.nameInput, detail.jobInput, detail.recipients) ?: return
        editDetail { copy(recipients = recipients + added, emailInput = "", nameInput = "", jobInput = "") }
    }

    /** One typed member, or null with the reason said. */
    private fun recipientFrom(email: String, name: String, job: String, existing: List<Recipient>): Recipient? {
        val address = email.trim().lowercase()
        if (!isValidEmail(address)) {
            vm.fail(str(S.dd_invalid_email))
            return null
        }
        if (existing.any { it.email.equals(address, ignoreCase = true) }) {
            vm.notice(str(S.desktop_docdist_already_in_this_list))
            return null
        }
        return Recipient(email = address, name = name.trim(), jobTitle = job.trim())
    }

    fun removeRecipient(email: String) =
        editDetail { copy(recipients = recipients.filterNot { it.email.equals(email, ignoreCase = true) }) }

    fun save() {
        val detail = vm.state.listDetail ?: return
        if (detail.name.isBlank()) return vm.fail(str(S.desktop_list_name_required))
        if (vm.refusesWrite()) return
        editDetail { copy(saving = true) }
        vm.run {
            when (
                val result = vm.repository.updateList(
                    detail.listId,
                    detail.name,
                    detail.recipients,
                    detail.description,
                )
            ) {
                is ZillitResult.Success -> {
                    vm.update {
                        copy(
                            listDetail = listDetail?.copy(saving = false),
                            lists = lists.map { list ->
                                if (list.id != detail.listId) list
                                else list.copy(name = detail.name.trim(), recipients = detail.recipients)
                            },
                        )
                    }
                    vm.notice(str(S.saved))
                }
                is ZillitResult.Failure -> {
                    editDetail { copy(saving = false) }
                    vm.report(result.error)
                }
            }
        }
    }

    fun confirmRemove(listId: String) {
        if (vm.refusesWrite()) return
        val list = vm.state.lists.firstOrNull { it.id == listId } ?: return
        vm.update {
            copy(
                prompt = DocDistPrompt(
                    title = str(S.desktop_docdist_remove_list_title, list.name),
                    message = str(S.desktop_docdist_delete_list_message),
                    confirmLabel = str(S.remove),
                    event = DocDistEvent.DeleteList(listId),
                ),
            )
        }
    }

    fun delete(listId: String) {
        if (vm.refusesWrite()) return
        vm.run {
            vm.onSuccess(vm.repository.deleteList(listId)) {
                vm.update {
                    copy(
                        lists = lists.filterNot { it.id == listId },
                        listDetail = listDetail?.takeIf { it.listId != listId },
                    )
                }
                vm.notice(str(S.desktop_docdist_list_removed))
            }
        }
    }

    // -- CSV import ------------------------------------------------------------

    fun pickCsv(forEditor: Boolean) {
        vm.run {
            val file = vm.host.pickFiles().firstOrNull() ?: return@run
            if (!file.name.endsWith(".csv", ignoreCase = true) && !file.contentType.contains("csv")) {
                return@run vm.fail(str(S.desktop_docdist_choose_csv_file))
            }
            val existing = if (forEditor) vm.state.listEditor?.recipients else vm.state.listDetail?.recipients
            val rows = runCatching { Csv.toContacts(file.bytes.decodeToString(), existing.orEmpty().map { it.email }) }
                .getOrNull()
            if (rows == null) return@run vm.fail(str(S.desktop_docdist_could_not_parse_csv))
            if (rows.isEmpty()) return@run vm.fail(str(S.desktop_docdist_no_usable_rows))
            val parsed = CsvImportState(fileName = file.name, rows = rows)
            if (forEditor) editEditor { copy(csv = parsed) } else editDetail { copy(csv = parsed) }
        }
    }

    fun cancelCsv() {
        editDetail { copy(csv = null) }
        editEditor { copy(csv = null) }
    }

    fun confirmCsv() {
        val detail = vm.state.listDetail
        val editor = vm.state.listEditor
        val parsed = detail?.csv ?: editor?.csv ?: return
        val additions = parsed.importable.map { it.toRecipient() }
        if (detail?.csv != null) {
            editDetail { copy(recipients = recipients + additions, csv = null) }
        } else {
            editEditor { copy(recipients = recipients + additions, csv = null) }
        }
        if (additions.isEmpty()) vm.notice(str(S.desktop_docdist_nothing_new_in_csv))
        else vm.notice(plural(additions.size, S.desktop_docdist_added_one_contact, S.desktop_docdist_added_contacts))
    }

    // -- composer's inline editor ------------------------------------------------

    private inline fun editEditor(crossinline change: ListEditorState.() -> ListEditorState) =
        vm.update { copy(listEditor = listEditor?.change()) }

    fun openEditor() {
        if (vm.refusesWrite()) return
        vm.update { copy(listEditor = ListEditorState(), composer = composer.copy(listMenuOpen = false)) }
    }

    fun closeEditor() = vm.update { copy(listEditor = null) }

    fun editEditorFields(name: String?, description: String?) = editEditor {
        copy(name = name ?: this.name, description = description ?: this.description)
    }

    fun editEditorInput(email: String, name: String, job: String) =
        editEditor { copy(emailInput = email, nameInput = name, jobInput = job) }

    fun pickEditorContact(email: String) {
        val contact = vm.state.contacts.firstOrNull { it.email.equals(email, ignoreCase = true) } ?: return
        editEditor { copy(emailInput = contact.email, nameInput = contact.name, jobInput = contact.jobTitle) }
    }

    fun addEditorRecipient() {
        val editor = vm.state.listEditor ?: return
        val added = recipientFrom(editor.emailInput, editor.nameInput, editor.jobInput, editor.recipients) ?: return
        editEditor { copy(recipients = recipients + added, emailInput = "", nameInput = "", jobInput = "") }
    }

    fun removeEditorRecipient(email: String) =
        editEditor { copy(recipients = recipients.filterNot { it.email.equals(email, ignoreCase = true) }) }

    /** Creates the list and, when the composer is open, applies it there at once. */
    fun saveEditor(onCreated: (DistributionList) -> Unit) {
        val editor = vm.state.listEditor ?: return
        if (editor.name.isBlank()) return vm.fail(str(S.desktop_list_name_required))
        if (editor.recipients.isEmpty()) return vm.fail(str(S.dd_at_least_one_recipient))
        if (vm.refusesWrite()) return
        editEditor { copy(saving = true) }
        vm.run {
            when (val result = vm.repository.createList(editor.name, editor.recipients, editor.description)) {
                is ZillitResult.Success -> {
                    vm.update { copy(listEditor = null, lists = lists + result.data) }
                    vm.notice(str(S.dd_history_save_list_success))
                    onCreated(result.data)
                }
                is ZillitResult.Failure -> {
                    editEditor { copy(saving = false) }
                    vm.report(result.error)
                }
            }
        }
    }
}
