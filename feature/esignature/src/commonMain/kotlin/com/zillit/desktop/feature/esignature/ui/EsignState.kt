package com.zillit.desktop.feature.esignature.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.esignature.domain.AuditEntry
import com.zillit.desktop.feature.esignature.domain.BulkJob
import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.domain.EnvelopeField
import com.zillit.desktop.feature.esignature.domain.EnvelopeRecipient
import com.zillit.desktop.feature.esignature.domain.EnvelopeSettings
import com.zillit.desktop.feature.esignature.domain.EnvelopeStatus
import com.zillit.desktop.feature.esignature.domain.EnvelopeTemplate
import com.zillit.desktop.feature.esignature.domain.EsignPage
import com.zillit.desktop.feature.esignature.domain.EsignViewer
import com.zillit.desktop.feature.esignature.domain.FieldAnswer
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.domain.SavedSignature
import com.zillit.desktop.feature.esignature.domain.SignatureFont
import com.zillit.desktop.feature.esignature.domain.SignerOptionLike
import com.zillit.desktop.feature.esignature.domain.EsignUnread
import com.zillit.desktop.feature.esignature.domain.StoredFile

/** The four top-level segments (the web's Segmented control). */
enum class EsignSurface(private val labelKey: String) {
    Manage(S.upload_document),
    Sign(S.docusign_segment_sign_documents),
    Templates(S.docusign_segment_templates),
    Bulk(S.docusign_segment_bulk),
    ;

    val label: String get() = str(labelKey)
}

/** Which full-page surface is up. The lists are the home; the rest stack on it. */
enum class EsignPageKind { Lists, Editor, Detail, Signing }

/** List or card, remembered across every bucket at once. */
enum class ListLayout { List, Card }

/** The manager's outer tabs. */
enum class ManageOuterTab(private val labelKey: String) {
    Active(S.desktop_ds_e_signature),
    Completed(S.completed),
    Rejected(S.rejected),
    ;

    val label: String get() = str(labelKey)
}

/** Under the Active tab. */
enum class ManageInnerTab(private val labelKey: String) {
    Sent(S.txt_sent),
    Draft(S.txt_draft),
    ;

    val label: String get() = str(labelKey)
}

/** The Sent tab's chips. */
enum class SentFilter(private val labelKey: String, private val helpKey: String) {
    All(S.filter_all, S.ds_sent_filter_all_tooltip),
    Awaiting(S.ds_sent_filter_awaiting, S.ds_sent_filter_awaiting_tooltip),
    InProgress(S.ds_sent_filter_in_progress, S.ds_sent_filter_in_progress_tooltip),
    ;

    val label: String get() = str(labelKey)
    val help: String get() = str(helpKey)
}

/** The receiver's tabs. */
enum class SignBucket(val wire: String, private val labelKey: String) {
    Action("received", S.docusign_receiver_tab_action),
    Completed("completed", S.completed),
    Rejected("rejected", S.rejected),
    ;

    val label: String get() = str(labelKey)
}

/** The manager's wire buckets — all fetched together, as the web does. */
object ManageBuckets {
    const val DRAFT = "draft"
    const val SENT = "sent"
    const val COMPLETED = "completed"
    const val REJECTED = "rejected"
    val all = listOf(DRAFT, SENT, COMPLETED, REJECTED)
}

data class ManageState(
    val outer: ManageOuterTab = ManageOuterTab.Active,
    val inner: ManageInnerTab = ManageInnerTab.Sent,
    val sentFilter: SentFilter = SentFilter.All,
    val search: String = "",
    /** Rows by wire bucket. */
    val buckets: Map<String, List<Envelope>> = emptyMap(),
    val loading: Boolean = false,
    /** A draft awaiting the user's confirmation to delete. */
    val confirmDeleteId: String? = null,
) {
    val drafts: List<Envelope> get() = buckets[ManageBuckets.DRAFT].orEmpty()

    /** Bulk-send children stay out of the Sent list — a job of 500 would drown it. */
    val sent: List<Envelope> get() = buckets[ManageBuckets.SENT].orEmpty().filter { !it.fromBulkSend }
    val hiddenBulkSent: Int get() = buckets[ManageBuckets.SENT].orEmpty().count { it.fromBulkSend }
    val completed: List<Envelope> get() = buckets[ManageBuckets.COMPLETED].orEmpty().filter { !it.fromBulkSend }
    val rejected: List<Envelope> get() = buckets[ManageBuckets.REJECTED].orEmpty().filter { !it.fromBulkSend }

    val awaiting: List<Envelope> get() = sent.filter { it.signedCount == 0 }
    val inProgress: List<Envelope> get() = sent.filter { it.signedCount in 1 until it.signers.size }

    /** The Sent tab after its chip, newest activity first. */
    val sentFiltered: List<Envelope>
        get() = when (sentFilter) {
            SentFilter.All -> sent
            SentFilter.Awaiting -> awaiting
            SentFilter.InProgress -> inProgress
        }.sortedByDescending { it.lastActivity ?: 0 }

    /** What the active tab shows, before the search narrows it. */
    val visibleRows: List<Envelope>
        get() = when (outer) {
            ManageOuterTab.Active -> if (inner == ManageInnerTab.Sent) sentFiltered else drafts
            ManageOuterTab.Completed -> completed
            ManageOuterTab.Rejected -> rejected
        }
}

data class SignListState(
    val tab: SignBucket = SignBucket.Action,
    val search: String = "",
    val buckets: Map<String, List<Envelope>> = emptyMap(),
    val loading: Boolean = false,
) {
    fun rows(tab: SignBucket): List<Envelope> = buckets[tab.wire].orEmpty()
    val visibleRows: List<Envelope> get() = rows(tab)
}

// ---------------------------------------------------------------- editor

enum class EditorStep { Prepare, Place }

/**
 * How clicks on the document drop fields — the web's setup-mode picker.
 * Manual: each click opens a popover asking who and what. Fast place: arm a
 * signer and a type first, then every click drops one.
 */
enum class PlacementMode(val wire: String, private val labelKey: String, private val helpKey: String) {
    Manual("manual", S.desktop_ds_generic_document, S.desktop_ds_no_signer_labels_on_the_page_each_click),
    FastPlace(
        "fastPlace",
        S.desktop_ds_pre_printed_signing_slots,
        S.desktop_ds_deal_memos_and_forms_with_labelled_slots_arm,
    ),
    ;

    val label: String get() = str(labelKey)
    val help: String get() = str(helpKey)

    companion object {
        fun fromWire(raw: String?): PlacementMode = entries.firstOrNull { it.wire == raw } ?: Manual
    }
}

/** A manual-mode click waiting for the sender to say whose field it is. */
data class PendingPlacement(
    val page: Int,
    val xPt: Double,
    val yPt: Double,
    val signerIndexes: Set<Int>,
)

/** An external person being added by email. */
data class ExternalRecipientDraft(
    val role: String = EnvelopeRecipient.ROLE_SIGNER,
    val name: String = "",
    val email: String = "",
) {
    val emailValid: Boolean get() = EMAIL_RE.matches(email.trim())
}

/** The Save-as-Template sheet. */
data class SaveTemplateDraft(
    val name: String = "",
    val category: String = "",
    val description: String = "",
    val saving: Boolean = false,
)

/**
 * The envelope editor — the web's `EnvelopeEditorBody`.
 *
 * One state serves three jobs: a fresh envelope, a draft reopened for
 * editing, and template authoring (no people, only role slots).
 */
data class EditorState(
    /** Set while editing a saved draft; the save is a PUT. */
    val envelopeId: String? = null,
    /** Template authoring: signers are slots, and the only save is "Save Template". */
    val isTemplate: Boolean = false,
    /** Set while editing a saved template. */
    val templateId: String? = null,
    /** Set when an envelope is being built from a template. */
    val fromTemplateName: String? = null,
    val step: EditorStep = EditorStep.Prepare,
    val document: StoredFile? = null,
    /** Picked locally and not uploaded yet — upload happens on save. */
    val pendingBytes: ByteArray? = null,
    val fileName: String = "",
    val pages: List<EsignPage> = emptyList(),
    val loadingDoc: Boolean = false,
    val title: String = "",
    val description: String = "",
    val recipients: List<EnvelopeRecipient> = emptyList(),
    val fields: List<EnvelopeField> = emptyList(),
    val settings: EnvelopeSettings = EnvelopeSettings(),
    val placementMode: PlacementMode = PlacementMode.Manual,
    /** Asked once, after the document lands, before placing. */
    val modeAsked: Boolean = false,
    /** Fast place: what the next click drops. */
    val armedType: FieldType = FieldType.SignHere,
    val armedSignerIndex: Int? = null,
    val selectedField: Int? = null,
    val pending: PendingPlacement? = null,
    val signerPickerOpen: Boolean = false,
    val ccPickerOpen: Boolean = false,
    val pickerSearch: String = "",
    val external: ExternalRecipientDraft? = null,
    val options: List<SignerOptionLike> = emptyList(),
    val saving: Boolean = false,
    val confirmSend: Boolean = false,
    val saveAsTemplate: SaveTemplateDraft? = null,
    val categories: List<String> = emptyList(),
    /** Fields the last validation flagged — drawn red until fixed. */
    val invalidFields: Set<Int> = emptySet(),
    /** Page render scale — 1.0 is the render width. */
    val zoom: Float = 1f,
    /** Who is composing — so the self-signer toggle knows its row. */
    val me: SignerOptionLike? = null,
) {
    val hasDocument: Boolean get() = document != null || pendingBytes != null
    val signers: List<EnvelopeRecipient> get() = recipients.filter { it.isSigner }
    val ccs: List<EnvelopeRecipient> get() = recipients.filter { it.isCc }
    val selfSigns: Boolean get() = me != null && signers.any { it.userId == me.userId }
    val placedCount: Int get() = fields.count { !it.autoInitial }

    /** Index into [recipients] for each signer, in signer order. */
    val signerIndexes: List<Int> get() = recipients.withIndex().filter { it.value.isSigner }.map { it.index }

    fun fieldsOf(recipientIndex: Int): List<EnvelopeField> = fields.filter { it.recipientIndex == recipientIndex }

    /** The web's `canProceedToPlace`: a document, and (outside templates) a signer. */
    val canPlace: Boolean get() = hasDocument && (isTemplate || signers.isNotEmpty())

    private fun projection(): List<Any?> = listOf(
        envelopeId, isTemplate, templateId, step, document, fileName, pages, loadingDoc, title, description,
        recipients, fields, settings, placementMode, modeAsked, armedType, armedSignerIndex, selectedField,
        pending, signerPickerOpen, ccPickerOpen, pickerSearch, external, options, saving, confirmSend,
        saveAsTemplate, categories, invalidFields, zoom, me, pendingBytes?.size,
    )

    override fun equals(other: Any?): Boolean = other is EditorState && other.projection() == projection()
    override fun hashCode(): Int = projection().hashCode()
}

// ---------------------------------------------------------------- detail

/** An open envelope's tracker — the web's `EnvelopeStatusTracker`. */
data class DetailState(
    val envelope: Envelope,
    val loading: Boolean = true,
    val audit: List<AuditEntry> = emptyList(),
    val auditLoading: Boolean = false,
    val showAudit: Boolean = true,
    val showOrder: Boolean = false,
    val refreshing: Boolean = false,
    /** When each recipient was last reminded — drives the cooldown label. */
    val remindedAt: Map<String, Long> = emptyMap(),
    val remindAllAt: Long = 0L,
    val reminding: Set<String> = emptySet(),
    val downloadingAudit: Boolean = false,
    val downloadingSigned: Boolean = false,
    /** Set when the sender is cancelling this envelope, before they confirm. */
    val voiding: Boolean = false,
    val voidReason: String = "",
    /** Whether the viewer is the one who sent it. */
    val sentByMe: Boolean = false,
    /** The viewer's own recipient row, when they are a signer here. */
    val me: EnvelopeRecipient? = null,
) {
    /** The web's `canSignNow`: I am a signer, still pending, on a live envelope. */
    val canSignNow: Boolean
        get() = me != null && !me.signed && !me.declined &&
            envelope.status in setOf(EnvelopeStatus.Sent, EnvelopeStatus.Delivered, EnvelopeStatus.Signed)

    /** Whether this viewer may cancel — the sender's act, on an envelope that is out and unfinished. */
    val canVoid: Boolean get() = sentByMe && envelope.status.isCancellable

    val hasSignedValues: Boolean get() = envelope.signedDocument != null || envelope.fields.any { it.value.isSet }
}

// ---------------------------------------------------------------- signing

/** What the signing surface is for. */
enum class SigningMode {
    /** Fields to fill — the current user is a pending signer. */
    Sign,
    /** Everyone's submitted values stamped read-only; the merged PDF once completed. */
    ViewSigned,
    /** The original document, no overlays. */
    Plain,
}

/** How the pad captures a mark: drawn, typed in a script face, or picked from what is saved. */
enum class PadMode(private val labelKey: String) {
    Saved(S.saved),
    Draw(S.docusign_create_tab_draw),
    Type(S.type),
    Upload(S.upload),
    ;

    val label: String get() = str(labelKey)
}

data class PadState(
    val mode: PadMode = PadMode.Saved,
    val strokes: List<List<Pair<Float, Float>>> = emptyList(),
    val typedName: String = "",
    val font: SignatureFont = SignatureFont.Formal,
    val uploadBytes: ByteArray? = null,
    val uploadName: String = "",
    val saveForLater: Boolean = true,
    val busy: Boolean = false,
) {
    val canApply: Boolean
        get() = when (mode) {
            PadMode.Saved -> false
            PadMode.Draw -> strokes.any { it.size > 1 }
            PadMode.Type -> typedName.isNotBlank()
            PadMode.Upload -> uploadBytes != null
        }

    private fun projection(): List<Any?> =
        listOf(mode, strokes, typedName, font, uploadBytes?.size, uploadName, saveForLater, busy)

    override fun equals(other: Any?): Boolean = other is PadState && other.projection() == projection()
    override fun hashCode(): Int = projection().hashCode()
}

/**
 * The signing surface — the web's `SigningView`.
 *
 * The consent gate stands until the server has recorded acceptance; fields
 * are answered in order, and Finish stays shut until every required field
 * is done.
 */
data class SigningState(
    val envelope: Envelope,
    val mode: SigningMode = SigningMode.Sign,
    val me: EnvelopeRecipient? = null,
    val pages: List<EsignPage> = emptyList(),
    val loadingPages: Boolean = true,
    val notPdf: Boolean = false,
    /** Fields waiting on the current user, in page order. */
    val myFields: List<EnvelopeField> = emptyList(),
    val currentIndex: Int = 0,
    /** Answers by field id. */
    val answers: Map<String, FieldAnswer> = emptyMap(),
    /** The gate: up until the server records the acceptance. */
    val needsConsent: Boolean = false,
    val consenting: Boolean = false,
    /** One pad covers every auto-initial — the web's "Sign once for every page". */
    val signOnce: Boolean = true,
    /** Copy the mark to every remaining field of the same type. */
    val applyToAll: Boolean = false,
    val pad: PadState? = null,
    val declining: Boolean = false,
    val declineReason: String = "",
    val submitting: Boolean = false,
    /** Mark and upload images fetched for the overlays, by media key. */
    val images: Map<String, ByteArray> = emptyMap(),
    /** The moment after Finish — the web's "Signing Complete" screen. */
    val finished: Boolean = false,
    /** Where Back goes: the detail it was opened from, or the list. */
    val returnToDetail: Boolean = false,
    /** Marks the signer can pick without drawing. */
    val savedMarks: List<SavedSignature> = emptyList(),
) {
    /** The auto-initials collapse to one pad in sign-once mode. */
    val visibleFields: List<EnvelopeField>
        get() = if (!signOnce) {
            myFields
        } else {
            val firstAuto = myFields.firstOrNull { it.autoInitial }
            myFields.filter { !it.autoInitial || it === firstAuto }
        }

    val current: EnvelopeField? get() = visibleFields.getOrNull(currentIndex)

    fun isDone(field: EnvelopeField): Boolean = when {
        field.locked -> true
        answers.containsKey(field.id) -> answers[field.id].let { it !is FieldAnswer.Typed || it.text.isNotBlank() }
        field.type.isAutoStamped -> true
        !field.required -> true
        else -> false
    }

    val requiredCount: Int get() = visibleFields.count { it.required && !it.locked && !it.type.isAutoStamped }
    val completedRequired: Int
        get() = visibleFields.count { it.required && !it.locked && !it.type.isAutoStamped && isDone(it) }
    val allRequiredDone: Boolean get() = visibleFields.all { !it.required || isDone(it) }
    val hasOptional: Boolean get() = visibleFields.any { !it.required }

    val canFinish: Boolean
        get() = mode == SigningMode.Sign && !needsConsent && allRequiredDone && !submitting && me != null
    val readOnly: Boolean get() = mode != SigningMode.Sign || me == null || me.signed || me.declined
}

// ---------------------------------------------------------------- marks

/** The saved-marks manager — the web's `SavedSignaturePicker` in manage mode. */
data class MarksState(
    val items: List<SavedSignature> = emptyList(),
    val images: Map<String, ByteArray> = emptyMap(),
    val loading: Boolean = false,
    val open: Boolean = false,
    /** Signature or initials. */
    val forSignature: Boolean = true,
    val pad: PadState = PadState(mode = PadMode.Draw),
    val confirmDeleteId: String? = null,
) {
    val signatures: List<SavedSignature> get() = items.filter { it.isSignature }
    val initials: List<SavedSignature> get() = items.filter { !it.isSignature }
}

// ---------------------------------------------------------------- templates

data class TemplatesState(
    val items: List<EnvelopeTemplate> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val search: String = "",
    /** Null is every category. */
    val category: String? = null,
    val layout: ListLayout = ListLayout.Card,
    val busyId: String? = null,
    val confirmDeleteId: String? = null,
    val detail: EnvelopeTemplate? = null,
) {
    val categories: List<String> get() = items.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()

    val visible: List<EnvelopeTemplate>
        get() = items.filter { template ->
            (category == null || template.category == category) &&
                (
                    search.isBlank() || template.name.contains(search, true) ||
                        template.description.contains(search, true)
                    )
        }
}

// ---------------------------------------------------------------- bulk send

/** The CSV picked for a bulk send, parsed. */
data class ParsedCsv(
    val headers: List<String>,
    val rows: List<Map<String, String>>,
    val delimiter: Char,
) {
    val nameKey: String? get() = headers.firstOrNull { it.trim().lowercase() in NAME_HEADERS }
    val emailKey: String? get() = headers.firstOrNull { it.trim().lowercase() in EMAIL_HEADERS }
    val hasRequiredColumns: Boolean get() = nameKey != null && emailKey != null

    /** Rows whose name and email are both usable. */
    fun validRows(): List<Map<String, String>> = rows.filter { rowValid(it) }
    fun rowValid(row: Map<String, String>): Boolean {
        val name = nameKey?.let { row[it] }.orEmpty().trim()
        val email = emailKey?.let { row[it] }.orEmpty().trim()
        return name.isNotBlank() && EMAIL_RE.matches(email)
    }

    /** Columns the template's fields can be pre-filled from — everything past the two required. */
    val extraColumns: List<String> get() = headers.filter { it != nameKey && it != emailKey }
}

data class BulkSendState(
    val template: EnvelopeTemplate,
    /** 1 upload, 2 preview, 3 confirm. */
    val step: Int = 1,
    val fileName: String = "",
    val csvText: String = "",
    val parsed: ParsedCsv? = null,
    val batchName: String = "",
    val submitting: Boolean = false,
) {
    val validCount: Int get() = parsed?.validRows()?.size ?: 0
    val invalidCount: Int get() = (parsed?.rows?.size ?: 0) - validCount
    val overCap: Boolean get() = validCount > BULK_ROW_CAP
}

data class BulkState(
    val jobs: List<BulkJob> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    /** The job whose rows are open, with its rows loaded. */
    val open: BulkJob? = null,
    val openLoading: Boolean = false,
    val busyJobId: String? = null,
    val send: BulkSendState? = null,
)

// ---------------------------------------------------------------- root

data class EsignUiState(
    val viewer: EsignViewer = EsignViewer(),
    /** The tool's unread rows — the surfaces', buckets' and envelopes' badges. */
    val unread: EsignUnread = EsignUnread.None,
    val currentUserId: String = "",
    val currentUserEmail: String = "",
    val surface: EsignSurface = EsignSurface.Manage,
    val page: EsignPageKind = EsignPageKind.Lists,
    val layout: ListLayout = ListLayout.List,
    val manage: ManageState = ManageState(),
    val signList: SignListState = SignListState(),
    val editor: EditorState? = null,
    val detail: DetailState? = null,
    val signing: SigningState? = null,
    val marks: MarksState = MarksState(),
    val templates: TemplatesState = TemplatesState(),
    val bulk: BulkState = BulkState(),
    /** Counts for the segment badges: envelopes waiting on me. */
    val pendingForMe: Int = 0,
)

sealed interface EsignEffect {
    data object PickPdf : EsignEffect
    data object PickCsv : EsignEffect
    data object PickImage : EsignEffect
    data class Notice(val message: String) : EsignEffect
    data class Failed(val message: String) : EsignEffect
}

private val NAME_HEADERS = setOf("name", "full_name", "full name", "fullname")
private val EMAIL_HEADERS = setOf("email", "email_address", "e-mail", "email address")
internal val EMAIL_RE = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
internal const val BULK_ROW_CAP = 100
