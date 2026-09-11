package com.zillit.desktop.feature.email.ui.contacts

import com.zillit.desktop.feature.email.data.EMAIL_CONTACTS_SYNC_EVENTS
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.email.domain.AddressBookRepository
import com.zillit.desktop.feature.email.domain.SavedContact
import com.zillit.desktop.feature.email.domain.matching
import com.zillit.desktop.feature.email.domain.validate

/**
 * The contact being written, if any.
 *
 * [SavedContact.id] is blank for a new one. Held whole rather than as a dozen
 * fields, so the form can change any of them with one event.
 */
data class ContactDraft(
    val contact: SavedContact,
    val error: String? = null,
) {
    val isNew: Boolean get() = contact.id.isBlank()
}

data class EmailContactsUiState(
    val contacts: List<SavedContact> = emptyList(),
    val query: String = "",
    val draft: ContactDraft? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    /** Deleting is irreversible, so it stops to ask (`ContactListActivity.kt:69-76`). */
    val pendingDelete: SavedContact? = null,
    val error: String? = null,
) {
    /** What the list shows: everything, or what matches the search. */
    val visible: List<SavedContact> get() = contacts.matching(query)
}

sealed interface EmailContactsEvent {
    data object Load : EmailContactsEvent
    data class QueryChanged(val value: String) : EmailContactsEvent

    /** Opens the form. Null starts a new contact. */
    data class Edit(val contact: SavedContact?) : EmailContactsEvent
    data class DraftChanged(val contact: SavedContact) : EmailContactsEvent
    data object Save : EmailContactsEvent
    data object CancelEdit : EmailContactsEvent

    /** Start a message to this contact — Android's tap on the address (`ContactListActivity.kt:152-163`). */
    data class WriteTo(val contact: SavedContact) : EmailContactsEvent

    data class AskDelete(val contact: SavedContact) : EmailContactsEvent
    data object ConfirmDelete : EmailContactsEvent
    data object DismissDelete : EmailContactsEvent
    data object DismissError : EmailContactsEvent
}

sealed interface EmailContactsEffect {
    /** Address a new message to [address]. What that means is the host's call. */
    data class WriteTo(val address: String) : EmailContactsEffect {
        override fun toString(): String = "WriteTo(…)"
    }
}

/**
 * The address book — Android `ContactListViewModel`
 * (`ui/contacts/ContactListViewModel.kt`) with the editor
 * (`EditContactActivity`) folded in, since on a desktop the form is a pane,
 * not a screen.
 */
class EmailContactsViewModel(
    private val repository: AddressBookRepository,
    /**
     * The socket, so an address saved on another device appears here. Null in
     * tests and on a build with no socket.
     */
    private val events: SocketEventBus? = null,
) : ZillitViewModel<EmailContactsUiState, EmailContactsEvent, EmailContactsEffect>(EmailContactsUiState()) {

    init {
        // Reload rather than patch: the list is searched, and a saved contact
        // can rename an existing row as easily as add one.
        events?.let { bus -> launch { bus.onAny(EMAIL_CONTACTS_SYNC_EVENTS).collect { load() } } }
    }

    override fun onEvent(event: EmailContactsEvent) {
        when (event) {
            EmailContactsEvent.Load -> load()
            is EmailContactsEvent.QueryChanged -> setState { copy(query = event.value) }
            is EmailContactsEvent.Edit -> setState {
                copy(draft = ContactDraft(event.contact ?: SavedContact(address = "")), error = null)
            }
            is EmailContactsEvent.DraftChanged -> setState {
                // The error clears as they type rather than persisting until
                // the next save, which would leave a red field just fixed.
                copy(draft = draft?.copy(contact = event.contact, error = null))
            }
            EmailContactsEvent.Save -> save()
            EmailContactsEvent.CancelEdit -> setState { copy(draft = null) }
            is EmailContactsEvent.WriteTo -> sendEffect(EmailContactsEffect.WriteTo(event.contact.address))
            is EmailContactsEvent.AskDelete -> setState { copy(pendingDelete = event.contact) }
            EmailContactsEvent.DismissDelete -> setState { copy(pendingDelete = null) }
            EmailContactsEvent.ConfirmDelete -> delete()
            EmailContactsEvent.DismissError -> setState { copy(error = null) }
        }
    }

    private fun load() {
        setState { copy(isLoading = contacts.isEmpty(), error = null) }
        launchResult(
            block = { repository.savedContacts() },
            onSuccess = { all -> setState { copy(isLoading = false, contacts = all) } },
            onError = { setState { copy(isLoading = false, error = it.localised()) } },
        )
    }

    private fun save() {
        val draft = currentState.draft ?: return
        val contact = draft.contact.trimmed()
        val invalid = contact.validate()
        if (invalid != null) {
            setState { copy(draft = draft.copy(error = invalid.message)) }
            return
        }
        setState { copy(isSaving = true, error = null) }
        launchResult(
            block = {
                if (draft.isNew) repository.save(contact) else repository.update(contact.id, contact)
            },
            onSuccess = {
                setState { copy(isSaving = false, draft = null) }
                // Reloaded rather than patched: a new contact's id is the
                // server's to assign, and Android refreshes the same way
                // (ContactListActivity.kt:40-44).
                load()
            },
            // The form stays open with the contact still in it.
            onError = { setState { copy(isSaving = false, draft = draft.copy(error = it.localised())) } },
        )
    }

    private fun delete() {
        val contact = currentState.pendingDelete ?: return
        setState { copy(pendingDelete = null) }
        launchResult(
            block = { repository.delete(contact.id) },
            onSuccess = { setState { copy(contacts = contacts.filterNot { it.id == contact.id }) } },
            onError = { setState { copy(error = it.localised()) } },
        )
    }
}

/** Every field trimmed, as Android's form does on save (`EditContactActivity.kt:359-389`). */
private fun SavedContact.trimmed() = copy(
    address = address.trim(),
    firstName = firstName.trim(),
    lastName = lastName.trim(),
    company = company.trim(),
    phone = phone.trim(),
    countryCode = countryCode.trim(),
    street = street.trim(),
    city = city.trim(),
    state = state.trim(),
    zipCode = zipCode.trim(),
    country = country.trim(),
    notes = notes.trim(),
)
