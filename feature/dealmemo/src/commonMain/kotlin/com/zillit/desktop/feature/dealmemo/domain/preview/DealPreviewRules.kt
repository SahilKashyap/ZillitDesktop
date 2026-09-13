package com.zillit.desktop.feature.dealmemo.domain.preview

import com.zillit.desktop.feature.dealmemo.domain.AmendmentAck
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DealMemoMetadata
import com.zillit.desktop.feature.dealmemo.domain.DealStatus
import com.zillit.desktop.feature.dealmemo.domain.DocRead
import com.zillit.desktop.feature.dealmemo.domain.isNonUnionId

/** Who is looking at a deal, for the page's gates. */
data class PreviewViewer(val userId: String, val canPost: Boolean, val isAccountant: Boolean)

/** One of the header's edit actions (`DealEditControl.jsx:190-220`). */
enum class EditAction(val glyph: String, val title: String, val subtitle: String) {
    Rules("£", "Update Non-Union Pay breakdown", "Overtime, premiums, penalties, turnarounds"),
    Nominals("#", "Update Nominals", "Cost codes and account allocation"),
    Edit("✎", "Edit Deal Memo", "Terms, dates, attachments"),
}

/** What the header's edit control renders: nothing, one plain button, or a menu. */
data class EditControl(val actions: List<EditAction>, val label: String, val primary: Boolean) {
    /** One action: a plain button named for it — only Edit takes the page's label. */
    val buttonLabel: String
        get() = actions.singleOrNull()?.let { if (it == EditAction.Edit) label else it.title } ?: label
}

/** A blocker the "Not ready yet" list names; the documents one gates but shows as chips. */
data class Blocker(val section: String, val missing: List<String>, val chipsOnly: Boolean = false)

/** A row of "Checklist for the Crew Member". */
data class ChecklistRow(val id: String, val label: String, val done: Boolean, val required: List<RequiredField>)

/**
 * Every gate on the deal page (`DMDealPreviewPage.jsx` §1.5) as one derivation
 * over the deal, the viewer and where the page is shown.
 *
 * [embedded] is My Deal; the standalone page is `/deals/:id`. Crew editing is
 * My Deal's and is withdrawn once the deal is live.
 */
data class DealPreviewRules(
    val deal: DealDoc,
    val viewer: PreviewViewer,
    val metadata: DealMemoMetadata,
    val embedded: Boolean,
    val draft: CrewDraft? = null,
) {
    private val status: String? get() = deal.rawStatus
    private val isOwner: Boolean get() = viewer.userId.isNotEmpty() && deal.userId == viewer.userId
    val isCreator: Boolean get() = viewer.userId.isNotEmpty() && deal.createdBy == viewer.userId

    /** The deal with the crew member's unsaved edits laid over it. */
    val shown: DealDoc by lazy { deal.withDraft(draft) }

    val chain: ApprovalChain by lazy { ApprovalChain.of(deal, metadata, viewer.userId) }

    /** The owner of an issued deal. */
    val canCrewSign: Boolean get() = status == DealStatus.Issued.wire && isOwner

    /** The approver whose level is next — standalone only. */
    val canApproverSign: Boolean
        get() = !embedded && status == DealStatus.AwaitingApproval.wire && metadata.isApprover && chain.canAct

    val signUserType: String get() = if (canApproverSign) DealSigner.APPROVER else DealSigner.CREW

    /** Crew reject — My Deal, the owner, issued or awaiting approval. */
    val canCrewReject: Boolean
        get() = embedded && isOwner && (status == DealStatus.Issued.wire || status == DealStatus.AwaitingApproval.wire)

    val canAcknowledgeAmendment: Boolean get() = deal.amendmentAck == AmendmentAck.Pending && isOwner

    /** My Deal edits the crew member's details until the deal goes live. */
    val crewCanEdit: Boolean
        get() = embedded && status != DealStatus.Active.wire && status != DealStatus.Deactivated.wire

    val showApprovalChain: Boolean
        get() = !embedded && chain.nodes.isNotEmpty() && status == DealStatus.AwaitingApproval.wire

    val showApproverActions: Boolean get() = canApproverSign

    val showEditCluster: Boolean get() = !embedded && (viewer.canPost || isCreator)

    val showActivate: Boolean get() = showEditCluster && status == DealStatus.Approved.wire

    val showDeactivatingChip: Boolean get() = !embedded && viewer.canPost && deal.isDeactivating

    /** A portal link is fetched only for external crew on a deal they can act on. */
    val shareLinkEligible: Boolean
        get() = !embedded && deal.externalFlag && status in SHARE_STATUSES

    val showShareStrip: Boolean get() = shareLinkEligible && (viewer.canPost || isCreator)

    val showHistory: Boolean get() = !embedded

    /** `dealEditActions`: rules for non-union deals and posters, nominals for accountants, edit until live. */
    val editControl: EditControl
        get() {
            val notRejected = status != DealStatus.Rejected.wire
            val agreement = DocRead.text(DocRead.obj(deal.json, "territory_union"), "agreement_identifier")
            val actions = buildList {
                if (notRejected && isNonUnionId(agreement) && viewer.canPost) add(EditAction.Rules)
                if (notRejected && viewer.isAccountant) add(EditAction.Nominals)
                if (status !in LOCKED_FOR_EDIT) add(EditAction.Edit)
            }
            val draftDeal = status == DealStatus.Draft.wire
            return EditControl(actions, if (draftDeal) "Edit and Issue Deal Memo" else "Edit", primary = draftDeal)
        }

    val nominalForm: NominalForm by lazy { NominalCoding.hydrate(deal) }

    val missingNominals: List<NominalRow> by lazy { NominalCoding.missingRows(deal, nominalForm) }

    val showNominalsPending: Boolean
        get() = !embedded && viewer.isAccountant && NominalCoding.applies(deal) &&
            EditAction.Nominals in editControl.actions && missingNominals.isNotEmpty()

    // -- documents ---------------------------------------------------------------------

    val surfaces: List<SignSurface> by lazy {
        if (canCrewSign || canApproverSign) DealSigning.surfaces(shown, viewer.userId, signUserType) else emptyList()
    }

    val crewSurfaces: List<SignSurface> get() = DealSigning.crewSurfaces(surfaces)

    val tabs: List<PreviewTab> by lazy { DealSigning.tabs(shown) }

    /** The owner signs on My Deal; everywhere else the group only reports the crew's signing. */
    val ownerSigning: Boolean get() = embedded && canCrewSign

    val signChips: List<DocChip>
        get() = if (ownerSigning) {
            DealSigning.ownerChips(crewSurfaces, tabs, fieldBlockers.isNotEmpty())
        } else {
            DealSigning.observerChips(crewSurfaces, tabs)
        }

    val viewChips: List<DocChip> get() = DealSigning.viewChips(tabs, crewSurfaces)

    val editedAfterSigning: Boolean
        get() = crewCanEdit && canCrewSign &&
            crewSurfaces.firstOrNull { it.key == SignSurface.DEAL_PDF }?.signedByMe == true

    val signatureWithdrawn: Boolean get() = DealSigning.signatureWithdrawn(deal)

    /** Send for Approval: the owner on My Deal while the deal is issued. */
    val showSend: Boolean get() = ownerSigning

    // -- blockers and checklist ------------------------------------------------------------

    /** Missing marked-or-floor details, then the UK HMRC answers where they apply. */
    val fieldBlockers: List<Blocker> by lazy {
        buildList {
            val missing = CrewRequirements.missingLabels(shown, draft)
            if (missing.isNotEmpty()) add(Blocker("Details marked required", missing))
            if (UkPayroll.appliesTo(shown)) {
                val uk = UkPayroll.missingLabels(DocRead.obj(shown.crew, UkPayroll.KEY))
                if (uk.isNotEmpty()) add(Blocker(UkPayroll.LABEL, uk))
            }
        }
    }

    /** Everything between the crew and Send for Approval, documents last. */
    val sendBlockers: List<Blocker>
        get() {
            val unsigned = crewSurfaces.filterNot { it.signedByMe }
            return fieldBlockers + listOfNotNull(
                unsigned.takeIf { it.isNotEmpty() }?.let { list ->
                    Blocker("Documents to sign", list.map { it.label.ifEmpty { "Document" } }, chipsOnly = true)
                },
            )
        }

    val checklist: List<ChecklistRow> by lazy {
        val required = CrewRequirements.fieldStatus(shown, draft)
        listOf(
            ChecklistRow(
                "personal",
                "Personal details",
                CrewRequirements.sectionComplete(required.personal, CrewRequirements.personalCoreDone(shown)),
                required.personal,
            ),
            ChecklistRow(
                "bank",
                "Bank details",
                CrewRequirements.sectionComplete(required.bank, BankWire.isComplete(DocRead.obj(shown.json, "bank"))),
                required.bank,
            ),
        ) + AdditionalDoc.listOf(DocRead.obj(shown.json, "additional_documents"))
            .filter { it.isChecklistDocument }
            .mapIndexed { index, doc ->
                ChecklistRow("doc-${doc.id ?: index}", doc.label, doc.signedByCrew, emptyList())
            }
    }

    companion object {
        private val SHARE_STATUSES = setOf("issued", "awaiting_approval", "active")
        private val LOCKED_FOR_EDIT = setOf("active", "deactivated", "completed", "cancelled")
    }
}
