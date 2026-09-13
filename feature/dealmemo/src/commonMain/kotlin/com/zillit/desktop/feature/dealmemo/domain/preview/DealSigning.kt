package com.zillit.desktop.feature.dealmemo.domain.preview

import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DocRead

enum class SignSurfaceKind { DealPdf, StartForm, Document }

/**
 * A document this viewer could sign (`DMDealPreviewPage.jsx:2093-2172`).
 *
 * [signedByMe] is relative to the role the viewer signs in; [signedByCrew]
 * is anyone's crew signature — what an observer is told.
 */
data class SignSurface(
    val key: String,
    val label: String,
    val kind: SignSurfaceKind,
    /** A PDF with bytes — technically signable. */
    val signable: Boolean,
    /** The crew's signing work: the deal memo and sign-required documents, never the start form. */
    val signRequired: Boolean,
    val signedByMe: Boolean,
    val signedByCrew: Boolean,
    val doc: AdditionalDoc? = null,
) {
    companion object {
        const val DEAL_PDF = "deal_pdf"
        const val START_FORM = "startform"
    }
}

enum class PreviewTabKind { Deal, StartForm, Document }

/** One document the page can open — the deal memo, the start form, each additional document. */
data class PreviewTab(
    val key: String,
    val label: String,
    val kind: PreviewTabKind,
    val description: String = "",
    val signed: Boolean = false,
    val doc: AdditionalDoc? = null,
)

enum class ChipTone { Todo, Done, Neutral, Disabled }

/** What clicking a chip in the crew bar does. */
sealed interface ChipAction {
    data class Sign(val key: String) : ChipAction

    /** The deal memo while details are missing: the "Not ready yet" list, fields only. */
    data object FieldsGate : ChipAction

    data object ViewPdf : ChipAction

    data class OpenTab(val tab: PreviewTab) : ChipAction

    data object None : ChipAction
}

/** One chip of the crew bar's document groups. */
data class DocChip(
    val key: String,
    val label: String,
    val sub: String,
    val tone: ChipTone,
    val action: ChipAction,
    /** The dot before the label: an observer's unsigned document. */
    val dot: Boolean = false,
)

/**
 * The page's documents and who has signed them — the sign surfaces, the
 * document tabs, and the crew bar's two chip groups.
 */
object DealSigning {

    /** `buildSignSurfaces`, for a viewer signing as [userType]. */
    fun surfaces(deal: DealDoc, userId: String?, userType: String): List<SignSurface> {
        val dealPdf = SignedPdf.of(DocRead.obj(deal.json, "deal_pdf"))
        val startForm = SignedPdf.of(DocRead.obj(deal.json, "crew_start_form_pdf"))
        val fixed = listOf(
            SignSurface(
                key = SignSurface.DEAL_PDF,
                label = "Deal Memo",
                kind = SignSurfaceKind.DealPdf,
                signable = true,
                signRequired = true,
                signedByMe = dealPdf.hasStoredCopy && DealSigner.hasSignedAs(dealPdf.signers, userId, userType),
                signedByCrew = dealPdf.signers.any { it.userType == DealSigner.CREW },
            ),
            SignSurface(
                key = SignSurface.START_FORM,
                label = "Start Form",
                kind = SignSurfaceKind.StartForm,
                signable = true,
                signRequired = false,
                signedByMe = startForm.hasStoredCopy && DealSigner.hasSignedAs(startForm.signers, userId, userType),
                signedByCrew = false,
            ),
        )
        val documents = AdditionalDoc.listOf(DocRead.obj(deal.json, "additional_documents")).map { doc ->
            SignSurface(
                key = doc.signKey,
                label = doc.label,
                kind = SignSurfaceKind.Document,
                signable = doc.signable,
                signRequired = doc.signRequired,
                // Additional documents count on the signer row alone — edits never void them.
                signedByMe = DealSigner.hasSignedAs(doc.signers, userId, userType),
                signedByCrew = doc.signedByCrew,
                doc = doc,
            )
        }
        return fixed + documents
    }

    /** The crew's signing work: signable and sign-required. */
    fun crewSurfaces(surfaces: List<SignSurface>): List<SignSurface> = surfaces.filter {
        it.signable && it.signRequired
    }

    /** `previewTabs`: Deal Memo, Start Form, then one tab per additional document. */
    fun tabs(deal: DealDoc): List<PreviewTab> {
        val dealPdf = SignedPdf.of(DocRead.obj(deal.json, "deal_pdf"))
        val startForm = SignedPdf.of(DocRead.obj(deal.json, "crew_start_form_pdf"))
        return listOf(
            PreviewTab("deal", "Deal Memo", PreviewTabKind.Deal, signed = dealPdf.signers.isNotEmpty()),
            PreviewTab(
                SignSurface.START_FORM,
                "Start Form",
                PreviewTabKind.StartForm,
                signed = startForm.signers.isNotEmpty(),
            ),
        ) + AdditionalDoc.listOf(DocRead.obj(deal.json, "additional_documents")).map { doc ->
            PreviewTab(
                key = doc.tabKey,
                label = doc.label,
                kind = PreviewTabKind.Document,
                description = doc.description,
                signed = doc.signers.isNotEmpty(),
                doc = doc,
            )
        }
    }

    /**
     * The owner's sign group (`barSignDocs`): unsigned chips sign — the deal
     * memo goes to the fields list first while details are missing — and a
     * signed chip opens the signed copy.
     */
    fun ownerChips(crew: List<SignSurface>, tabs: List<PreviewTab>, fieldsMissing: Boolean): List<DocChip> =
        crew.map { surface ->
            val tab = tabs.firstOrNull { it.key == surface.key }
            val action = when {
                !surface.signedByMe && surface.key == SignSurface.DEAL_PDF && fieldsMissing -> ChipAction.FieldsGate
                !surface.signedByMe -> ChipAction.Sign(surface.key)
                surface.key == SignSurface.DEAL_PDF -> ChipAction.ViewPdf
                tab != null -> ChipAction.OpenTab(tab)
                else -> ChipAction.None
            }
            DocChip(
                key = surface.key,
                label = surface.label,
                sub = if (surface.signedByMe) "Signed by you" else "Awaiting your signature",
                tone = if (surface.signedByMe) ChipTone.Done else ChipTone.Todo,
                action = action,
            )
        }

    /**
     * The same group inside the "Not ready yet" gate: the unsigned deal memo
     * goes inert while details are missing, so the gate never swaps itself to
     * a fields-only list under the user's cursor.
     */
    fun gateChips(owner: List<DocChip>, fieldsMissing: Boolean): List<DocChip> = owner.map { chip ->
        if (chip.key == SignSurface.DEAL_PDF && chip.tone == ChipTone.Todo && fieldsMissing) {
            chip.copy(tone = ChipTone.Disabled, sub = "Complete your details first", action = ChipAction.None)
        } else {
            chip
        }
    }

    /** `barSignDocsReadOnly`: an observer's view of the crew's signing — every chip just opens its document. */
    fun observerChips(crew: List<SignSurface>, tabs: List<PreviewTab>): List<DocChip> = crew.map { surface ->
        val tab = tabs.firstOrNull { it.key == surface.key }
        DocChip(
            key = surface.key,
            label = surface.label,
            sub = if (surface.signedByCrew) "Signed by the crew member" else "Awaiting signature",
            tone = if (surface.signedByCrew) ChipTone.Done else ChipTone.Neutral,
            action = when {
                surface.key == SignSurface.DEAL_PDF -> ChipAction.ViewPdf
                tab != null -> ChipAction.OpenTab(tab)
                else -> ChipAction.None
            },
            dot = !surface.signedByCrew,
        )
    }

    /** `barViewDocs`: everything but the deal memo and the sign group's documents. */
    fun viewChips(tabs: List<PreviewTab>, crew: List<SignSurface>): List<DocChip> {
        val signKeys = crew.map { it.key }.toSet()
        return tabs.filter { it.key != "deal" && it.key !in signKeys }.map { tab ->
            DocChip(
                key = tab.key,
                label = tab.label,
                sub = tab.description.ifEmpty { if (tab.signed) "Signed" else "View" },
                tone = ChipTone.Neutral,
                action = ChipAction.OpenTab(tab),
            )
        }
    }

    /** The bar's status sentence: nothing without a sign group, else how many are still unsigned. */
    fun statusSentence(chips: List<DocChip>, owner: Boolean): String {
        if (chips.isEmpty()) return ""
        val outstanding = chips.count { it.tone != ChipTone.Done }
        if (outstanding == 0) return "All documents signed"
        val who = if (owner) "your" else "crew"
        return "$outstanding document${if (outstanding == 1) "" else "s"} awaiting $who signature"
    }

    /** "View Signed PDF" once a stored copy exists — the only kind a sign POST writes. */
    fun pdfLabel(deal: DealDoc): String =
        if (SignedPdf.of(DocRead.obj(deal.json, "deal_pdf")).hasStoredCopy) "View Signed PDF" else "View PDF"

    /** "Details changed after signing": signer rows survive an edit but the stored copy was withdrawn. */
    fun signatureWithdrawn(deal: DealDoc): Boolean {
        val pdf = SignedPdf.of(DocRead.obj(deal.json, "deal_pdf"))
        return pdf.signers.isNotEmpty() && !pdf.hasStoredCopy
    }
}
