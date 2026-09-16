package com.zillit.desktop.feature.email.ui

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.email.domain.ComposeMode
import com.zillit.desktop.feature.email.domain.ContactSource
import com.zillit.desktop.feature.email.domain.EmailAttachment
import com.zillit.desktop.feature.email.domain.EmailContact
import com.zillit.desktop.feature.email.domain.EmailDraft
import com.zillit.desktop.feature.email.domain.EmailMessage
import com.zillit.desktop.feature.email.domain.EmailSignature
import com.zillit.desktop.feature.email.domain.OutgoingAttachment
import com.zillit.desktop.feature.email.domain.OutgoingEmail
import com.zillit.desktop.feature.email.domain.PickedFile
import com.zillit.desktop.feature.email.domain.RichText
import com.zillit.desktop.feature.email.domain.StoredFile
import com.zillit.desktop.feature.email.domain.UploadState
import com.zillit.desktop.feature.email.domain.areSettled
import com.zillit.desktop.feature.email.domain.composedBody
import com.zillit.desktop.feature.email.domain.defaultFor
import com.zillit.desktop.feature.email.domain.headerAddress
import com.zillit.desktop.feature.email.domain.htmlToRichText
import com.zillit.desktop.feature.email.domain.isValidEmail
import com.zillit.desktop.feature.email.domain.isWorthSaving
import com.zillit.desktop.feature.email.domain.mailFullTimeLabel
import com.zillit.desktop.feature.email.domain.replyDraft
import com.zillit.desktop.feature.email.domain.suggestionsFor
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
    /** The address rows: committed chips, and what is being typed after them. */
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val toInput: String = "",
    val ccInput: String = "",
    val bccInput: String = "",
    /** Everyone this user could address: the address book, the crew and the groups. */
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
    /** The `From:` row — the active mailbox's address. */
    val fromAddress: String = "",
    /** Whether the quoted original is unfolded under the editor. */
    val quoteExpanded: Boolean = false,
    val isSending: Boolean = false,
    val isSavingDraft: Boolean = false,
    /** True once a draft exists on the server for this composer. */
    val isDraftSaved: Boolean = false,
    /** "Send this email without a subject?" is up. */
    val asksSubjectless: Boolean = false,
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
        get() = !isSending && allTo().any { it.isValidEmail() } && attachments.areSettled

    /** Chips plus whatever is still being typed — a typed address counts once Send is pressed. */
    private fun allTo(): List<String> = to + toInput.trim().takeIf { it.isNotEmpty() }.let(::listOfNotNull)

    /**
     * The fields as one sendable message.
     *
     * Drafts pass false: a draft is what was typed, and baking the sign-off
     * in would double it on every reopen-and-send — and make an untouched
     * composer "worth saving" the moment a default signature exists.
     */
    fun message(withSignature: Boolean = true): OutgoingEmail = draft.copy(
        to = (to + toInput.asToken()).map { it.headerAddress() }.filter { it.isNotBlank() },
        cc = (cc + ccInput.asToken()).map { it.headerAddress() }.filter { it.isNotBlank() },
        bcc = (bcc + bccInput.asToken()).map { it.headerAddress() }.filter { it.isNotBlank() },
        // Serialised here, at the edge, and joined with the signature and
        // the quoted original at the same moment — which is the only moment
        // any of them is combined.
        body = composedBody(body.toHtml(), signature.takeIf { withSignature }, draft.quotedHtml),
    )

    /**
     * Suggestions for the field being typed in, matched against what is
     * typed there and leaving out everyone already on the message.
     */
    val suggestions: List<EmailContact>
        get() {
            val field = focusedField ?: return emptyList()
            return contacts.suggestionsFor(query = inputFor(field), exclude = to + cc + bcc)
        }

    fun tokensFor(field: RecipientField): List<String> = when (field) {
        RecipientField.To -> to
        RecipientField.Cc -> cc
        RecipientField.Bcc -> bcc
    }

    fun inputFor(field: RecipientField): String = when (field) {
        RecipientField.To -> toInput
        RecipientField.Cc -> ccInput
        RecipientField.Bcc -> bccInput
    }

    /** Whether [address] is someone the mailbox already knows — crew, a contact, a group. */
    fun isKnown(address: String): Boolean =
        contacts.any { it.address.equals(address.headerAddress(), ignoreCase = true) }

    /** The chip's label: the name we know them by, or the address. */
    fun labelFor(address: String): String =
        contacts.firstOrNull { it.address.equals(address.headerAddress(), ignoreCase = true) }
            ?.name?.takeIf { it.isNotBlank() } ?: address.headerAddress()

    val hasQuote: Boolean get() = draft.quotedHtml.isNotBlank()

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

private fun String.asToken(): List<String> = trim().takeIf { it.isNotEmpty() }.let(::listOfNotNull)

sealed interface ComposeEvent {

    /** Anything that edits the message: its fields, recipients or files. */
    sealed interface Field : ComposeEvent

    /** A recipient row changed — chips and the text after them, as one value. */
    data class RecipientsChanged(val field: RecipientField, val tokens: List<String>, val input: String) : Field

    /** A recipient field gained or lost focus. Null closes the suggestions. */
    data class FocusChanged(val field: RecipientField?) : Field

    /** A suggestion was picked. */
    data class ContactPicked(val contact: EmailContact) : Field

    /** A file was chosen. Uploading starts at once. */
    data class AttachFile(val file: PickedFile) : Field

    data class RemoveAttachment(val id: String) : Field

    /** One of the original's files, dropped from a reply or forward. */
    data class RemoveForwarded(val id: String) : Field

    /** Opens the system file chooser. */
    data object PickFiles : Field

    /** The attach sheet's answer — the chooser filtered to one kind. */
    data class PickFilesOf(val kind: PreviewKind) : Field

    /** Switches the sign-off. Null sends none. */
    data class SignatureChosen(val signature: EmailSignature?) : Field

    /** Opens the signature manager. */
    data object ManageSignatures : Field

    data class SubjectChanged(val value: String) : Field
    data class BodyChanged(val value: RichText) : Field

    data object ToggleQuote : ComposeEvent

    /**
     * The window is closing — save whatever is written.
     *
     * Explicit rather than tied to teardown: a draft save is a network call,
     * and firing it from a destroyed scope is how the last edit gets lost.
     */
    data object Closing : ComposeEvent

    /** "Save as draft": saved now, and the composer closes on it. */
    data object SaveDraft : ComposeEvent

    /** Discards the draft this composer created and closes. */
    data object Discard : ComposeEvent

    data object Send : ComposeEvent

    /** The subject-less dialog's "Send Anyway". */
    data object SendAnyway : ComposeEvent
    data object DismissSubjectless : ComposeEvent

    /** Offers an address on a chip to the address book. */
    data class AddToContacts(val address: String) : ComposeEvent
    data object DismissError : ComposeEvent
}

/** Whether an event changed the message, and so should restart the autosave clock. */
private val ComposeEvent.isEdit: Boolean
    get() = this is ComposeEvent.RecipientsChanged ||
        this is ComposeEvent.ContactPicked ||
        this is ComposeEvent.SubjectChanged ||
        this is ComposeEvent.BodyChanged ||
        this is ComposeEvent.RemoveAttachment ||
        this is ComposeEvent.RemoveForwarded

sealed interface ComposeEffect {
    /** Accepted by the server — the composer can close. */
    data object Sent : ComposeEffect

    /** The draft was thrown away; the composer should close without saving. */
    data object Discarded : ComposeEffect

    /** Saved on request; the composer closes and the Drafts folder has it. */
    data object DraftSaved : ComposeEffect

    /**
     * Open the system file chooser.
     *
     * An effect because the chooser is a platform thing and blocks its thread;
     * the ViewModel stays free of both.
     */
    data object ChooseFiles : ComposeEffect

    /** As [ChooseFiles], filtered to one kind from the attach sheet. */
    data class ChooseFilesOf(val kind: PreviewKind) : ComposeEffect

    /** Open the signature manager in its own window. */
    data object OpenSignatures : ComposeEffect

    /** Open the address book on a new contact for [address]. */
    data class AddToContacts(val address: String) : ComposeEffect

    /** A short confirmation for the host's toast. */
    data class Notice(val message: String) : ComposeEffect
}

/**
 * Writing a message.
 *
 * Its own ViewModel rather than more state on [EmailViewModel], following the
 * same reasoning as the create-production flow: the composer has its own
 * lifecycle, its own pane, and three address rows whose validity is
 * independent of anything the mailbox is doing.
 */
class ComposeViewModel(
    private val deps: Composing,
    mode: ComposeMode = ComposeMode.New,
    replyTo: EmailMessage? = null,
    /** Set when reopening a saved draft, so editing updates it rather than forking. */
    editing: EmailDraft? = null,
    /**
     * A recipient and subject the composer opens already carrying.
     *
     * For a message the app itself offers to start — writing to support is the
     * one today. Ignored for a reply or a reopened draft, which bring their own.
     */
    addressedTo: String = "",
    about: String = "",
    /**
     * Words and files handed over by another tool — chat's Share. The files
     * are already in storage, so they open as uploaded attachments; the
     * words open in the editor. Ignored, like the two above, for a reply or
     * a reopened draft.
     */
    bodyHtml: String = "",
    attachments: List<StoredFile> = emptyList(),
) : ZillitViewModel<ComposeUiState, ComposeEvent, ComposeEffect>(
    initial(mode, replyTo, deps, editing, addressedTo, about, bodyHtml, attachments),
) {

    /** Null until the first autosave, then the draft this composer owns. */
    private var draftId: String? = editing?.id

    /** True when this composer opened a saved draft — Discard then closes it rather than deleting it. */
    private val reopened: Boolean = editing != null

    /**
     * This composer's `unique_id`, minted once and sent on every create.
     *
     * A create whose answer is lost — a slow link, a lid closed mid-save —
     * leaves [draftId] null, and the next autosave creates again. Before the
     * key that was the classic duplicate: the server had the first draft, and
     * now made a second. With it the server answers the retry with the record
     * it already holds, and [draftId] lands on the same one either way.
     */
    private val draftKey: String = deps.newDraftKey()

    /** True once sent or discarded, so a late autosave cannot resurrect it. */
    private var isSent = false

    /**
     * True once the user has changed something. Autosave waits for it: a
     * composer that opened with a signature and the BCC presets already in
     * it is not a draft anyone wrote (the web's `dirtyRef`).
     */
    private var dirty = false

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
            ComposeEvent.ToggleQuote -> setState { copy(quoteExpanded = !quoteExpanded) }
            ComposeEvent.DismissError -> setState { copy(error = null) }
            ComposeEvent.Send -> send(force = false)
            ComposeEvent.SendAnyway -> send(force = true)
            ComposeEvent.DismissSubjectless -> setState { copy(asksSubjectless = false) }
            ComposeEvent.Closing -> saveDraftNow()
            ComposeEvent.SaveDraft -> saveOnRequest()
            ComposeEvent.Discard -> discard()
            is ComposeEvent.AddToContacts -> sendEffect(ComposeEffect.AddToContacts(event.address))
        }

        // Any edit restarts the autosave clock.
        if (event.isEdit) {
            dirty = true
            scheduleDraftSave()
        }
    }

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

    /**
     * Loads everyone this user could address: the crew the host already
     * holds at once, then the address book, the crew by mailbox address and
     * the distribution groups as each answers. Every source is optional —
     * a failed address book is not worth an error over a message being
     * written.
     */
    private fun loadContacts() {
        setState { copy(contacts = deps.crew()) }
        launch {
            val saved = (deps.contacts.contacts() as? ZillitResult.Success)?.data.orEmpty()
            val crew = when (val result = deps.crewMailboxes()) {
                is ZillitResult.Success -> result.data.ifEmpty { deps.crew() }
                is ZillitResult.Failure -> {
                    ZillitLog.w(TAG) { "could not load crew mailboxes: ${result.error.technical}" }
                    deps.crew()
                }
            }
            val groups = (deps.groups() as? ZillitResult.Success)?.data.orEmpty().mapNotNull { group ->
                group.address?.takeIf { it.isNotBlank() }?.let { address ->
                    EmailContact(
                        address = address,
                        name = group.name,
                        source = ContactSource.Group,
                        subtitle = "Email group",
                    )
                }
            }
            setState { copy(contacts = saved + crew + groups) }
        }
    }

    /** Replaces the entry being typed with the chosen contact. */
    private fun pick(contact: EmailContact) {
        val field = currentState.focusedField ?: return
        val tokens = currentState.tokensFor(field)
        if (tokens.any { it.headerAddress().equals(contact.address, ignoreCase = true) }) {
            setRecipients(field, tokens, "")
            return
        }
        setRecipients(field, tokens + contact.address, "")
    }

    private fun setRecipients(field: RecipientField, tokens: List<String>, input: String) {
        val cleaned = tokens.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() }
        setState {
            when (field) {
                RecipientField.To -> copy(to = cleaned, toInput = input, error = null)
                RecipientField.Cc -> copy(cc = cleaned, ccInput = input)
                RecipientField.Bcc -> copy(bcc = cleaned, bccInput = input)
            }
        }
    }

    /** Editing the message itself: its fields, its recipients, its files. */
    private fun onField(event: ComposeEvent.Field) {
        when (event) {
            is ComposeEvent.RecipientsChanged -> setRecipients(event.field, event.tokens, event.input)
            is ComposeEvent.SubjectChanged -> setState { copy(draft = draft.copy(subject = event.value)) }
            is ComposeEvent.BodyChanged -> setState { copy(body = event.value) }
            is ComposeEvent.FocusChanged -> setState { copy(focusedField = event.field) }
            is ComposeEvent.ContactPicked -> pick(event.contact)
            is ComposeEvent.AttachFile -> attach(event.file)
            ComposeEvent.PickFiles -> sendEffect(ComposeEffect.ChooseFiles)
            is ComposeEvent.PickFilesOf -> sendEffect(ComposeEffect.ChooseFilesOf(event.kind))
            is ComposeEvent.SignatureChosen -> setState { copy(signature = event.signature) }
            ComposeEvent.ManageSignatures -> sendEffect(ComposeEffect.OpenSignatures)
            is ComposeEvent.RemoveAttachment -> setState {
                copy(attachments = attachments.filterNot { it.id == event.id })
            }
            is ComposeEvent.RemoveForwarded -> setState {
                copy(draft = draft.copy(forwarded = draft.forwarded.filterNot { it.id == event.id }))
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
        dirty = true

        launch {
            val result = uploader.upload(file.name, file.contentType, file.bytes) { percent ->
                updateAttachment(attachment.id) { it.copy(state = UploadState.InProgress(percent)) }
            }
            updateAttachment(attachment.id) {
                when (result) {
                    is ZillitResult.Success -> it.copy(state = UploadState.Uploaded(result.data))
                    is ZillitResult.Failure -> it.copy(state = UploadState.Failed(result.error.localised()))
                }
            }
            if (result is ZillitResult.Success) scheduleDraftSave()
        }
    }

    private fun updateAttachment(id: String, change: (OutgoingAttachment) -> OutgoingAttachment) {
        setState { copy(attachments = attachments.map { if (it.id == id) change(it) else it }) }
    }

    // -- drafts ------------------------------------------------------------

    /**
     * Saves a second after typing stops.
     *
     * Debounced rather than saved per keystroke: a draft save is a round trip,
     * and one per character would put a request behind every letter of a long
     * mail. One second matches Android and the web, and is short enough that
     * closing the lid mid-sentence loses nothing.
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
    private fun saveDraftNow(onSaved: () -> Unit = {}) {
        debounceJob?.cancel()
        val previous = savingJob
        savingJob = launch {
            previous?.join()
            saveDraft()
            onSaved()
        }
    }

    private suspend fun saveDraft(quiet: Boolean = true) {
        val message = currentState.message(withSignature = false).withStoredFiles()
        // An untouched composer must not litter the Drafts folder — and
        // closing saves, so without this it would.
        //
        // `isSending` matters as much as `isSent`: a queued save that lands
        // between the send starting and finishing would create a draft the
        // send cannot name, and so nothing would ever clear it.
        val sendUnderway = isSent || currentState.isSending
        if (!dirty || !message.isWorthSaving || sendUnderway) return

        val existing = draftId
        val result = if (existing == null) {
            deps.drafts.saveDraft(message, draftKey).also { saved ->
                if (saved is ZillitResult.Success) draftId = saved.data.takeIf { it.isNotBlank() }
            }
        } else {
            deps.drafts.updateDraft(existing, message)
        }

        // Autosave is silent. A failed background save must not put an error
        // over a message the user is still writing; the text is still on
        // screen, and the next save will try again.
        when (result) {
            is ZillitResult.Failure -> {
                ZillitLog.w(TAG) { "draft autosave failed: ${result.error.technical}" }
                if (!quiet) setState { copy(error = result.error.localised()) }
            }
            is ZillitResult.Success -> setState { copy(isDraftSaved = draftId != null) }
        }
    }

    /** Only what actually landed goes on the wire; a failed upload stays visible and unsent. */
    private fun OutgoingEmail.withStoredFiles(): OutgoingEmail =
        copy(attachments = currentState.attachments.mapNotNull { it.stored })

    /**
     * "Save as draft": the web saves, toasts, and closes the composer with the
     * Drafts folder holding it. Recipients typed here are remembered as
     * contacts too (`handleDraftSaved` with `fromAutoSave = false`).
     */
    private fun saveOnRequest() {
        if (currentState.isSavingDraft || currentState.isSending) return
        dirty = true
        setState { copy(isSavingDraft = true, error = null) }
        debounceJob?.cancel()
        val previous = savingJob
        savingJob = launch {
            previous?.join()
            saveDraft(quiet = false)
            setState { copy(isSavingDraft = false) }
            if (draftId != null) {
                rememberRecipients()
                sendEffect(ComposeEffect.Notice("Draft saved"))
                sendEffect(ComposeEffect.DraftSaved)
            }
        }
    }

    /**
     * Discard, the web's way (`closeComposeForm(true)`): a draft THIS
     * composer created is deleted; one opened from the Drafts folder is left
     * there and merely closed — this composer only ever updated it.
     */
    private fun discard() {
        debounceJob?.cancel()
        val own = draftId?.takeIf { !reopened }
        draftId = null
        // Marked sent so a late autosave cannot recreate what was discarded.
        isSent = true
        launch {
            savingJob?.join()
            if (own != null) deps.drafts.deleteDrafts(listOf(own))
            sendEffect(ComposeEffect.Discarded)
        }
    }

    // -- sending -----------------------------------------------------------

    /**
     * Sends, after the two checks the web makes first: a recipient, and — for
     * a new message with no subject — a dialog asking whether to send it
     * without one (`send_email_without_subject_text`).
     */
    private fun send(force: Boolean) {
        val state = currentState
        setState { copy(asksSubjectless = false) }
        // The draft id lives on this class, not in the state the fields build:
        // it is bookkeeping, not something the user typed. It has to be
        // attached here or the server never learns which draft to clear, and
        // the sent mail leaves a copy in Drafts forever.
        val message = state.message().copy(draftId = draftId).withStoredFiles()
        if (message.to.none { it.isValidEmail() }) {
            setState { copy(error = "Please input the recipient email") }
            return
        }
        // The button is already disabled while a file is in flight, but this is
        // the send itself: [areSettled] is the reason, and a message that goes
        // now names objects that do not exist yet. The web says the same
        // ("Please wait while files are uploading") rather than sending a mail
        // whose attachment never appears.
        if (!state.attachments.areSettled) {
            setState { copy(error = "Please wait while the files are uploading") }
            return
        }
        if (!force && message.subject.isBlank() && state.mode == ComposeMode.New) {
            setState { copy(asksSubjectless = true) }
            return
        }

        // No *new* autosave may land after this: the send carries the draft id
        // so the server deletes it, and a save arriving afterwards would
        // recreate it. `isSent` stops any already-queued save from writing.
        debounceJob?.cancel()
        setState { copy(isSending = true, error = null) }

        launch {
            // A save still in flight would land after the send and leave a
            // copy of this mail in Drafts: wait for it, so the send names it.
            savingJob?.join()
            val settled = message.copy(draftId = draftId)
            when (val result = deps.repository.send(settled)) {
                is ZillitResult.Success -> {
                    isSent = true
                    draftId = null
                    rememberRecipients()
                    setState { copy(isSending = false) }
                    sendEffect(ComposeEffect.Notice("Email sent"))
                    sendEffect(ComposeEffect.Sent)
                }
                // The composer stays open on failure with everything still typed
                // in it. Losing a written message to a network blip is unforgivable.
                is ZillitResult.Failure -> setState { copy(isSending = false, error = result.error.localised()) }
            }
        }
    }

    /**
     * Every address the message went to that the production does not already
     * know becomes a contact — the web's `enqueueContactsSave`. Fire and
     * forget: a failure here is not a failure of the send.
     */
    private fun rememberRecipients() {
        val state = currentState
        val crew = state.contacts.filter { it.source != ContactSource.Saved }.map { it.address.lowercase() }.toSet()
        val fresh = (state.to + state.cc + state.bcc)
            .map { it.headerAddress() }
            .filter { it.isValidEmail() && it.lowercase() !in crew }
        if (fresh.isEmpty()) return
        launch {
            val result = deps.contacts.saveAddresses(fresh)
            if (result is ZillitResult.Failure) {
                ZillitLog.w(TAG) { "could not remember recipients: ${result.error.technical}" }
            }
        }
    }

    private companion object {
        const val TAG = "Email"
        const val AUTOSAVE_DELAY_MILLIS = 1_000L

        @Suppress("LongParameterList") // Every source a composer can open from.
        fun initial(
            mode: ComposeMode,
            replyTo: EmailMessage?,
            deps: Composing,
            editing: EmailDraft?,
            addressedTo: String = "",
            about: String = "",
            bodyHtml: String = "",
            attachments: List<StoredFile> = emptyList(),
        ): ComposeUiState {
            val mailbox = deps.mailbox()
            val self = mailbox?.address?.takeIf { it.isNotBlank() } ?: deps.selfAddress()
            val quotedAt = replyTo?.receivedAtMillis?.let(::mailFullTimeLabel).orEmpty()
            val draft = editing?.toOutgoing()
                ?: replyTo?.replyDraft(mode, self, quotedAt)
                // Last, so a reply or a reopened draft keeps its own recipient:
                // this only fills a composer that would otherwise open empty.
                ?: OutgoingEmail(
                    to = listOfNotNull(addressedTo.takeIf(String::isNotBlank)),
                    subject = about,
                    body = bodyHtml,
                )
            // A handed-over file is already stored: it opens as attached, not
            // as a pending upload, and rides the send like one picked here.
            val seeded = if (editing == null && replyTo == null) {
                attachments.map { stored ->
                    OutgoingAttachment(
                        id = deps.newAttachmentId(),
                        fileName = stored.fileName,
                        sizeBytes = stored.sizeBytes,
                        contentType = stored.contentType,
                        state = UploadState.Uploaded(stored),
                    )
                }
            } else {
                emptyList()
            }

            // Every new message starts with the mailbox's BCC presets, as on
            // both other clients; a reopened draft keeps what it was saved with.
            val presets = if (editing == null) mailbox?.bccPresets.orEmpty() else emptyList()
            val bcc = (draft.bcc + presets).map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase() }

            return ComposeUiState(
                draft = draft,
                // A reopened draft holds HTML, as does a handed-over body;
                // a reply opens empty with the original quoted below.
                body = if (editing != null || (replyTo == null && bodyHtml.isNotBlank())) {
                    htmlToRichText(draft.body)
                } else {
                    RichText()
                },
                attachments = seeded,
                mode = mode,
                to = draft.to,
                cc = draft.cc,
                bcc = bcc,
                fromAddress = self,
                isDraftSaved = editing != null,
            )
        }
    }
}
