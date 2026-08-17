package com.zillit.desktop.feature.email.ui

import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.email.domain.EmailSignature
import com.zillit.desktop.feature.email.domain.RichText
import com.zillit.desktop.feature.email.domain.SignatureRepository
import com.zillit.desktop.feature.email.domain.htmlToRichText
import com.zillit.desktop.feature.email.domain.toHtml

/**
 * The signature being written, if any.
 *
 * [id] is null for a new one. The body is a [RichText] document rather than
 * HTML because signatures are exactly where people want bold and a link — the
 * same editor the composer uses.
 */
data class SignatureDraft(
    val id: String? = null,
    val title: String = "",
    val body: RichText = RichText(),
) {
    val isNew: Boolean get() = id == null

    /** A signature with no title is unpickable in the menu. */
    val canSave: Boolean get() = title.isNotBlank()
}

data class SignatureManagerUiState(
    val signatures: List<EmailSignature> = emptyList(),
    val draft: SignatureDraft? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    /** Deleting is irreversible, so it stops to ask. */
    val pendingDelete: EmailSignature? = null,
    val error: String? = null,
)

sealed interface SignatureEvent {
    data object Load : SignatureEvent

    /** Opens the editor. Null starts a new signature. */
    data class Edit(val signature: EmailSignature?) : SignatureEvent

    data class TitleChanged(val value: String) : SignatureEvent
    data class BodyChanged(val value: RichText) : SignatureEvent

    data object Save : SignatureEvent
    data object CancelEdit : SignatureEvent

    /** Which kinds of message this signature is applied to automatically. */
    data class UsageChanged(
        val signature: EmailSignature,
        val useForNew: Boolean,
        val useForReply: Boolean,
    ) : SignatureEvent

    data class AskDelete(val signature: EmailSignature) : SignatureEvent
    data object ConfirmDelete : SignatureEvent
    data object DismissDelete : SignatureEvent
}

/**
 * Managing sign-offs.
 *
 * Its own ViewModel and its own window rather than a panel inside the composer:
 * editing a signature is a task in its own right, and doing it inside a
 * half-written message means one of the two has to be abandoned to finish the
 * other.
 */
class SignatureManagerViewModel(
    private val repository: SignatureRepository,
) : ZillitViewModel<SignatureManagerUiState, SignatureEvent, Nothing>(SignatureManagerUiState()) {

    override fun onEvent(event: SignatureEvent) {
        when (event) {
            SignatureEvent.Load -> load()

            is SignatureEvent.Edit -> setState {
                copy(
                    draft = SignatureDraft(
                        id = event.signature?.id,
                        title = event.signature?.title.orEmpty(),
                        // Parsed back into a document: reopening must give an
                        // editable signature, not raw markup.
                        body = htmlToRichText(event.signature?.body.orEmpty()),
                    ),
                    error = null,
                )
            }

            is SignatureEvent.TitleChanged -> setState {
                copy(draft = draft?.copy(title = event.value))
            }
            is SignatureEvent.BodyChanged -> setState { copy(draft = draft?.copy(body = event.value)) }
            SignatureEvent.CancelEdit -> setState { copy(draft = null) }

            SignatureEvent.Save -> save()
            is SignatureEvent.UsageChanged -> setUsage(event)

            is SignatureEvent.AskDelete -> setState { copy(pendingDelete = event.signature) }
            SignatureEvent.DismissDelete -> setState { copy(pendingDelete = null) }
            SignatureEvent.ConfirmDelete -> delete()
        }
    }

    private fun load() {
        setState { copy(isLoading = signatures.isEmpty(), error = null) }

        launchResult(
            block = { repository.signatures() },
            onSuccess = { all -> setState { copy(isLoading = false, signatures = all) } },
            onError = { setState { copy(isLoading = false, error = it.localised()) } },
        )
    }

    private fun save() {
        val draft = currentState.draft?.takeIf { it.canSave } ?: return
        val body = draft.body.toHtml()

        setState { copy(isSaving = true, error = null) }

        launchResult<Unit>(
            block = {
                // Create returns the saved signature; update returns nothing.
                // Both are discarded — the list is reloaded either way, because
                // a new signature's id and flags are the server's to assign.
                draft.id
                    ?.let { id -> repository.update(id, draft.title.trim(), body) }
                    ?: repository.create(draft.title.trim(), body).map { }
            },
            onSuccess = {
                setState { copy(isSaving = false, draft = null) }
                // Reloaded rather than patched locally: a new signature has an
                // id only the server knows, and the usage flags are its call.
                load()
            },
            // The editor stays open with the text still in it. Losing a written
            // signature to a network blip is a small thing done twice.
            onError = { setState { copy(isSaving = false, error = it.localised()) } },
        )
    }

    /**
     * Sets which messages a signature is applied to.
     *
     * Exclusive: turning one on turns the others off, because the composer
     * picks the *first* match and two defaults would make which one wins depend
     * on the server's ordering.
     */
    private fun setUsage(event: SignatureEvent.UsageChanged) {
        val others = currentState.signatures.filter { it.id != event.signature.id }

        setState {
            copy(
                signatures = signatures.map { signature ->
                    if (signature.id == event.signature.id) {
                        signature.copy(useForNew = event.useForNew, useForReply = event.useForReply)
                    } else {
                        signature.copy(
                            useForNew = signature.useForNew && !event.useForNew,
                            useForReply = signature.useForReply && !event.useForReply,
                        )
                    }
                },
            )
        }

        launch {
            repository.setUsage(event.signature.id, event.useForNew, event.useForReply)
            // Only the ones that actually had a flag to lose.
            others.filter { (it.useForNew && event.useForNew) || (it.useForReply && event.useForReply) }
                .forEach { signature ->
                    repository.setUsage(
                        id = signature.id,
                        useForNew = signature.useForNew && !event.useForNew,
                        useForReply = signature.useForReply && !event.useForReply,
                    )
                }
            load()
        }
    }

    private fun delete() {
        val signature = currentState.pendingDelete ?: return
        setState { copy(pendingDelete = null) }

        launchResult(
            block = { repository.delete(signature.id) },
            onSuccess = {
                setState { copy(signatures = signatures.filterNot { it.id == signature.id }) }
            },
            onError = { setState { copy(error = it.localised()) } },
        )
    }
}
