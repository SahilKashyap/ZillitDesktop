package com.zillit.desktop.feature.documentdistribution.ui

import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.DistributionList
import com.zillit.desktop.feature.documentdistribution.domain.EmailTemplate
import com.zillit.desktop.feature.documentdistribution.domain.LibrarySort
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.domain.WatermarkStyle

/** Everything the user can do in this tool. */
sealed interface DocDistEvent {

    data object Refresh : DocDistEvent
    data object ClearNotice : DocDistEvent
    data object DismissPrompt : DocDistEvent
    data object ConfirmPrompt : DocDistEvent

    data class Open(val destination: DocDistDestination) : DocDistEvent

    // -- library ----------------------------------------------------------

    /** Null navigates to the library root. */
    data class OpenFolder(val folderId: String?) : DocDistEvent
    data class CreateFolder(val name: String, val parentId: String?) : DocDistEvent
    data class RenameFolder(val folderId: String, val name: String) : DocDistEvent
    data class DeleteFolder(val folderId: String) : DocDistEvent

    data class Search(val text: String) : DocDistEvent
    data class FilterByDate(val isoDate: String?) : DocDistEvent
    data class SortBy(val sort: LibrarySort) : DocDistEvent
    data object LoadMore : DocDistEvent

    data class ToggleDocument(val documentId: String) : DocDistEvent
    data object ClearSelection : DocDistEvent
    /** Selects or clears every document currently loaded. */
    data class SelectAll(val selected: Boolean) : DocDistEvent

    data class DeleteDocument(val documentId: String) : DocDistEvent
    data class MoveSelection(val folderId: String?) : DocDistEvent
    data class OpenDocument(val documentId: String) : DocDistEvent
    data class DownloadDocument(val documentId: String) : DocDistEvent

    // -- composer ---------------------------------------------------------

    /** Opens the composer pre-loaded with whatever is selected. */
    data object Compose : DocDistEvent
    data object CloseComposer : DocDistEvent
    data class ComposeSubject(val text: String) : DocDistEvent
    data class ComposeBody(val html: String) : DocDistEvent
    data class ComposeRecipientDraft(val text: String) : DocDistEvent
    /** Commits the draft text in the recipient field as one or more chips. */
    data object CommitRecipientDraft : DocDistEvent
    data class RemoveRecipient(val email: String) : DocDistEvent
    data class AddList(val listId: String) : DocDistEvent
    data class ApplyTemplate(val templateId: String) : DocDistEvent
    data class ToggleWatermark(val documentId: String) : DocDistEvent
    data class SetWatermark(val style: WatermarkStyle) : DocDistEvent
    data class RemoveAttachment(val documentId: String) : DocDistEvent
    data object Send : DocDistEvent

    // -- history ----------------------------------------------------------

    data class SearchHistory(val text: String) : DocDistEvent
    /** Expands one row and refreshes its per-recipient open status. */
    data class ExpandDistribution(val distributionId: String?) : DocDistEvent
    /** Re-opens the composer with a past send's recipients and subject. */
    data class DuplicateDistribution(val distributionId: String) : DocDistEvent

    // -- lists, contacts, templates ---------------------------------------

    data class SaveList(val list: DistributionList) : DocDistEvent
    data class DeleteList(val listId: String) : DocDistEvent
    data class SaveContact(val contact: Contact) : DocDistEvent
    data class DeleteContact(val email: String) : DocDistEvent
    data class SaveTemplate(val template: EmailTemplate) : DocDistEvent
    data class DeleteTemplate(val templateId: String) : DocDistEvent
}

/** One-shot things the window does rather than renders. */
sealed interface DocDistEffect {
    data class Failed(val message: String) : DocDistEffect

    /** Hand a URL to the OS — a preview, or a download the browser handles. */
    data class OpenUrl(val url: String) : DocDistEffect
}

/** Turns typed text into recipients, splitting on the separators people paste. */
internal fun parseRecipients(text: String): List<Recipient> =
    text.split(',', ';', '\n', ' ')
        .map { it.trim().trim('<', '>') }
        .filter { it.isNotEmpty() }
        .map { Recipient(email = it) }
