package com.zillit.desktop.feature.documentdistribution.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.Csv
import com.zillit.desktop.feature.documentdistribution.domain.DistributionList
import com.zillit.desktop.feature.documentdistribution.domain.HISTORY_MAX_LIMIT
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.domain.isValidEmail

/**
 * The address book: every recipient the production has ever seen, the
 * lists each is on, and the sends each received.
 *
 * List membership is edited by rewriting the list's whole recipient set —
 * the presets endpoint replaces rather than patches — exactly as the web's
 * modal does.
 */
@Suppress("TooManyFunctions") // One handler per user action.
internal class ContactsSection(private val vm: VmScope, private val library: LibrarySection) {

    fun load() {
        vm.update { copy(loading = true, loadingContactEmails = true, error = null) }
        vm.run {
            val contacts = vm.repository.contacts()
            val lists = vm.repository.lists()
            vm.update {
                when (contacts) {
                    is ZillitResult.Success -> {
                        val kept = selectedContactEmail?.takeIf { email -> contacts.data.any { it.email == email } }
                        copy(
                            loading = false,
                            contacts = contacts.data,
                            lists = (lists as? ZillitResult.Success)?.data ?: this.lists,
                            // The first row is selected on open, as the web does, so
                            // the detail pane is never a blank "Select a contact".
                            selectedContactEmail = kept
                                ?: contacts.data.minByOrNull { it.displayName.lowercase() }?.email,
                        )
                    }
                    is ZillitResult.Failure -> copy(loading = false, error = contacts.error.userMessage)
                }
            }
            // Best-effort: the sent-emails pane, never blocking the book.
            val sends = vm.repository.history(page = 0, search = "", limit = HISTORY_MAX_LIMIT)
            vm.update {
                copy(
                    loadingContactEmails = false,
                    contactDistributions = (sends as? ZillitResult.Success)?.data?.rows ?: contactDistributions,
                )
            }
        }
    }

    fun select(email: String) = vm.update { copy(selectedContactEmail = email) }

    // -- editor ----------------------------------------------------------------

    fun openAdd() {
        if (vm.refusesWrite()) return
        vm.update { copy(contactEditor = ContactEditorState()) }
    }

    fun openEdit() {
        if (vm.refusesWrite()) return
        val contact = vm.state.selectedContact ?: return
        vm.update {
            copy(
                contactEditor = ContactEditorState(
                    originalEmail = contact.email,
                    name = contact.name,
                    email = contact.email,
                    job = contact.jobTitle,
                    listIds = contact.lists.map { it.id },
                ),
            )
        }
    }

    fun edit(name: String?, email: String?, job: String?, listIds: List<String>?) = vm.update {
        copy(
            contactEditor = contactEditor?.copy(
                name = name ?: contactEditor.name,
                email = email ?: contactEditor.email,
                job = job ?: contactEditor.job,
                listIds = listIds ?: contactEditor.listIds,
            ),
        )
    }

    fun close() = vm.update { copy(contactEditor = null) }

    @Suppress("CyclomaticComplexMethod") // Add, edit and rename are one form on the web too.
    fun saveEditor() {
        val editor = vm.state.contactEditor ?: return
        val email = editor.email.trim().lowercase()
        if (!isValidEmail(email)) return vm.fail("Enter a valid email")
        if (vm.refusesWrite()) return
        val original = editor.originalEmail.lowercase()
        val clashes = vm.state.contacts.any { it.email.lowercase() == email && it.email.lowercase() != original }
        if ((editor.isNew || editor.emailChanged) && clashes) return vm.notice(
            "That email is already in your address book",
        )
        val contact = Contact(email = email, name = editor.name.trim(), jobTitle = editor.job.trim())
        vm.update { copy(contactEditor = editor.copy(saving = true)) }
        vm.run {
            val saved = vm.repository.saveContact(contact)
            if (saved is ZillitResult.Failure) {
                vm.update { copy(contactEditor = contactEditor?.copy(saving = false)) }
                return@run vm.report(saved.error)
            }
            val listFailures = reconcileLists(editor, contact)
            // On rename, the old standalone row goes; sent emails keep the old address.
            if (editor.emailChanged) vm.repository.deleteContact(original)
            vm.update { copy(contactEditor = null, selectedContactEmail = email) }
            when {
                listFailures -> vm.fail("Contact saved, but updating one or more lists failed")
                editor.isNew && editor.listIds.isNotEmpty() ->
                    vm.notice("Contact added to ${plural(editor.listIds.size, "list")}")
                editor.isNew -> vm.notice("Contact added")
                else -> vm.notice("Contact updated")
            }
            load()
        }
    }

    /** Puts the contact on the lists it should be on and off those it should not; true on any failure. */
    private suspend fun reconcileLists(editor: ContactEditorState, contact: Contact): Boolean {
        val current = vm.state.selectedContact?.takeIf { !editor.isNew }?.lists?.map { it.id }.orEmpty().toSet()
        val desired = editor.listIds.toSet()
        var failed = false
        for (listId in current + desired) {
            val list = vm.state.lists.firstOrNull { it.id == listId } ?: continue
            var recipients = list.recipients
            var changed = false
            if (listId in current && (listId !in desired || editor.emailChanged)) {
                val before = recipients.size
                recipients = recipients.filterNot { it.email.equals(editor.originalEmail, ignoreCase = true) }
                changed = changed || recipients.size != before
            }
            if (listId in desired && recipients.none { it.email.equals(contact.email, ignoreCase = true) }) {
                recipients = recipients + Recipient(contact.email, contact.name, contact.jobTitle)
                changed = true
            }
            if (changed && vm.repository.updateList(listId, null, recipients) is ZillitResult.Failure) failed = true
        }
        return failed
    }

    fun confirmDelete(email: String) {
        if (vm.refusesWrite()) return
        val contact = vm.state.contacts.firstOrNull { it.email.equals(email, ignoreCase = true) } ?: return
        vm.update {
            copy(
                prompt = DocDistPrompt(
                    title = "Delete contact",
                    message = if (contact.lists.isNotEmpty()) {
                        "Removes ${contact.displayName} from your address book and " +
                            "${plural(contact.lists.size, "distribution list")}."
                    } else {
                        "Remove ${contact.displayName} from your address book?"
                    },
                    confirmLabel = "Delete",
                    event = DocDistEvent.DeleteContact(contact.email),
                ),
            )
        }
    }

    fun delete(email: String) {
        if (vm.refusesWrite()) return
        vm.run {
            vm.onSuccess(vm.repository.deleteContact(email)) {
                vm.update { copy(selectedContactEmail = null, contactEditor = null) }
                vm.notice("Contact deleted")
                load()
            }
        }
    }

    /** The plain save the older editor raised — kept for the render tests. */
    fun save(contact: Contact) {
        if (vm.refusesWrite()) return
        vm.run {
            vm.onSuccess(vm.repository.saveContact(contact)) {
                vm.notice("Contact saved")
                load()
            }
        }
    }

    // -- list membership ---------------------------------------------------------

    fun addToList(listId: String) {
        val contact = vm.state.selectedContact ?: return
        val list = vm.state.lists.firstOrNull { it.id == listId } ?: return
        if (vm.refusesWrite()) return
        rewrite(
            list,
            list.recipients + Recipient(contact.email, contact.name, contact.jobTitle),
            "Added to \"${list.name}\"",
        )
    }

    fun removeFromList(listId: String) {
        val contact = vm.state.selectedContact ?: return
        val list = vm.state.lists.firstOrNull { it.id == listId } ?: return
        if (vm.refusesWrite()) return
        rewrite(
            list,
            list.recipients.filterNot { it.email.equals(contact.email, ignoreCase = true) },
            "Removed from \"${list.name}\"",
        )
    }

    private fun rewrite(list: DistributionList, recipients: List<Recipient>, success: String) {
        vm.update { copy(contactListBusyId = list.id) }
        vm.run {
            val result = vm.repository.updateList(list.id, null, recipients)
            vm.update { copy(contactListBusyId = null) }
            vm.onSuccess(result) {
                vm.notice(success)
                load()
            }
        }
    }

    fun copyEmail() {
        val contact = vm.state.selectedContact ?: return
        vm.host.copyToClipboard(contact.email)
        vm.notice("Email copied")
    }

    fun exportCsv() {
        val contacts = vm.state.contacts
        if (contacts.isEmpty()) return vm.notice("No contacts to export")
        if (vm.refusesDownload()) return
        vm.run { library.saveAndOpen("Address Book.csv", Csv.contacts(contacts).encodeToByteArray()) }
    }

    fun viewEmail(distributionId: String?) = vm.update {
        copy(viewingEmail = distributionId?.let { id -> contactDistributions.firstOrNull { it.id == id } })
    }
}
