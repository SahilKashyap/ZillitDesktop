package com.zillit.desktop.feature.dealmemo.ui

import androidx.compose.ui.geometry.Offset
import com.zillit.desktop.feature.dealmemo.domain.preview.ChipAction
import com.zillit.desktop.feature.dealmemo.domain.preview.CrewField
import com.zillit.desktop.feature.dealmemo.domain.preview.DealAttachment
import com.zillit.desktop.feature.dealmemo.domain.preview.EditAction
import com.zillit.desktop.feature.dealmemo.domain.preview.NominalRow
import com.zillit.desktop.feature.dealmemo.domain.rules.BulkRuleRow
import com.zillit.desktop.feature.dealmemo.ui.documents.DealPlacement
import com.zillit.desktop.feature.dealmemo.ui.documents.SignatureFont
import com.zillit.desktop.feature.dealmemo.ui.preview.NewSignatureStep
import kotlinx.serialization.json.JsonElement

/** Everything the deal page can be asked to do. */
sealed interface PreviewEvent : DealMemoEvent {
    /** The header's back arrow. */
    data object Back : PreviewEvent
    data object Retry : PreviewEvent

    data object ToggleEditMenu : PreviewEvent
    data object CloseEditMenu : PreviewEvent
    data class Edit(val action: EditAction) : PreviewEvent

    data object Activate : PreviewEvent
    data object ApproveAndSign : PreviewEvent
    data object CopyShareLink : PreviewEvent
    data object Acknowledge : PreviewEvent

    data object ToggleMore : PreviewEvent
    data object CloseMore : PreviewEvent
    data object ShowChecklist : PreviewEvent
    data object CloseChecklist : PreviewEvent
    data object ViewPdf : PreviewEvent
    data object History : PreviewEvent

    data class Chip(val action: ChipAction) : PreviewEvent
    data object CloseGate : PreviewEvent

    data object OpenReject : PreviewEvent
    data class RejectReason(val reason: String) : PreviewEvent
    data object ConfirmReject : PreviewEvent
    data object CancelReject : PreviewEvent

    data object SendForApproval : PreviewEvent

    data class ViewPassport(val attachment: DealAttachment) : PreviewEvent
    data object CloseViewer : PreviewEvent
    data object DownloadViewer : PreviewEvent
    data object CloseStartForm : PreviewEvent

    /** "Complete your details", from the strip or the gate. */
    data object CompleteDetails : PreviewEvent
}

/** The per-document signer. */
sealed interface SignerEvent : DealMemoEvent {
    data class SelectSaved(val id: String) : SignerEvent
    data class AskDeleteSaved(val id: String) : SignerEvent
    data object ConfirmDeleteSaved : SignerEvent
    data object CancelDeleteSaved : SignerEvent
    data object NewSignature : SignerEvent
    data class Method(val step: NewSignatureStep) : SignerEvent
    data object StepBack : SignerEvent
    data class TypedText(val text: String) : SignerEvent
    data class TypedFont(val font: SignatureFont) : SignerEvent
    data object ToggleSaveForNextTime : SignerEvent
    data object UseTyped : SignerEvent
    data class UseDrawn(val strokes: List<List<Offset>>, val width: Int, val height: Int) : SignerEvent
    data object ContinueToSigning : SignerEvent

    data object ChangeSignature : SignerEvent
    data class Zoom(val delta: Float) : SignerEvent
    data object Retry : SignerEvent
    data class AddAnother(val placement: DealPlacement) : SignerEvent
    data class RemoveStamp(val index: Int) : SignerEvent
    data class Sign(val placement: DealPlacement) : SignerEvent
    data object Cancel : SignerEvent
}

/** "Update Nominals". */
sealed interface NominalsEvent : DealMemoEvent {
    data class Code(val row: NominalRow, val code: String) : NominalsEvent
    data object Save : NominalsEvent
    data object Cancel : NominalsEvent
    data object SaveAndLeave : NominalsEvent
    data object Discard : NominalsEvent
    data object StayEditing : NominalsEvent
}

/** The full-page rules grid. */
sealed interface RulesEvent : DealMemoEvent {
    data class Patch(val uid: String, val row: BulkRuleRow) : RulesEvent
    data class Remove(val uid: String) : RulesEvent
    data class Add(val count: Int) : RulesEvent
    data object Import : RulesEvent

    /** "Import union rules": pick an agreement and append its rules. */
    data object ImportAgreement : RulesEvent
    data object Save : RulesEvent
    data object Close : RulesEvent
}

/** "Complete your details". */
sealed interface CrewFormEvent : DealMemoEvent {
    /** A `crew_details` key. */
    data class Crew(val key: String, val value: JsonElement) : CrewFormEvent

    /**
     * A key of a nested `crew_details` section — `emergency_details`, `representative_details`, `loan_out_company`,
     * `uk`.
     */
    data class Section(val section: String, val key: String, val value: JsonElement) : CrewFormEvent

    /** A `bank` key. */
    data class Bank(val key: String, val value: JsonElement) : CrewFormEvent

    /** The emergency name and number are written flat and nested together. */
    data class EmergencyName(val name: String) : CrewFormEvent
    data class EmergencyNumber(val number: String) : CrewFormEvent

    /** Several UK keys in one change — a route switch clears the other side at once. */
    data class UkPatch(val patch: Map<String, JsonElement>) : CrewFormEvent

    data object AddPassport : CrewFormEvent
    data class RemovePassport(val index: Int) : CrewFormEvent

    data class Touch(val field: CrewField) : CrewFormEvent
    data class GoToStep(val index: Int) : CrewFormEvent
    data object Continue : CrewFormEvent
    data object StepBack : CrewFormEvent

    /** The topbar Save — only while dirty. */
    data object Save : CrewFormEvent

    /** Save & Finish on the last step — saves even an unchanged draft. */
    data object Finish : CrewFormEvent

    /** Back, the breadcrumb or Escape: a question when there are unsaved changes. */
    data object Close : CrewFormEvent
    data object KeepEditing : CrewFormEvent
    data object DiscardChanges : CrewFormEvent
    data object SaveChanges : CrewFormEvent
}
