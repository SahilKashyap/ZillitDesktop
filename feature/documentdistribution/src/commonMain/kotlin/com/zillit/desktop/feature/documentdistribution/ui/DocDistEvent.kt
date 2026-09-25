package com.zillit.desktop.feature.documentdistribution.ui

import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.LibrarySort
import com.zillit.desktop.feature.documentdistribution.domain.LocalFile
import com.zillit.desktop.feature.documentdistribution.domain.PublishDraft
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkStyle
import com.zillit.desktop.feature.documentdistribution.domain.parseAddressList

/** Everything the user can do in this tool. */
sealed interface DocDistEvent {
    /** The restriction banner's button: ask an admin for the missing right. */
    data class RequestRights(val kind: RightsKind) : DocDistEvent

    data object Refresh : DocDistEvent
    data object ClearNotice : DocDistEvent
    data object DismissPrompt : DocDistEvent
    data object ConfirmPrompt : DocDistEvent
    data object DismissInfoBanner : DocDistEvent

    data class Open(val destination: DocDistDestination) : DocDistEvent

    // -- library ----------------------------------------------------------

    /** Null navigates to the library root. */
    data class OpenFolder(val folderId: String?) : DocDistEvent
    data object GoUp : DocDistEvent
    data object OpenNewFolder : DocDistEvent
    data class OpenEditFolder(val folderId: String) : DocDistEvent
    data class EditFolderName(val text: String) : DocDistEvent
    data class EditFolderDescription(val text: String) : DocDistEvent
    data class EditFolderDate(val isoDate: String) : DocDistEvent
    data object CloseFolderEditor : DocDistEvent
    data object SaveFolder : DocDistEvent
    /** Asks first; [DeleteFolder] is what the confirmation raises. */
    data class ConfirmDeleteFolder(val folderId: String) : DocDistEvent
    data class DeleteFolder(val folderId: String) : DocDistEvent

    data class Search(val text: String) : DocDistEvent
    data class FilterByDate(val isoDate: String?) : DocDistEvent
    data object ClearFilters : DocDistEvent
    data class SortBy(val sort: LibrarySort) : DocDistEvent
    data class SetView(val view: LibraryView) : DocDistEvent
    data object LoadMore : DocDistEvent

    data class ToggleDocument(val documentId: String) : DocDistEvent
    data class ToggleFolder(val folderId: String) : DocDistEvent
    data object ClearSelection : DocDistEvent
    /** Selects or clears every row on screen. */
    data class SelectAll(val selected: Boolean) : DocDistEvent

    data class ConfirmDeleteDocument(val documentId: String) : DocDistEvent
    data class DeleteDocument(val documentId: String) : DocDistEvent
    data object ConfirmDeleteSelection : DocDistEvent
    data object DeleteSelection : DocDistEvent

    /** Opens the OS file dialog and uploads what is chosen into the open folder. */
    data object PickAndUpload : DocDistEvent
    /** Files dropped on the window. */
    data class DropFiles(val files: List<LocalFile>) : DocDistEvent
    data class DragHover(val hovering: Boolean) : DocDistEvent

    data class OpenDocument(val documentId: String) : DocDistEvent
    data object ClosePreview : DocDistEvent
    data class DownloadDocument(val documentId: String) : DocDistEvent
    /** From the preview: a record whose bytes are gone. */
    data object RemoveMissingRecord : DocDistEvent

    // publish
    data object OpenPublishSelection : DocDistEvent
    data class PublishDocument(val documentId: String) : DocDistEvent
    data class PublishFolder(val folderId: String) : DocDistEvent
    data object ClosePublish : DocDistEvent
    data class ChoosePublishTarget(val category: String) : DocDistEvent
    data class EditPublishDraft(val draft: PublishDraft) : DocDistEvent
    data class ToggleReplaceTarget(val chatId: String) : DocDistEvent
    data object ConfirmPublish : DocDistEvent

    // move
    data object OpenMove : DocDistEvent
    data object CloseMove : DocDistEvent
    data class ChooseMoveDestination(val folderId: String?) : DocDistEvent
    data object ConfirmMove : DocDistEvent
    data class MoveSelection(val folderId: String?) : DocDistEvent

    // watermark + download
    data class OpenWatermarkDownload(val documentId: String) : DocDistEvent
    data object CloseWatermarkDownload : DocDistEvent
    data class EditWatermarkLine1(val text: String) : DocDistEvent
    data class EditWatermarkLine2(val text: String) : DocDistEvent
    data class EditWatermarkDownloadStyle(val style: WatermarkStyle) : DocDistEvent
    data object ConfirmWatermarkDownload : DocDistEvent

    data object OpenWatermarkBatch : DocDistEvent
    data object CloseWatermarkBatch : DocDistEvent
    data class RemoveBatchDocument(val documentId: String) : DocDistEvent
    data class EditBatchStyle(val style: WatermarkStyle) : DocDistEvent
    data class EditBatchRecipientInput(val text: String) : DocDistEvent
    data object AddBatchRecipient : DocDistEvent
    data class AddBatchRecipientFromContact(val email: String) : DocDistEvent
    data class RemoveBatchRecipient(val email: String) : DocDistEvent
    data class BatchListMenu(val open: Boolean) : DocDistEvent
    data class AddBatchList(val listId: String) : DocDistEvent
    data object ConfirmWatermarkBatch : DocDistEvent

    // -- the project's watermark settings --------------------------------------
    data object OpenWatermarkSettings : DocDistEvent
    data object CloseWatermarkSettings : DocDistEvent
    data class EditWatermarkSettings(val style: WatermarkStyle) : DocDistEvent
    data object ResetWatermarkSettings : DocDistEvent
    data object SaveWatermarkSettings : DocDistEvent

    // library picker (composer + batch)
    data class OpenPicker(val purpose: PickerPurpose) : DocDistEvent
    data object ClosePicker : DocDistEvent
    data class PickerFolder(val folderId: String?) : DocDistEvent
    data class PickerSearch(val text: String) : DocDistEvent
    data class PickerToggle(val documentId: String) : DocDistEvent
    data object PickerToggleAllVisible : DocDistEvent
    data object PickerClear : DocDistEvent
    data object PickerConfirm : DocDistEvent

    // -- composer ---------------------------------------------------------

    /** Opens the composer pre-loaded with whatever is selected. */
    data object Compose : DocDistEvent
    data object ComposeBlank : DocDistEvent
    data class DistributeDocument(val documentId: String) : DocDistEvent
    data class DistributeFolder(val folderId: String) : DocDistEvent
    /** A blank send to one address — the address book's "Send email". */
    data class ComposeTo(val email: String) : DocDistEvent
    /** A blank send addressed to a list — "Share some documents". */
    data class ComposeWithList(val listId: String) : DocDistEvent
    data object CloseComposer : DocDistEvent
    data class ComposeStage(val stage: ComposerStage) : DocDistEvent
    data class ComposeSubject(val text: String) : DocDistEvent
    data class ComposeBody(val text: String) : DocDistEvent
    data class ComposeAddresses(val field: AddressField, val tokens: List<String>, val input: String) : DocDistEvent
    data class ShowCcBcc(val shown: Boolean) : DocDistEvent
    data class ComposeListMenu(val open: Boolean) : DocDistEvent
    data class ComposeTemplateMenu(val open: Boolean) : DocDistEvent
    data class ComposeSignatureMenu(val open: Boolean) : DocDistEvent
    data class AddList(val listId: String) : DocDistEvent
    data class ApplyTemplate(val templateId: String) : DocDistEvent
    data class InsertSignature(val signatureId: String) : DocDistEvent
    data object SaveCurrentAsTemplate : DocDistEvent
    data object OpenListEditor : DocDistEvent
    data class ToggleWatermark(val documentId: String) : DocDistEvent
    data object OpenWatermarkWizard : DocDistEvent
    data class EditWizard(val style: WatermarkStyle) : DocDistEvent
    data object SaveWizard : DocDistEvent
    data object CloseWizard : DocDistEvent
    data class OpenWatermarkPreview(val documentId: String?) : DocDistEvent
    data class RemoveAttachment(val documentId: String) : DocDistEvent
    data class MoveAttachment(val documentId: String, val delta: Int) : DocDistEvent
    data object PickAndAttach : DocDistEvent
    data class AttachDroppedFiles(val files: List<LocalFile>) : DocDistEvent
    data class ToggleOversizeFile(val index: Int) : DocDistEvent
    data object ConfirmOversize : DocDistEvent
    data object CancelOversize : DocDistEvent
    data object Send : DocDistEvent

    // -- history ----------------------------------------------------------

    data class SearchHistory(val text: String) : DocDistEvent
    data class ToggleHistorySender(val senderId: String) : DocDistEvent
    data object ClearHistorySenders : DocDistEvent
    data class SearchHistorySenders(val text: String) : DocDistEvent
    data class HistorySenderMenu(val open: Boolean) : DocDistEvent
    data object LoadMoreHistory : DocDistEvent
    /** Opens one send's detail (null closes it) and refreshes its per-recipient open status. */
    data class ExpandDistribution(val distributionId: String?) : DocDistEvent
    data object RefreshDistribution : DocDistEvent
    /** Re-opens the composer with a past send's subject, body and attachments. */
    data class DuplicateDistribution(val distributionId: String) : DocDistEvent
    data class OpenSaveRecipientsAsList(val open: Boolean) : DocDistEvent
    data class EditSaveListName(val text: String) : DocDistEvent
    data object ConfirmSaveRecipientsAsList : DocDistEvent
    data object ExportRecipientsCsv : DocDistEvent

    // -- lists ------------------------------------------------------------

    data class SearchLists(val text: String) : DocDistEvent
    data class NewListRow(val open: Boolean) : DocDistEvent
    data class EditNewListName(val text: String) : DocDistEvent
    data object CreateListInline : DocDistEvent
    data class OpenList(val listId: String) : DocDistEvent
    data object CloseList : DocDistEvent
    data class EditListName(val text: String) : DocDistEvent
    data class EditListRecipientInput(val email: String, val name: String, val job: String) : DocDistEvent
    data class PickListContact(val email: String) : DocDistEvent
    data object AddListRecipient : DocDistEvent
    data class RemoveListRecipient(val email: String) : DocDistEvent
    data object SaveList : DocDistEvent
    data class ConfirmRemoveList(val listId: String) : DocDistEvent
    data class DeleteList(val listId: String) : DocDistEvent
    data class ExportList(val listId: String) : DocDistEvent
    data object DownloadCsvTemplate : DocDistEvent
    data object PickCsv : DocDistEvent
    data object CancelCsv : DocDistEvent
    data object ConfirmCsv : DocDistEvent

    // composer's inline list editor
    data object CloseListEditor : DocDistEvent
    data class EditListEditor(val name: String? = null, val description: String? = null) : DocDistEvent
    data class EditListEditorInput(val email: String, val name: String, val job: String) : DocDistEvent
    data class PickListEditorContact(val email: String) : DocDistEvent
    data object AddListEditorRecipient : DocDistEvent
    data class RemoveListEditorRecipient(val email: String) : DocDistEvent
    data object PickCsvForListEditor : DocDistEvent
    data object SaveListEditor : DocDistEvent

    // -- address book -----------------------------------------------------

    data class SearchContacts(val text: String) : DocDistEvent
    data class SelectContact(val email: String) : DocDistEvent
    data object OpenAddContact : DocDistEvent
    data object OpenEditContact : DocDistEvent
    data class EditContact(
        val name: String? = null,
        val email: String? = null,
        val job: String? = null,
        val listIds: List<String>? = null,
    ) : DocDistEvent
    /** The email field lost focus: its error may show from now on. */
    data object TouchContactEmail : DocDistEvent
    data object CloseContactEditor : DocDistEvent
    data object SaveContactEditor : DocDistEvent
    data class ConfirmDeleteContact(val email: String) : DocDistEvent
    data class DeleteContact(val email: String) : DocDistEvent
    data class SaveContact(val contact: Contact) : DocDistEvent
    data class AddContactToList(val listId: String) : DocDistEvent
    data class RemoveContactFromList(val listId: String) : DocDistEvent
    data object CopyContactEmail : DocDistEvent
    data object ExportContactsCsv : DocDistEvent
    data class ViewEmail(val distributionId: String?) : DocDistEvent

    // -- templates --------------------------------------------------------

    data object OpenNewTemplate : DocDistEvent
    data class OpenEditTemplate(val templateId: String) : DocDistEvent
    data class EditTemplate(
        val name: String? = null,
        val description: String? = null,
        val subject: String? = null,
        val body: String? = null,
    ) : DocDistEvent
    data object CloseTemplateEditor : DocDistEvent
    data object SaveTemplateEditor : DocDistEvent
    data class ConfirmDeleteTemplate(val templateId: String) : DocDistEvent
    data class DeleteTemplate(val templateId: String) : DocDistEvent
}

/** The three address rows of the composer. */
enum class AddressField { To, Cc, Bcc }

/** One-shot things the window does rather than renders. */
sealed interface DocDistEffect {
    data class Failed(val message: String) : DocDistEffect

    /** Hand a URL to the OS — a preview, or a download the browser handles. */
    data class OpenUrl(val url: String) : DocDistEffect
}

/** Turns typed text into recipients, splitting on the separators people paste. */
internal fun parseRecipients(text: String): List<Recipient> = parseAddressList(text)
