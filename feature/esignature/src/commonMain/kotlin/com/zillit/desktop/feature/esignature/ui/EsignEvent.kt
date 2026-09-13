package com.zillit.desktop.feature.esignature.ui

import com.zillit.desktop.feature.esignature.domain.BulkJob
import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.domain.EnvelopeField
import com.zillit.desktop.feature.esignature.domain.EnvelopeRecipient
import com.zillit.desktop.feature.esignature.domain.EnvelopeSettings
import com.zillit.desktop.feature.esignature.domain.EnvelopeTemplate
import com.zillit.desktop.feature.esignature.domain.FieldAnswer
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.domain.SignatureFont

/** Everything the user can do, one event per act. */
sealed interface EsignEvent {

    // ------------------------------------------------------------ navigation
    data class SwitchSurface(val surface: EsignSurface) : EsignEvent
    data object Refresh : EsignEvent
    data class SetLayout(val layout: ListLayout) : EsignEvent
    data object Back : EsignEvent

    // ------------------------------------------------------------ manage list
    data class SwitchOuterTab(val tab: ManageOuterTab) : EsignEvent
    data class SwitchInnerTab(val tab: ManageInnerTab) : EsignEvent
    data class SetSentFilter(val filter: SentFilter) : EsignEvent
    data class SearchManage(val query: String) : EsignEvent
    data class AskDeleteDraft(val envelopeId: String?) : EsignEvent
    data object ConfirmDeleteDraft : EsignEvent

    // ------------------------------------------------------------ sign list
    data class SwitchSignBucket(val bucket: SignBucket) : EsignEvent
    data class SearchSign(val query: String) : EsignEvent

    // ------------------------------------------------------------ opening things
    /** A row: drafts open in the editor, everything else in the tracker. */
    data class OpenEnvelope(val envelope: Envelope) : EsignEvent
    data class OpenDetail(val envelopeId: String) : EsignEvent

    /** The signing surface: to sign, to view stamped values, or the plain document. */
    data class OpenSigning(val envelope: Envelope, val mode: SigningMode, val fromDetail: Boolean = false) : EsignEvent

    // ------------------------------------------------------------ detail
    data object RefreshDetail : EsignEvent
    data object ToggleAudit : EsignEvent
    data object ToggleOrder : EsignEvent
    data class Remind(val recipientId: String?) : EsignEvent
    data object DownloadAuditPdf : EsignEvent
    data object DownloadSigned : EsignEvent
    data object StartVoid : EsignEvent
    data class EditVoidReason(val reason: String) : EsignEvent
    data object CancelVoid : EsignEvent
    data object ConfirmVoid : EsignEvent

    // ------------------------------------------------------------ signing
    data object Consent : EsignEvent
    data class FocusField(val index: Int) : EsignEvent
    data object NextField : EsignEvent
    data object PrevField : EsignEvent
    data class Answer(val fieldId: String, val answer: FieldAnswer?) : EsignEvent
    data class SetSignOnce(val on: Boolean) : EsignEvent
    data class SetApplyToAll(val on: Boolean) : EsignEvent
    data object OpenPad : EsignEvent
    data object ClosePad : EsignEvent
    data class SetPadMode(val mode: PadMode) : EsignEvent
    data class AddPadStroke(val stroke: List<Pair<Float, Float>>) : EsignEvent
    data object ClearPad : EsignEvent
    data class EditTypedName(val name: String) : EsignEvent
    data class SetPadFont(val font: SignatureFont) : EsignEvent
    data class SetSaveForLater(val on: Boolean) : EsignEvent
    data object PickPadImage : EsignEvent
    /** Applies the pad — drawn, typed or uploaded — to the current field. */
    data object ApplyPad : EsignEvent
    /** Applies a saved mark to the current field. */
    data class UseSavedMark(val markId: String) : EsignEvent
    data object PickUploadForField : EsignEvent
    data object FinishSigning : EsignEvent
    data object StartDecline : EsignEvent
    data class EditDeclineReason(val reason: String) : EsignEvent
    data object CancelDecline : EsignEvent
    data object ConfirmDecline : EsignEvent

    // ------------------------------------------------------------ editor
    data object StartCompose : EsignEvent
    data object StartTemplate : EsignEvent
    data class UseTemplate(val template: EnvelopeTemplate) : EsignEvent
    data class EditTemplate(val template: EnvelopeTemplate) : EsignEvent
    data class EditCompose(val transform: EditorState.() -> EditorState) : EsignEvent
    data object PickDocument : EsignEvent
    data object GoToPlace : EsignEvent
    data object GoToPrepare : EsignEvent
    data class ChoosePlacementMode(val mode: PlacementMode) : EsignEvent
    data class ToggleSelfSign(val on: Boolean) : EsignEvent
    data class AddSigner(val userId: String) : EsignEvent
    data class AddCc(val userId: String) : EsignEvent
    data class RemoveRecipient(val index: Int) : EsignEvent
    data class MoveRecipient(val index: Int, val up: Boolean) : EsignEvent
    data class OpenExternal(val role: String) : EsignEvent
    data object CloseExternal : EsignEvent
    data class EditExternal(val draft: ExternalRecipientDraft) : EsignEvent
    data object AddExternal : EsignEvent
    data class EditSettings(val settings: EnvelopeSettings) : EsignEvent
    /** A click on a page, in page points. */
    data class PageClicked(val page: Int, val xPt: Double, val yPt: Double) : EsignEvent
    data class ArmType(val type: FieldType) : EsignEvent
    data class ArmSigner(val recipientIndex: Int?) : EsignEvent
    data class TogglePendingSigner(val recipientIndex: Int) : EsignEvent
    data class PlacePending(val type: FieldType) : EsignEvent
    data object CancelPending : EsignEvent
    data class SelectField(val index: Int?) : EsignEvent
    data class UpdateField(val index: Int, val transform: (EnvelopeField) -> EnvelopeField) : EsignEvent
    data class MoveField(val index: Int, val dxPt: Double, val dyPt: Double) : EsignEvent
    data class ResizeField(val index: Int, val dwPt: Double, val dhPt: Double) : EsignEvent
    data class DeleteField(val index: Int) : EsignEvent
    data class DuplicateField(val index: Int) : EsignEvent
    data class SetInitialsOnAllPages(val on: Boolean) : EsignEvent
    data class SetZoom(val zoom: Float) : EsignEvent
    data object SaveDraft : EsignEvent
    data object RequestSend : EsignEvent
    data object CancelSend : EsignEvent
    data object ConfirmSend : EsignEvent
    data object OpenSaveAsTemplate : EsignEvent
    data class EditSaveAsTemplate(val draft: SaveTemplateDraft) : EsignEvent
    data object CancelSaveAsTemplate : EsignEvent
    data object ConfirmSaveAsTemplate : EsignEvent
    data object SaveTemplate : EsignEvent
    data object CancelCompose : EsignEvent

    // ------------------------------------------------------------ marks
    data object OpenMarks : EsignEvent
    data object CloseMarks : EsignEvent
    data class SetMarksKind(val forSignature: Boolean) : EsignEvent
    data object SaveMark : EsignEvent
    data class AskDeleteMark(val id: String?) : EsignEvent
    data object ConfirmDeleteMark : EsignEvent

    // ------------------------------------------------------------ templates
    data class SearchTemplates(val query: String) : EsignEvent
    data class FilterTemplates(val category: String?) : EsignEvent
    data class SetTemplatesLayout(val layout: ListLayout) : EsignEvent
    data class ShowTemplate(val template: EnvelopeTemplate?) : EsignEvent
    data class DuplicateTemplate(val template: EnvelopeTemplate) : EsignEvent
    data class AskDeleteTemplate(val id: String?) : EsignEvent
    data object ConfirmDeleteTemplate : EsignEvent

    // ------------------------------------------------------------ bulk send
    data class StartBulkSend(val template: EnvelopeTemplate) : EsignEvent
    data object CancelBulkSend : EsignEvent
    data object PickBulkCsv : EsignEvent
    data class BulkStep(val step: Int) : EsignEvent
    data class EditBatchName(val name: String) : EsignEvent
    data object ConfirmBulkSend : EsignEvent
    data class OpenBulkJob(val job: BulkJob?) : EsignEvent
    data class RetryFailed(val jobId: String) : EsignEvent
    data class RemindOutstanding(val jobId: String) : EsignEvent

    // ------------------------------------------------------------ files back from the host
    data class FilePicked(val name: String, val bytes: ByteArray) : EsignEvent {
        override fun equals(other: Any?): Boolean = other is FilePicked &&
            other.name == name && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = name.hashCode() * 31 + bytes.size
    }
}

/** A recipient row's identity for list keys and reminders. */
internal fun EnvelopeRecipient.key(): String = id.ifBlank { email.ifBlank { userId } }
