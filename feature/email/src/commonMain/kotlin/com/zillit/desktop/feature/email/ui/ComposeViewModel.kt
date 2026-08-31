package com.zillit.desktop.feature.email.ui

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.email.domain.ComposeMode
import com.zillit.desktop.feature.email.domain.ContactRepository
import com.zillit.desktop.feature.email.domain.AttachmentUploader
import com.zillit.desktop.feature.email.domain.EmailContact
import com.zillit.desktop.feature.email.domain.EmailSignature
import com.zillit.desktop.feature.email.domain.composedBody
import com.zillit.desktop.feature.email.domain.defaultFor
import com.zillit.desktop.feature.email.domain.OutgoingAttachment
import com.zillit.desktop.feature.email.domain.PickedFile
import com.zillit.desktop.feature.email.domain.UploadState
import com.zillit.desktop.feature.email.domain.areSettled
import com.zillit.desktop.feature.email.domain.completeRecipient
import com.zillit.desktop.feature.email.domain.currentRecipientToken
import com.zillit.desktop.feature.email.domain.suggestionsFor
import com.zillit.desktop.feature.email.domain.DraftRepository
import com.zillit.desktop.feature.email.domain.EmailDraft
import com.zillit.desktop.feature.email.domain.EmailMessage
import com.zillit.desktop.feature.email.domain.EmailRepository
import com.zillit.desktop.feature.email.domain.OutgoingEmail
import com.zillit.desktop.feature.email.domain.RichText
import com.zillit.desktop.feature.email.domain.htmlToRichText
import com.zillit.desktop.feature.email.domain.isWorthSaving
import com.zillit.desktop.feature.email.domain.replyDraft
import com.zillit.desktop.feature.email.domain.toHtml
import com.zillit.desktop.feature.email.domain.toOutgoing
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

data class ComposeUiState(
    val draft: OutgoingEmail = OutgoingEmail(),
    /**
     * The body being edited.
     *
     * Held apart from [draft] because the draft carries the *serialised* body —
     * HTML, which is what the wire takes — while the editor needs the document
     * with its marks intact. Converting on every keystroke would lose formatting
     * that HTML cannot express and re-parse what it can.
     */
    val body: RichText = RichText(),
    val mode: ComposeMode = ComposeMode.New,
    /** Raw text of the recipient fields, before it is split into addresses. */
    val toText: String = "",
    val ccText: String = "",
    val bccText: String = "",
    /** Cc and Bcc stay hidden until asked for — most mail needs neither. */
    val showsCopyFields: Boolean = false,
    /** Everyone this user could address, from the address book and the crew list. */
    val contacts: List<EmailContact> = emptyList(),
    /** Which recipient field is being typed in, if any. */
    val focusedField: RecipientField? = null,
    val attachments: List<OutgoingAttachment> = emptyList(),
    /** Every sign-off this user has, for the picker. */
    val signatures: List<EmailSignature> = emptyList(),
    /**
     * The one going out with this message.
     *
     * Held apart from [body] on purpose — see `composedBody`. Putting it in the
     * editor means every autosave and every switch has to find and remove the
     * old one, and the classic result is a message ending in three copies of
     * someone's phone number.
     */
    val signature: EmailSignature? = null,
    val isSending: Boolean = false,
    /** True once a draft exists on the server for this composer. */
    val isDraftSaved: Boolean = false,
    val error: String? = null,
) {
    /**
     * Sending is blocked while a file is still going up.
     *
     * The payload names objects in the bucket rather than carrying bytes, so a
     * send mid-upload arrives referring to an attachment that does not exist
     * yet. The send cannot wait for the upload, so the button does.
     */
    val canSend: Boolean
        get() = !isSending &&
            draft.copy(to = toText.toAddresses()).canSend &&
            attachments.areSettled

    /**
     * The fields as one sendable message.
     *
     * Recipients live as raw text while being typed — half an address is not an
     * address — and are only split when the message is actually used.
     */
    fun message(withSignature: Boolean = true): OutgoingEmail = draft.copy(
        to = toText.toAddresses(),
        cc = ccText.toAddresses(),
        bcc = bccText.toAddresses(),
        // Serialised here, at the edge, and joined with the signature at the
        // same moment — which is the only moment either of them is combined.
        // Drafts pass false: a draft is what was typed, and baking the
        // sign-off in would double it on every reopen-and-send — and make an
        // untouched composer "worth saving" the moment a default signature
        // exists.
        body = composedBody(body.toHtml(), signature.takeIf { withSignature }),
    )

    /**
     * Suggestions for the field being typed in.
     *
     * Matched against the *last* entry only — the field holds a comma-separated
     * list, and matching the whole string would find nothing the moment a
     * second recipient is added.
     */
    val suggestions: List<EmailContact>
        get() {
            val field = focusedField ?: return emptyList()
            val text = textFor(field)
            return contacts.suggestionsFor(
                query = text.currentRecipientToken(),
                // Everyone already on the message, across all three fields:
                // offering someone who is already in Cc is noise.
                exclude = allRecipients(),
            )
        }

    fun textFor(field: RecipientField): String = when (field) {
        RecipientField.To -> toText
        RecipientField.Cc -> ccText
        RecipientField.Bcc -> bccText
    }

    private fun allRecipients(): List<String> =
        toText.toAddresses() + ccText.toAddresses() + bccText.toAddresses()

    val title: String
        get() = when (mode) {
            ComposeMode.New -> "New message"
            ComposeMode.Reply -> "Reply"
            ComposeMode.ReplyAll -> "Reply all"
            ComposeMode.Forward -> "Forward"
        }
}

/** The three address fields, so one set of events serves all of them. */
enum class RecipientField { To, Cc, Bcc }

sealed interface ComposeEvent {

    /** Anything that edits the message: its fields, recipients or files. */
    sealed interface Field : ComposeEvent

    /** A recipient field gained or lost focus. Null closes the suggestions. */
    data class FocusChanged(val field: RecipientField?) : Field

    /** A suggestion was picked. */
    data class ContactPicked(val contact: EmailContact) : Field

    /** A file was chosen. Uploading starts at once. */
    data class AttachFile(val file: PickedFile) : Field

    data class RemoveAttachment(val id: String) : Field

    /** Opens the system file chooser. */
    data object PickFiles : Field

    /** Switches the sign-off. Null sends none. */
    data class SignatureChosen(val signature: EmailSignature?) : Field

    /** Opens the signature manager. */
    data object ManageSignatures : Field

    /**
     * The window is closing — save whatever is written.
     *
     * Explicit rather than tied to teardown: a draft save is a network call,
     * and firing it from a destroyed scope is how the last edit gets lost.
     */
    data object Closing : ComposeEvent

    /** Discards the draft entirely. */
    data object Discard : ComposeEvent

    data class ToChanged(val value: String) : Field
    data class CcChanged(val value: String) : Field
    data class BccChanged(val value: String) : Field
    data class SubjectChanged(val value: String) : Field
    data class BodyChanged(val value: RichText) : Field
    data object ToggleCopyFields : ComposeEvent
    data object Send : ComposeEvent
    data object DismissError : ComposeEvent
}

/** Whether an event changed the message, and so should restart the autosave clock. */
private val ComposeEvent.isEdit: Boolean
    get() = this is ComposeEvent.ToChanged ||
        this is ComposeEvent.CcChanged ||
        this is ComposeEvent.BccChanged ||
        this is ComposeEvent.SubjectChanged ||
        this is ComposeEvent.BodyChanged

sealed interface ComposeEffect {
    /** Accepted by the server — the window can close. */
    data object Sent : ComposeEffect

    /** The draft was thrown away; the window should close without saving. */
    data object Discarded : ComposeEffect

    /**
     * Open the system file chooser.
     *
     * An effect because the chooser is a platform thing and blocks its thread;
     * the ViewModel stays free of both.
     */
    data object ChooseFiles : ComposeEffect

    /** Open the signature manager in its own window. */
    data object OpenSignatures : ComposeEffect
}

/**
 * Writing a message.
 *
 * Its own ViewModel rather than more state on [EmailViewModel], following the
 * same reasoning as the create-production flow: the composer has its own
 * lifecycle, its own window, and four fields whose validity is independent of
 * anything the mailbox is doing.
 */
class ComposeViewModel(
    private val deps: Composing,
    mode: ComposeMode = ComposeMode.New,
    replyTo: EmailMessage? = null,
    /** Set when reopening a saved draft, so editing updates it rather than forking. */
    editing: EmailDraft? = null,
) : ZillitViewModel<ComposeUiState, ComposeEvent, ComposeEffect>(
    initial(mode, replyTo, deps.selfAddress(), editing),
) {

    /** Null until the first autosave, then the draft this composer owns. */
    private var draftId: String? = editing?.id

    /** True once sent or discarded, so a late autosave cannot resurrect it. */
    private var isSent = false

    /** The pending debounce. Cancelled freely — it has not asked the server anything. */
    private var debounceJob: Job? = null

    /**
     * The save currently talking to the server.
     *
     * Never cancelled, only queued behind. Cancelling an in-flight *create*
     * throws away the id the server assigned, so the next save creates a second
     * draft — verified against QA, where a mid-flight cancel showed up as a
     * `JobCancellationException` on the draft endpoint.
     */
    private var savingJob: Job? = null

    init {
        loadContacts()
        loadSignatures(mode)
    }

    override fun onEvent(event: ComposeEvent) {
        when (event) {
            is ComposeEvent.Field -> onField(event)
            ComposeEvent.ToggleCopyFields -> setState { copy(showsCopyFields = !showsCopyFields) }
            ComposeEvent.DismissError -> setState { copy(error = null) }
            ComposeEvent.Send -> send()
            ComposeEvent.Closing -> saveDraftNow()
            ComposeEvent.Discard -> discard()
        }

        // Any edit restarts the autosave clock.
        if (event.isEdit) scheduleDraftSave()
    }

    /**
     * Loads everyone this user could address.
     *
     * Crew come from the production context this app already holds, so opening
     * the composer costs one request rather than two — and works offline for
     * the people most messages go to.
     */
    /**
     * Loads sign-offs and picks the one for this kind of message.
     *
     * Silent on failure: a composer that refuses to open because a signature
     * list would not load is worse than one without a signature.
     */
    private fun loadSignatures(mode: ComposeMode) {
        launchResult(
            block = { deps.signatures.signatures() },
            onSuccess = { all ->
                setState {
                    // Only if the user has not already chosen — a slow response
                    // must not overwrite a deliberate pick.
                    copy(signatures = all, signature = signature ?: all.defaultFor(mode))
                }
            },
            onError = { ZillitLog.w(TAG) { "could not load signatures: ${it.technical}" } },
        )
    }

    private fun loadContacts() {
        setState { copy(contacts = deps.crew()) }

        launchResult(
            block = { deps.contacts.contacts() },
            onSuccess = { saved -> setState { copy(contacts = saved + deps.crew()) } },
            // Silent: the crew list is already there, and a failed address book
            // is not worth an error over a message being written.
            onError = { ZillitLog.w(TAG) { "could not load contacts: ${it.technical}" } },
        )
    }

    /** Replaces the entry being typed with the chosen contact. */
    private fun pick(contact: EmailContact) {
        val field = currentState.focusedField ?: return
        val completed = currentState.textFor(field).completeRecipient(contact)

        setState {
            when (field) {
                RecipientField.To -> copy(toText = completed)
                RecipientField.Cc -> copy(ccText = completed)
                RecipientField.Bcc -> copy(bccText = completed)
            }
        }
        scheduleDraftSave()
    }

    /** Editing the message itself: its fields, its recipients, its files. */
    private fun onField(event: ComposeEvent.Field) {
        when (event) {
            is ComposeEvent.ToChanged -> setState { copy(toText = event.value, error = null) }
            is ComposeEvent.CcChanged -> setState { copy(ccText = event.value) }
            is ComposeEvent.BccChanged -> setState { copy(bccText = event.value) }
            is ComposeEvent.SubjectChanged -> setState {
                copy(draft = draft.copy(subject = event.value))
            }
            is ComposeEvent.BodyChanged -> setState { copy(body = event.value) }
            is ComposeEvent.FocusChanged -> setState { copy(focusedField = event.field) }
            is ComposeEvent.ContactPicked -> pick(event.contact)
            is ComposeEvent.AttachFile -> attach(event.file)
            ComposeEvent.PickFiles -> sendEffect(ComposeEffect.ChooseFiles)
            is ComposeEvent.SignatureChosen -> setState { copy(signature = event.signature) }
            ComposeEvent.ManageSignatures -> sendEffect(ComposeEffect.OpenSignatures)
            is ComposeEvent.RemoveAttachment -> setState {
                copy(attachments = attachments.filterNot { it.id == event.id })
            }
        }
    }

    /**
     * Adds a file and starts uploading it.
     *
     * The chip appears immediately and fills in behind — a file picker that
     * seems to do nothing for the length of an upload is one people click
     * twice.
     */
    private fun attach(file: PickedFile) {
        val uploader = deps.uploader ?: return
        val attachment = OutgoingAttachment(
            id = deps.newAttachmentId(),
            fileName = file.name,
            sizeBytes = file.bytes.size.toLong(),
            contentType = file.contentType,
        )
        setState { copy(attachments = attachments + attachment) }

        launch {
            val result = uploader.upload(file.name, file.contentType, file.bytes) { percent ->
                updateAttachment(attachment.id) { it.copy(state = UploadState.InProgress(percent)) }
            }
            updateAttachment(attachment.id) {
                when (result) {
                    is ZillitResult.Success -> it.copy(state = UploadState.Uploaded(result.data))
                    is ZillitResult.Failure -> it.copy(
                        state = UploadState.Failed(result.error.localised()),
                    )
                }
            }
        }
    }

    private fun updateAttachment(id: String, change: (OutgoingAttachment) -> OutgoingAttachment) {
        setState {
            copy(attachments = attachments.map { if (it.id == id) change(it) else it })
        }
    }

    // -- drafts ------------------------------------------------------------

    /**
     * Saves a second after typing stops.
     *
     * Debounced rather than saved per keystroke: a draft save is a round trip,
     * and one per character would put a request behind every letter of a long
     * mail. One second matches Android, and is short enough that closing the
     * lid mid-sentence loses nothing.
     */
    private fun scheduleDraftSave() {
        debounceJob?.cancel()
        debounceJob = launch {
            delay(AUTOSAVE_DELAY_MILLIS)
            saveDraftNow()
        }
    }

    /**
     * Saves now, dropping any pending debounce.
     *
     * Saves are serialised rather than overlapped: the first one creates and
     * every later one updates, so letting a second start before the first
     * returns would have it create a duplicate.
     */
    private fun saveDraftNow() {
        debounceJob?.cancel()
        val previous = savingJob
        savingJob = launch {
            previous?.join()
            saveDraft()
        }
    }

    private suspend fun saveDraft() {
        val message = currentState.message(withSignature = false)
        // An empty composer opened and closed must not litter the Drafts
        // folder — and closing saves, so without this it would.
        //
        // `isSending` matters as much as `isSent`: a queued save that lands
        // between the send starting and finishing would create a draft the
        // send cannot name, and so nothing would ever clear it.
        if (!message.isWorthSaving || isSent || currentState.isSending) return

        val existing = draftId
        val result = if (existing == null) {
            deps.drafts.saveDraft(message).also { saved ->
                if (saved is ZillitResult.Success) draftId = saved.data.takeIf { it.isNotBlank() }
            }
        } else {
            deps.drafts.updateDraft(existing, message)
        }

        // Autosave is silent. A failed background save must not put an error
        // over a message the user is still writing; the text is still on
        // screen, and the next save will try again.
        if (result is ZillitResult.Failure) {
            ZillitLog.w(TAG) { "draft autosave failed: ${result.error.technical}" }
        } else {
            setState { copy(isDraftSaved = draftId != null) }
        }
    }

    private fun discard() {
        debounceJob?.cancel()
        val existing = draftId
        draftId = null
        // Marked sent so a late autosave cannot recreate what was discarded.
        isSent = true

        launch {
            if (existing != null) deps.drafts.deleteDrafts(listOf(existing))
            sendEffect(ComposeEffect.Discarded)
        }
    }

    private fun send() {
        // The draft id lives on this class, not in the state the fields build:
        // it is bookkeeping, not something the user typed. It has to be
        // attached here or the server never learns which draft to clear, and
        // the sent mail leaves a copy in Drafts forever.
        val message = currentState.message().copy(
            draftId = draftId,
            // Only what actually landed. A failed upload is left in the list so
            // the user can see it, but it must not be named in the payload.
            attachments = currentState.attachments.mapNotNull { it.stored },
        )
        if (!message.canSend) {
            setState { copy(error = "Add at least one valid recipient.") }
            return
        }
        // The button is already disabled while a file is in flight, but this is
        // the send itself: [areSettled] is the reason, and a message that goes
        // now names objects that do not exist yet. The web says the same
        // ("Please wait while files are uploading") rather than sending a mail
        // whose attachment never appears.
        if (!currentState.attachments.areSettled) {
            setState { copy(error = "Please wait while files finish uploading.") }
            return
        }

        // No *new* autosave may land after this: the send carries the draft id
        // so the server deletes it, and a save arriving afterwards would
        // recreate it. `isSent` stops any already-queued save from writing.
        debounceJob?.cancel()
        setState { copy(isSending = true, error = null) }

        launchResult(
            block = { deps.repository.send(message) },
            onSuccess = {
                isSent = true
                draftId = null
                setState { copy(isSending = false) }
                sendEffect(ComposeEffect.Sent)
            },
            // The window stays open on failure with everything still typed in
            // it. Losing a written message to a network blip is unforgivable.
            onError = { setState { copy(isSending = false, error = it.localised()) } },
        )
    }

    private companion object {
        const val TAG = "Email"
        const val AUTOSAVE_DELAY_MILLIS = 1_000L

        fun initial(
            mode: ComposeMode,
            replyTo: EmailMessage?,
            selfAddress: String,
            editing: EmailDraft?,
        ): ComposeUiState {
            val draft = editing?.toOutgoing()
                ?: replyTo?.replyDraft(mode, selfAddress)
                ?: OutgoingEmail()

            return ComposeUiState(
                draft = draft,
                // A reopened draft holds HTML; a fresh reply holds the plain
                // quoted original. Both have to arrive as an editable document.
                body = if (editing != null) htmlToRichText(draft.body) else RichText.plain(draft.body),
                mode = mode,
                toText = draft.to.joinToString(", "),
                ccText = draft.cc.joinToString(", "),
                bccText = draft.bcc.joinToString(", "),
                // Shown from the start when the draft already uses them,
                // otherwise a reopened draft would appear to have lost its Cc.
                showsCopyFields = draft.cc.isNotEmpty() || draft.bcc.isNotEmpty(),
                isDraftSaved = editing != null,
            )
        }
    }
}

/**
 * Splits a recipient field into addresses.
 *
 * Commas and semicolons both separate, because both are what people paste from
 * other mail clients. Whitespace does not: a pasted `"Khan, Aisha"
 * <a@b.com>` would otherwise shatter into three.
 *
 * Each entry is then stripped to the bare address: picking a suggestion
 * inserts the friendly `Name <addr>` form, and both the validity check and
 * the outgoing payload want `addr` alone. Leaving the decoration on made
 * every picked recipient read as invalid — the Send button stayed off for
 * exactly the people chosen the recommended way — and would have posted the
 * display name to the server on the rare path that got through.
 */
internal fun String.toAddresses(): List<String> =
    split(',', ';').map { it.trim().bareAddress() }.filter(String::isNotEmpty)

/** The address inside `Name <addr>`, or the string itself when undecorated. */
private fun String.bareAddress(): String {
    val open = lastIndexOf('<')
    val close = lastIndexOf('>')
    if (open < 0 || close <= open + 1) return this
    return substring(open + 1, close).trim()
}
