package com.zillit.desktop.feature.purchaseorder.ui

import com.zillit.desktop.feature.purchaseorder.domain.PoAddress
import com.zillit.desktop.feature.purchaseorder.domain.PoAssignmentRule
import com.zillit.desktop.feature.purchaseorder.domain.PoAttachment
import com.zillit.desktop.feature.purchaseorder.domain.PoLine
import com.zillit.desktop.feature.purchaseorder.domain.PoQuickFilter
import com.zillit.desktop.feature.purchaseorder.domain.PoSettings
import com.zillit.desktop.feature.purchaseorder.domain.PoSortColumn
import com.zillit.desktop.feature.purchaseorder.domain.PoSortKey

sealed interface PoPrompt {
    data class Confirm(
        val action: PoConfirmAction,
        val targetId: String,
        val title: String,
        val message: String,
        /** Set when the confirm is about an amendment, which re-opens the approval chain. */
        val destructive: Boolean = false,
    ) : PoPrompt

    data class WithReason(
        val action: PoReasonAction,
        val targetId: String,
        val title: String,
        val label: String,
        val reason: String = "",
    ) : PoPrompt
}

enum class PoConfirmAction {
    Approve,
    Post,
    Delete,
    DeleteTemplate,
    RemoveRule,

    /** Editing a fully-approved order discards its approvals — asked first. */
    AmendOrder,
}

enum class PoReasonAction {
    Reject,

    /**
     * Naming a template saved off an order.
     *
     * A prompt rather than the order's description: the web asks, and a
     * template called "Camera package — 12 weeks" is one nobody finds again
     * next month.
     */
    NameTemplate,
}

sealed interface PoEvent {
    data object Refresh : PoEvent
    data class Open(val destination: PoDestination) : PoEvent

    /** The Queue tab's two halves — My Queue and All Queue. */
    data class OpenQueue(val scope: PoQueueScope) : PoEvent
    data class Search(val query: String) : PoEvent
    data class Filter(val filter: PoQuickFilter) : PoEvent
    data class FilterDepartment(val departmentId: String?) : PoEvent
    data class Sort(val key: PoSortKey) : PoEvent

    /** A clicked column header: same column again flips the direction. */
    data class SortColumn(val column: PoSortColumn) : PoEvent
    data class ToggleSelection(val id: String) : PoEvent
    data class SelectAll(val ids: List<String>) : PoEvent
    data object ClearSelection : PoEvent
    data object ClearNotice : PoEvent

    // -- the detail dialog ----------------------------------------------------

    /** Opens one order, re-read so its stamps and lines are current. */
    data class OpenOrder(val id: String) : PoEvent
    data object CloseOrder : PoEvent

    /** Opens one of the order's files through the host's store. */
    data class OpenAttachment(val attachment: PoAttachment) : PoEvent

    /** Renders the order's PDF and hands it to the OS. */
    data class ViewPdf(val id: String) : PoEvent

    /** Emails the order to its vendor. [allowResend] only on the processing page. */
    data class SendVendorEmail(val id: String, val allowResend: Boolean = false) : PoEvent

    // -- prompts --------------------------------------------------------------

    data class Ask(val prompt: PoPrompt) : PoEvent
    data class UpdatePrompt(val prompt: PoPrompt) : PoEvent
    data object DismissPrompt : PoEvent
    data object ConfirmPrompt : PoEvent

    // -- the form -------------------------------------------------------------

    /** Opens the form empty — the strip's Create PO / Enter PO button. */
    data object CreateOrder : PoEvent

    /** Opens the form on an existing order, asking first when it is an amendment. */
    data class EditOrder(val id: String) : PoEvent

    /** Resumes a draft from the PO Drafts tab. */
    data class ResumeDraft(val id: String) : PoEvent

    /** Opens the form pre-filled from a template — the Templates tab's Use. */
    data class UseTemplate(val id: String) : PoEvent

    /** Opens the template editor. */
    data class EditTemplate(val id: String) : PoEvent
    data object CreateTemplate : PoEvent

    data class EditForm(val form: PoFormState) : PoEvent
    data object AddLine : PoEvent
    data class RemoveLine(val index: Int) : PoEvent

    /** Halves a line into two children — the web's "Split Line". */
    data class SplitLine(val index: Int) : PoEvent

    /** Divides a rental line across its own window — the web's "Split by Period". */
    data class SplitLineByPeriod(val index: Int) : PoEvent
    data object AttachFile : PoEvent
    data class RemoveAttachment(val attachment: PoAttachment) : PoEvent
    data object CloseForm : PoEvent

    /** Raises or updates the order. */
    data object SubmitForm : PoEvent
    data object SaveDraft : PoEvent

    /** Saves the form as a template; a blank name is refused. */
    data class SaveAsTemplate(val name: String) : PoEvent

    /** Asks what to call the template first — the web's own name prompt. */
    data object NameTemplate : PoEvent

    // -- the PO Entry page ----------------------------------------------------

    /** Opens the processing page on an order, loading it fresh. */
    data class ProcessOrder(val id: String) : PoEvent
    data class EditEntry(val entry: PoEntryState) : PoEvent
    data object AddEntryLine : PoEvent
    data class RemoveEntryLine(val index: Int) : PoEvent
    data object SaveEntry : PoEvent
    data object PostEntry : PoEvent
    data object CloseEntry : PoEvent

    // -- the dialogs ----------------------------------------------------------

    data class AskReassign(val orderId: String) : PoEvent
    data class AskBulkReassign(val ids: List<String>) : PoEvent
    data class EditReassign(val reassign: PoReassignState) : PoEvent
    data object ConfirmReassign : PoEvent
    data object DismissReassign : PoEvent

    data class AskClose(val orderId: String) : PoEvent
    data class EditClose(val close: PoCloseState) : PoEvent
    data object ConfirmClose : PoEvent
    data object DismissClose : PoEvent

    data class AskCloseOff(val period: String, val ids: List<String>) : PoEvent
    data class EditCloseOff(val closeOff: PoCloseOffState) : PoEvent
    data object ConfirmCloseOff : PoEvent
    data object DismissCloseOff : PoEvent

    data class AskBulkDate(val ids: List<String>) : PoEvent
    data class EditBulkDate(val bulk: PoBulkDateState) : PoEvent
    data object ConfirmBulkDate : PoEvent
    data object DismissBulkDate : PoEvent

    // -- the Delivery Addresses tab -------------------------------------------

    data object AddAddress : PoEvent
    data class EditAddressRow(val id: String) : PoEvent
    data class EditAddress(val address: PoAddress) : PoEvent
    data object SaveAddress : PoEvent
    data object DismissAddress : PoEvent

    /** Fills the form's delivery block from a saved address. */
    data class PickSavedAddress(val id: String?) : PoEvent

    data class DeleteTemplate(val id: String) : PoEvent

    // -- hand-offs ------------------------------------------------------------

    /** The department view's Vendors and Invoices tabs, which are other surfaces. */
    data object OpenVendors : PoEvent
    data object OpenInvoices : PoEvent

    // -- the Settings tab -----------------------------------------------------

    /** The edited copy of the document; nothing reaches the server until a card's Save. */
    data class EditSettings(val settings: PoSettings) : PoEvent

    data class SaveSettings(val section: PoSettingsSection) : PoEvent

    /** Picks, uploads and saves the terms document in one go — "Saves on upload". */
    data object PickTermsDocument : PoEvent

    data object OpenTermsDocument : PoEvent

    /** The Form Configuration card — the editor lives in the Account Hub. */
    data object OpenFormConfiguration : PoEvent

    data object AddRule : PoEvent

    data class EditRule(val rule: PoAssignmentRule) : PoEvent

    /** Asks first when the server holds the rule; a rule never saved just goes. */
    data class RemoveRule(val id: String) : PoEvent
}

sealed interface PoEffect {
    data class Failed(val message: String) : PoEffect

    /** The host fetches the file from storage and hands it to the OS. */
    data class OpenAttachment(val attachment: PoAttachment) : PoEffect

    /** The Forms Configuration editor for purchase orders — the Account Hub's screen, the host's route. */
    data object OpenFormConfig : PoEffect

    /** The account hub's Vendors area — the department view's Vendors tab. */
    data object OpenVendors : PoEffect

    /** The Invoices tool — the department view's Invoices tab. */
    data object OpenInvoices : PoEffect
}
