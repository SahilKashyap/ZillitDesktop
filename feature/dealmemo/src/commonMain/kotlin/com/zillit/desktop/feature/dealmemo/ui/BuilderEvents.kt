package com.zillit.desktop.feature.dealmemo.ui

import com.zillit.desktop.feature.dealmemo.domain.authoring.DealForm
import com.zillit.desktop.feature.dealmemo.domain.rules.DayTypeRow
import kotlinx.serialization.json.JsonElement

/** What the one-page builder can be asked to do. */
sealed interface BuilderEvent : DealMemoEvent {

    // -- the form ------------------------------------------------------------------------

    /** A user edit of one field — `set(k, v)`. */
    data class SetField(val key: String, val value: JsonElement) : BuilderEvent

    /** A user edit that writes several fields at once. */
    data class Patch(val values: Map<String, JsonElement>) : BuilderEvent

    /** A user edit computed from the form as it stands — `setForm(updater)`, for list rows. */
    data class Edit(val transform: (DealForm) -> DealForm) : BuilderEvent

    /** A default the page fills in itself — `setAuto`: never marks the page dirty. */
    data class Auto(val values: Map<String, JsonElement>) : BuilderEvent

    /** A computed default, e.g. the rate card's published rate — the raw `setForm`. */
    data class AutoEdit(val transform: (DealForm) -> DealForm) : BuilderEvent

    /** Rates' Reset: the rule fields back to what was last loaded or saved. */
    data object ResetRules : BuilderEvent

    /** "Reset pay rules" asks first; this opens or dismisses the question. */
    data class ConfirmResetRules(val open: Boolean) : BuilderEvent

    data object RefetchRate : BuilderEvent

    /** The agreed day rate; the weekly rate follows it. */
    data class DayRate(val value: JsonElement) : BuilderEvent

    /** The agreed weekly rate; the day rate follows it. */
    data class WeeklyRate(val value: JsonElement) : BuilderEvent

    /** "Reset to scale": the published figures back in, and the rate card asked again. */
    data object ResetToScale : BuilderEvent

    /** "Edit rules": the deal's pay rules in the full-page grid. */
    data object OpenRules : BuilderEvent

    // -- sections ------------------------------------------------------------------------

    /** The Edit / Done chip; one editor is open at a time. */
    data class ToggleEdit(val sectionId: Int) : BuilderEvent

    /** Show / Hide on a collapsible section. */
    data class ToggleCollapse(val sectionId: Int) : BuilderEvent

    /** The page scrolled to the section it was asked to. */
    data object ScrollDone : BuilderEvent

    // -- the top bar -----------------------------------------------------------------------

    /** Back, or the `Deal Memos` crumb — the leave guard when something is unsaved. */
    data object Back : BuilderEvent

    data object Save : BuilderEvent

    data object Issue : BuilderEvent

    /** Save Setup / Update Setup. */
    data object SaveSetup : BuilderEvent

    // -- dialogs ---------------------------------------------------------------------------

    data object CloseValidation : BuilderEvent

    data object ConfirmIssuePreview : BuilderEvent

    data object CloseIssuePreview : BuilderEvent

    /** "Nominal codes not added" → Now: open Nominal Coding. */
    data object NominalsNow : BuilderEvent

    /** → Later: issue without them. */
    data object NominalsLater : BuilderEvent

    data object CloseNominalPrompt : BuilderEvent

    data class EditName(val name: String) : BuilderEvent

    data object ConfirmName : BuilderEvent

    data object CancelName : BuilderEvent

    data class EditSetupName(val name: String) : BuilderEvent

    data object ConfirmSetupName : BuilderEvent

    data object CloseSetupName : BuilderEvent

    data object KeepEditing : BuilderEvent

    /** The leave guard's primary: Save as Draft, Save Changes, Save Setup or Update Setup. */
    data object LeaveAndSave : BuilderEvent

    data object LeaveWithoutSaving : BuilderEvent

    // -- the setup picker (a deal from the Create menu) -------------------------------------

    /** Picking a setup reloads the deal from it; clearing only forgets which one it was. */
    data class PickSetup(val templateId: String?) : BuilderEvent

    data object ResetToSetup : BuilderEvent

    // -- documents ---------------------------------------------------------------------------

    /** Upload Custom Document: PDFs from disk, uploaded when the deal is saved. */
    data object PickCustomDocuments : BuilderFileEvent

    /** A Production Setup agreement document onto the deal, or back off it. */
    data class AttachAgreement(val rowId: String) : BuilderFileEvent

    data class DetachAgreement(val rowId: String) : BuilderFileEvent

    /** "Crew sign required" on a Production Setup document, attached or not. */
    data class SetAgreementSignRequired(val rowId: String, val required: Boolean) : BuilderFileEvent

    data class ViewDocument(val docId: String) : BuilderFileEvent

    data class ViewAgreement(val rowId: String) : BuilderFileEvent

    /** The long-form contract: picked and uploaded at once. */
    data object PickLongFormContract : BuilderFileEvent

    data object ViewLongFormContract : BuilderFileEvent

    data object CloseViewer : BuilderFileEvent

    data object DownloadViewer : BuilderFileEvent

    /** Passport / ID: uploaded on pick, two at most. */
    data object PickPassport : BuilderFileEvent

    data class RemovePassport(val index: Int) : BuilderFileEvent

    data class ViewPassport(val index: Int) : BuilderFileEvent

    // -- payroll bureaus (setup pages) --------------------------------------------------------

    data object AddBureau : BuilderEvent

    data class EditBureau(val id: String, val title: String? = null, val description: String? = null) : BuilderEvent

    data class RemoveBureau(val id: String) : BuilderEvent

    // -- Production Setup, written straight from a setup page ----------------------------------

    /** "+ Add company": the first-company notice on a production with none, else the form. */
    data object AddCompany : SetupPageEvent

    /** The notice's Got it opens the form; closing it opens nothing. */
    data class CloseCompanyNotice(val proceed: Boolean) : SetupPageEvent

    data class EditCompany(val values: Map<String, JsonElement>) : SetupPageEvent

    data object SaveCompany : SetupPageEvent

    data object CloseCompany : SetupPageEvent

    /** The project's non-union pay rules in the full-page grid. */
    data object OpenProjectRules : SetupPageEvent

    data class PickImportTerritory(val territory: String?) : SetupPageEvent

    data class PickImportAgreement(val agreementId: String?) : SetupPageEvent

    /** The picked agreement's rules appended to the grid — saved only by the grid's own Save. */
    data object ConfirmRuleImport : SetupPageEvent

    data object CloseRuleImport : SetupPageEvent

    data class EditDayType(val row: DayTypeRow) : SetupPageEvent

    data object AddDayType : SetupPageEvent

    data class RemoveDayType(val id: String) : SetupPageEvent

    data object SaveDayTypes : SetupPageEvent

    /** "+ Add Document (PDF)": files picked into the pending queue. */
    data object QueueAgreementDocuments : SetupPageEvent

    data class EditPendingDocument(val id: String, val title: String, val description: String) : SetupPageEvent

    data class RemovePendingDocument(val id: String) : SetupPageEvent

    /** "Save all": the queue uploaded and added to the project in one write. */
    data object SaveAgreementDocuments : SetupPageEvent

    data class EditAgreementDocument(val id: String, val title: String, val description: String) : SetupPageEvent

    /** A saved document's field was left: a changed title or description is written. */
    data class CommitAgreementDocument(val id: String) : SetupPageEvent

    data class DeleteAgreementDocument(val id: String) : SetupPageEvent
}

/** The builder's documents, long-form contract and passport — handled by the builder's file collaborator. */
sealed interface BuilderFileEvent : BuilderEvent

/** Production Setup written straight from a setup page — companies, pay rules, day types, agreement documents. */
sealed interface SetupPageEvent : BuilderEvent
