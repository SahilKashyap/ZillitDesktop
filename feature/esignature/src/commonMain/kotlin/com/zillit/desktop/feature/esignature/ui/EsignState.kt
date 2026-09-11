package com.zillit.desktop.feature.esignature.ui

import com.zillit.desktop.feature.esignature.domain.AuditEntry
import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.domain.EnvelopeField
import com.zillit.desktop.feature.esignature.domain.EnvelopeScope
import com.zillit.desktop.feature.esignature.domain.EsignPage
import com.zillit.desktop.feature.esignature.domain.EsignViewer
import com.zillit.desktop.feature.esignature.domain.FieldAnswer
import com.zillit.desktop.feature.esignature.domain.FieldStyle
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.domain.SavedSignature
import com.zillit.desktop.feature.esignature.domain.SignerOptionLike

/** The manager's list buckets, in the web's tab order. */
enum class ManageBucket(val wire: String, val label: String) {
    Draft("draft", "Draft"),
    Sent("sent", "Sent"),
    Completed("completed", "Completed"),
    Rejected("rejected", "Rejected"),
}

/** The receiver's list buckets. */
enum class SignBucket(val wire: String, val label: String) {
    Action("received", "Action required"),
    Completed("completed", "Completed"),
    Rejected("rejected", "Rejected"),
}

/** The two top-level surfaces (the web's Segmented control). */
enum class EsignSurface(val label: String) {
    Manage("Envelopes"),
    Sign("Sign documents"),
}

data class ManageState(
    val bucket: ManageBucket = ManageBucket.Sent,
    val rows: List<Envelope> = emptyList(),
    val loading: Boolean = false,
)

data class SignListState(
    val bucket: SignBucket = SignBucket.Action,
    val rows: List<Envelope> = emptyList(),
    val loading: Boolean = false,
)

/** An open envelope — the tracker, and the signing surface when it is mine to sign. */
data class EnvelopeDetailState(
    val envelope: Envelope,
    val pages: List<EsignPage> = emptyList(),
    val loadingPages: Boolean = true,
    val audit: List<AuditEntry> = emptyList(),
    /** Fields waiting on the current user, when they are an unsigned signer. */
    val myFields: List<EnvelopeField> = emptyList(),
    /** The signer's consent step — fields hide until they accept. */
    val consented: Boolean = false,
    /** True while the acceptance is being recorded; the gate stays up. */
    val consenting: Boolean = false,
    /** Set when the sender is cancelling this envelope, before they confirm. */
    val voiding: Boolean = false,
    val voidReason: String = "",
    /** Answers by field id; unanswered marks fall back to the saved signature. */
    val answers: Map<String, FieldAnswer> = emptyMap(),
    val signing: Boolean = false,
    val declining: Boolean = false,
    val declineReason: String = "",
    val notPdf: Boolean = false,
    /** Whether the viewer is the one who sent it. */
    val sentByMe: Boolean = false,
) {
    val canSignNow: Boolean get() = myFields.isNotEmpty() && consented && !signing

    /**
     * Whether this viewer may cancel the envelope.
     *
     * The sender's act, on an envelope that is out and unfinished. A recipient
     * declines instead — that is their side of the same decision.
     */
    val canVoid: Boolean get() = sentByMe && envelope.status.isCancellable
}

/** The compose flow: envelope details, then coordinate placement. */
data class ComposeState(
    val fileName: String = "",
    val fileBytes: ByteArray? = null,
    val title: String = "",
    val description: String = "",
    val pages: List<EsignPage> = emptyList(),
    val options: List<SignerOptionLike> = emptyList(),
    val chosen: List<String> = emptyList(),
    val placing: Boolean = false,
    val activeSigner: String? = null,
    val activeType: FieldType = FieldType.SignHere,
    /** Placed fields by chosen-signer user id. */
    val placed: Map<String, List<PlacedField>> = emptyMap(),
    /**
     * Sender-supplied addresses for signers whose crew row carries none —
     * the service refuses a recipient without one. Keyed by user id.
     */
    val emailOverrides: Map<String, String> = emptyMap(),
    /** Text styling applied to typed fields placed from now on. */
    val activeStyle: FieldStyle = FieldStyle(),
    val initialsOnAllPages: Boolean = false,
    /** Days between reminders; null = the server's default. */
    val reminderCadenceDays: Int? = null,
    val sending: Boolean = false,
) {
    val placedCount: Int get() = placed.values.sumOf { it.size }

    val everySignerCovered: Boolean get() =
        chosen.isNotEmpty() && chosen.all { !placed[it].isNullOrEmpty() }

    fun emailFor(userId: String): String {
        val known = options.firstOrNull { it.userId == userId }?.email.orEmpty()
        return known.ifBlank { emailOverrides[userId].orEmpty() }
    }

    /** The chosen signers still missing an address the service will accept. */
    val missingEmails: List<String> get() = chosen.filter { emailFor(it).isBlank() }

    private fun projection(): List<Any?> = listOf(
        fileName, title, description, pages, options, chosen, placing,
        activeSigner, activeType, placed, emailOverrides, sending, fileBytes?.size,
    )

    override fun equals(other: Any?): Boolean =
        other is ComposeState && other.projection() == projection()

    override fun hashCode(): Int = projection().hashCode()
}

/** A compose-time field, in page points (top-left origin). */
data class PlacedField(
    val type: FieldType,
    val page: Int,
    val x: Double,
    val y: Double,
    val style: FieldStyle = FieldStyle(),
)

/** The saved-marks drawer state (list + the drawing dialog). */
data class MarksState(
    val items: List<SavedSignature> = emptyList(),
    val images: Map<String, ByteArray> = emptyMap(),
    val loading: Boolean = false,
    val drawing: DrawMarkState? = null,
)

data class DrawMarkState(
    val isSignature: Boolean = true,
    val strokes: List<List<Pair<Float, Float>>> = emptyList(),
    val saving: Boolean = false,
)

data class EsignUiState(
    val viewer: EsignViewer = EsignViewer(),
    val currentUserId: String = "",
    val surface: EsignSurface = EsignSurface.Sign,
    val manage: ManageState = ManageState(),
    val signList: SignListState = SignListState(),
    val detail: EnvelopeDetailState? = null,
    val compose: ComposeState? = null,
    val marks: MarksState = MarksState(),
    val showMarks: Boolean = false,
)

sealed interface EsignEvent {
    data class SwitchSurface(val surface: EsignSurface) : EsignEvent
    data object Refresh : EsignEvent
    data class SwitchManageBucket(val bucket: ManageBucket) : EsignEvent
    data class SwitchSignBucket(val bucket: SignBucket) : EsignEvent

    data class OpenEnvelope(val envelope: Envelope) : EsignEvent
    data object CloseDetail : EsignEvent
    data object Consent : EsignEvent

    /** Cancelling an envelope that has already gone out. */
    data object StartVoid : EsignEvent
    data class EditVoidReason(val reason: String) : EsignEvent
    data object CancelVoid : EsignEvent
    data object ConfirmVoid : EsignEvent
    data class Answer(val fieldId: String, val answer: FieldAnswer) : EsignEvent
    data object SignEnvelope : EsignEvent
    data class EditDeclineReason(val reason: String) : EsignEvent
    data object StartDecline : EsignEvent
    data object CancelDecline : EsignEvent
    data object ConfirmDecline : EsignEvent
    data class DeleteDraft(val envelopeId: String) : EsignEvent
    data class Remind(val envelopeId: String, val recipientId: String?) : EsignEvent

    data object StartCompose : EsignEvent
    data class EditCompose(val state: ComposeState) : EsignEvent
    data object BeginPlacement : EsignEvent
    data class PlaceField(val page: Int, val xPx: Float, val yPx: Float) : EsignEvent
    data class RemovePlaced(val signer: String, val index: Int) : EsignEvent
    data object SubmitCompose : EsignEvent
    data object CancelCompose : EsignEvent

    data object ToggleMarks : EsignEvent
    data class StartDrawMark(val isSignature: Boolean) : EsignEvent
    data class AddMarkStroke(val stroke: List<Pair<Float, Float>>) : EsignEvent
    data object ClearMarkStrokes : EsignEvent
    data object SaveMark : EsignEvent
    data object CancelDrawMark : EsignEvent
    data class DeleteMark(val id: String) : EsignEvent

    data class FilePicked(val name: String, val bytes: ByteArray) : EsignEvent {
        override fun equals(other: Any?): Boolean = other is FilePicked &&
            other.name == name && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = name.hashCode() * 31 + bytes.size
    }
}

sealed interface EsignEffect {
    data object PickPdf : EsignEffect
    data class Notice(val message: String) : EsignEffect
    data class Failed(val message: String) : EsignEffect
}
