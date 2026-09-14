package com.zillit.desktop.feature.email.ui

import com.zillit.desktop.feature.email.domain.ComposeMode
import com.zillit.desktop.feature.email.domain.EmailAttachment
import com.zillit.desktop.feature.email.domain.EmailDraft
import com.zillit.desktop.feature.email.domain.EmailFilters
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.EmailMessage
import com.zillit.desktop.feature.email.domain.EmailRealtimeEvent
import com.zillit.desktop.feature.email.domain.EmailSummary
import com.zillit.desktop.feature.email.domain.MailRow
import com.zillit.desktop.feature.email.domain.MailboxIdentity
import com.zillit.desktop.feature.email.domain.MailboxKind
import com.zillit.desktop.feature.email.domain.applyFilters
import com.zillit.desktop.feature.email.domain.defaultFolder
import com.zillit.desktop.feature.email.domain.forSidebar
import com.zillit.desktop.feature.email.domain.groupIntoThreads
import com.zillit.desktop.feature.email.domain.matchingSearch
import com.zillit.desktop.feature.email.domain.moveTargets
import com.zillit.desktop.feature.email.domain.search
import com.zillit.desktop.feature.email.domain.EmailQuery

/**
 * The two mailboxes and which is open — what the sidebar's switcher draws.
 *
 * [accounts] is null for everyone outside the Accounts department, and the
 * switcher is then not drawn at all: the web gates the whole control on
 * `accounts_mail_box_detail.email_address`.
 */
data class MailboxSwitch(
    val personal: MailboxIdentity? = null,
    val accounts: MailboxIdentity? = null,
    val active: MailboxKind = MailboxKind.Personal,
    /** Unread per mailbox from the badge ledger, keyed by address. */
    val unreadByAddress: Map<String, Int> = emptyMap(),
) {
    val hasAccounts: Boolean get() = accounts != null

    val activeIdentity: MailboxIdentity?
        get() = if (active == MailboxKind.Accounts) accounts ?: personal else personal

    val activeAddress: String get() = activeIdentity?.address.orEmpty()

    fun identity(kind: MailboxKind): MailboxIdentity? =
        if (kind == MailboxKind.Accounts) accounts else personal

    fun unread(kind: MailboxKind): Int =
        identity(kind)?.address?.let { address ->
            unreadByAddress.entries.firstOrNull { it.key.equals(address, ignoreCase = true) }?.value
        } ?: 0

    /** The web's dot: the mailbox you are NOT looking at has unread mail. */
    val inactiveHasUnread: Boolean
        get() = unread(if (active == MailboxKind.Accounts) MailboxKind.Personal else MailboxKind.Accounts) > 0
}

/** The strip over the mailbox while the full sync runs — "Syncing Emails… 45%". */
data class SyncProgress(val percent: Int)

data class EmailUiState(
    val mailboxes: MailboxSwitch = MailboxSwitch(),
    val folders: List<EmailFolder> = emptyList(),
    /**
     * The badge service's unread per folder (`email_label` grouped by unit,
     * where the unit is the folder name) — the number the phones badge each
     * folder with. Distinct from the folder list's IMAP `unread`, which is
     * the mail server's own count and can disagree with the badge ledger.
     */
    val folderBadges: Map<String, Int> = emptyMap(),
    val selectedFolderName: String? = null,
    /** The open folder's cached rows, newest first, before filters and grouping. */
    val messages: List<EmailSummary> = emptyList(),
    /** [messages] as the list draws them — see [computeRows]. */
    val rows: List<MailRow> = emptyList(),
    /** Every folder's cached rows — what conversations are counted and opened from. */
    val everything: List<EmailSummary> = emptyList(),
    /** Whether the list stacks a conversation into one row (the mailbox's own setting). */
    val conversationView: Boolean = false,
    val filters: EmailFilters = EmailFilters.None,
    /** The header's search box. Matches the open folder; [searchAllFolders] widens it. */
    val searchTerm: String = "",
    val searchAllFolders: Boolean = false,
    /** The row that is open, and the conversation it opened. */
    val openRowId: String? = null,
    val thread: List<EmailMessage> = emptyList(),
    val isLoadingThread: Boolean = false,
    val isLoadingFolders: Boolean = false,
    val isLoadingMessages: Boolean = false,
    /** The full-mailbox pass in flight; null between passes. */
    val sync: SyncProgress? = null,
    val isRefreshing: Boolean = false,
    /**
     * Saved drafts, held whole rather than as summaries.
     *
     * The composer needs the real thing when one is reopened, and the Drafts
     * folder is small enough that keeping it in memory costs nothing.
     */
    val drafts: List<EmailDraft> = emptyList(),
    /** Rows ticked for a bulk action, by message id (draft id in Drafts). */
    val selectedIds: Set<String> = emptySet(),
    /** A destructive action waiting on the user to confirm it. */
    val pendingConfirm: PendingConfirm? = null,
    /** The rows [pendingConfirm] is about — kept apart so cancelling restores the ticks. */
    val pendingIds: Set<String> = emptySet(),
    /** A small dialog with one button — the selection cap, today. */
    val info: MailInfo? = null,
    /** The conversation-view dialog is up. */
    val conversationDialog: Boolean = false,
    val isSavingConversationView: Boolean = false,
    /** The switcher's one-time tour. */
    val tourOpen: Boolean = false,
    /** A short confirmation the toast shows — "Emails moved to Trash". */
    val notice: String? = null,
    val error: String? = null,
) {
    val sidebar: List<EmailFolder> get() = folders.forSidebar()

    val selectedFolder: EmailFolder?
        get() = folders.firstOrNull { it.name == selectedFolderName } ?: folders.defaultFolder()

    /** True when the open folder is Drafts, which behaves differently throughout. */
    val isViewingDrafts: Boolean
        get() = selectedFolder?.name.equals(EmailFolder.DRAFTS, ignoreCase = true)

    /** True when the open folder is Trash, where delete destroys rather than moves. */
    val isViewingTrash: Boolean
        get() = selectedFolder?.name.equals(EmailFolder.TRASH, ignoreCase = true)

    val isViewingSent: Boolean
        get() = selectedFolder?.name.equals(EmailFolder.SENT, ignoreCase = true)

    fun draft(id: String): EmailDraft? = drafts.firstOrNull { it.id == id }

    /**
     * What the list shows: the open folder's rows through the filters and the
     * search box, stacked into conversations when the mailbox asks for it.
     * A search across every folder answers with plain rows — a conversation
     * from three folders has no one folder to be stacked in.
     *
     * Stored, not derived on read: grouping walks every cached row, and the
     * screen reads this several times per frame. [regrouped] recomputes it
     * whenever one of its inputs changes.
     */
    fun computeRows(): List<MailRow> {
        val folder = selectedFolder?.name.orEmpty()
        if (searchAllFolders && searchTerm.isNotBlank()) {
            return everything.applyFilters(filters).search(EmailQuery(term = searchTerm)).map { MailRow(it) }
        }
        val narrowed = messages.applyFilters(filters).matchingSearch(searchTerm)
        return groupIntoThreads(narrowed, everything, conversationView && !isViewingDrafts, folder)
    }

    /** This state with [rows] brought up to date with its inputs. */
    fun regrouped(): EmailUiState = copy(rows = computeRows())

    val openRow: MailRow? get() = rows.firstOrNull { it.id == openRowId }

    /** The newest message of the open conversation, which the toolbar's verbs answer. */
    val newestOpen: EmailMessage? get() = thread.maxByOrNull { it.receivedAtMillis }

    val selectedMessages: List<EmailSummary>
        get() = messages.filter { it.id in selectedIds }

    /** Where the selection could be moved to. */
    val moveTargets: List<EmailFolder> get() = folders.moveTargets(selectedFolderName)

    val hasSelection: Boolean get() = selectedIds.isNotEmpty()

    /** True while the filters narrow the list — the dot on the Filters button. */
    val filtersActive: Boolean get() = filters.isActive

    /** The active mailbox's own address, for the composer's From row and the badge ledger. */
    val activeMailboxAddress: String get() = mailboxes.activeAddress
}

/** A destructive action the user has been asked to confirm. */
sealed interface PendingConfirm {

    /** Trashing ticked rows — the web asks even for this (`DeleteConfirmModal`). */
    data class TrashSelected(val count: Int) : PendingConfirm

    /** Permanently destroying the selection. Only ever raised from Trash. */
    data class Destroy(val messageIds: List<String>) : PendingConfirm

    /** Deleting ticked drafts. */
    data class DeleteDrafts(val draftIds: List<String>) : PendingConfirm

    /** One message of an open conversation — "Are you sure you want to delete?" */
    data class DeleteOne(val message: EmailMessage) : PendingConfirm

    data object EmptyTrash : PendingConfirm

    /**
     * Deleting a folder, and whatever is in it.
     *
     * [cachedCount] is what this machine holds, which is a floor rather than a
     * total — a folder only partly synced holds more on the server. Worded to
     * say so, because "delete 12 messages" when it is really 400 is worse than
     * saying nothing.
     */
    data class DeleteFolder(val folder: EmailFolder, val cachedCount: Int) : PendingConfirm
}

/** A one-button dialog that only informs. */
sealed interface MailInfo {
    /** The web's "Selection Limited" — more than 30 rows ticked. */
    data object SelectionLimit : MailInfo
}

sealed interface EmailEvent {
    data object Load : EmailEvent

    /** A different production opened; the mailbox starts over. */
    data object ProjectChanged : EmailEvent
    data object Refresh : EmailEvent

    /** Anything that acts on one message. */
    sealed interface Message : EmailEvent

    data object CloseMessage : Message

    data class SelectFolder(val folderName: String) : EmailEvent

    /** Opens a row — a message, or the conversation it stands for. */
    data class SelectMessage(val rowId: String) : Message

    /** Opens the newest row of the folder, when nothing is open — the web's auto-open. */
    data object OpenFirst : Message

    data class QueryChanged(val value: String) : EmailEvent
    data object ToggleSearchAllFolders : EmailEvent

    data class FiltersChanged(val filters: EmailFilters) : EmailEvent
    data object ClearFilters : EmailEvent

    /**
     * Opens the composer. [replyTo] is null for a new message.
     *
     * [addressedTo] and [about] prefill a message the app itself offered to
     * start — writing to support is the one today — and are ignored for a
     * reply, which brings its own recipient.
     */
    data class Compose(
        val mode: ComposeMode,
        val replyTo: EmailMessage? = null,
        val addressedTo: String = "",
        val about: String = "",
    ) : Message

    /** Something changed on the server. */
    data class Realtime(val event: EmailRealtimeEvent) : EmailEvent

    /** Managing the folder list itself. */
    sealed interface Folder : EmailEvent

    /** Opens the folder dialog. [folder] is null to create a new one. */
    data class EditFolder(val folder: EmailFolder? = null) : Folder

    data class FolderNameChanged(val value: String) : Folder

    /** Creates or renames, whichever the dialog was opened for. */
    data object SaveFolder : Folder

    /** Asks to delete [folder] — from the sidebar's row menu, or the dialog's own button. */
    data class DeleteFolder(val folder: EmailFolder? = null) : Folder
    data object DismissFolderEdit : Folder

    /** Everything to do with ticked rows and acting on them. */
    sealed interface Selection : EmailEvent

    /** Ticks or unticks a row. */
    data class ToggleSelection(val rowId: String) : Selection

    /** The select-all menu: All, None, Read, Unread. */
    data class SelectRows(val which: SelectionChoice) : Selection
    data object ClearSelection : Selection

    /** Trash the selection — or destroy it, when already in Trash. Asks first. */
    data object DeleteSelected : Selection

    /** The open conversation, from the reading pane's toolbar. */
    data object DeleteOpen : Selection

    /** One message of the open conversation. Asks first. */
    data class DeleteOne(val message: EmailMessage) : Selection

    data class MoveSelected(val folderName: String) : Selection

    /** The open conversation, from the reading pane's toolbar. */
    data class MoveOpen(val folderName: String) : Selection

    /** A row dragged onto a folder. */
    data class DropOnFolder(val rowId: String, val folderName: String) : Selection

    data object EmptyTrash : Selection

    /** Goes ahead with whatever [EmailUiState.pendingConfirm] holds. */
    data object ConfirmPending : Selection
    data object DismissConfirm : Selection
    data object DismissInfo : Selection

    /** Reopens a saved draft in the composer. */
    data class EditDraft(val draftId: String) : Message

    /** Fetches an attachment and writes it to the user's Downloads folder. */
    data class DownloadAttachment(
        val attachment: EmailAttachment,
        val messageId: String,
        /** The folder the message's copy lives in — a thread spans folders. */
        val folderName: String,
    ) : Message

    /** Prints the open conversation, or one message of it. */
    data class Print(val message: EmailMessage? = null) : Message

    /** Tears the open conversation off into a window of its own. */
    data object PopOut : Message

    /** Offers an address to the address book. */
    data class AddToContacts(val address: String) : Message

    /** The mailbox switcher and its tour. */
    sealed interface Mailbox : EmailEvent
    data class SwitchMailbox(val kind: MailboxKind) : Mailbox
    data object ShowTour : Mailbox
    data object DismissTour : Mailbox

    /** The conversation-view dialog, off the Settings menu. */
    data object OpenConversationDialog : EmailEvent
    data object DismissConversationDialog : EmailEvent
    data class ConversationViewChanged(val enabled: Boolean) : EmailEvent

    data object DismissError : EmailEvent
    data object DismissNotice : EmailEvent
}

/** The select-all menu's four choices. */
enum class SelectionChoice { All, None, Read, Unread }

sealed interface EmailEffect {
    /** Hand off to the composer, which stands in the reading pane. */
    data class OpenComposer(
        val mode: ComposeMode,
        val replyTo: EmailMessage?,
        /** Prefilled when the app offered to start this message. */
        val addressedTo: String = "",
        val about: String = "",
    ) : EmailEffect

    /** Reopen a saved draft in the composer. */
    data class OpenDraft(val draftId: String) : EmailEffect

    /** Opens the conversation in a window of its own. */
    data class PopOutThread(val subject: String, val messages: List<EmailMessage>) : EmailEffect

    /** Hands a print-ready page to the host. */
    data class Print(val title: String, val html: String) : EmailEffect

    /** Opens the address book on a new contact. */
    data class AddToContacts(val address: String) : EmailEffect
}
