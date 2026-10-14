package com.zillit.desktop.feature.formsignature.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * One unread leaf of the tool's notification ledger.
 *
 * `forms_and_signature_label` rows file under a unit per surface
 * (`ContractSignatureV2/utils.js:212-223`): `document_added_to_general_label`
 * is a standard form, keyed by the form in `level_2`;
 * `document_for_signature_label` is a document out for signature, keyed by
 * its tab in `level_1` (`received_for_signature`, `fully_signed`). The three
 * older units — received, finalized, under discussion — are tiles the web
 * no longer shows but still sums, so they ride with the Documents tile here.
 */
data class FormBadgeLeaf(val unit: String, val level1: String, val level2: String, val unread: Int)

/**
 * The tool's unread rows and the reads its screens make — one document at a
 * time, when it is opened, as iOS reads them (`FormDocumentView.swift:401-446`,
 * `ContractSignatureDocumentView.swift:180-195`): a standard form under
 * `all_forms` (`FormDetailsV2.jsx:690-706`), a document for signature by the
 * leaves its tab holds for it. A tab shown reads nothing any more — the web's
 * whole-tab read (`DocumentsForSignature.jsx:95-108`) cleared badges nobody
 * had opened.
 */
interface FormSignatureBadges {
    val leaves: Flow<List<FormBadgeLeaf>> get() = emptyFlow()

    /** A standard form's detail on screen: its rows under `all_forms`. */
    fun readStandardForm(formId: String) {}

    /**
     * One opened document's rows, leaf by leaf — each leaf's unit, `level_1`
     * and `level_2` as a level read; a blank level is left unscoped. The
     * leaves come from [FormSignatureUnread.documentLeaves] /
     * [FormSignatureUnread.unlistedAllForms].
     */
    fun readLeaves(leaves: List<FormBadgeLeaf>) {}

    /**
     * The discussion room left (`readContractSignatureChatBadges`):
     * `notification:read` with the general unit as segment and the room as
     * `level_1`.
     */
    fun readChat(unitId: String) {}

    companion object {
        val None: FormSignatureBadges = object : FormSignatureBadges {}

        const val TOOL = "forms_and_signature_label"
        const val UNIT_GENERAL = "document_added_to_general_label"
        const val UNIT_FOR_SIGNATURE = "document_for_signature_label"
        const val UNIT_RECEIVED = "document_received_for_signature_label"
        const val UNIT_FINALIZED = "document_finalized_label"
        const val UNIT_UNDER_DISCUSSION = "document_under_discussion_label"
        const val LEVEL_ALL_FORMS = "all_forms"
        const val LEVEL_RECEIVED = "received_for_signature"
        const val LEVEL_FULLY_SIGNED = "fully_signed"

        /** The units the Documents tile and tabs answer for. */
        val documentUnits: Set<String> = setOf(UNIT_FOR_SIGNATURE, UNIT_RECEIVED, UNIT_FINALIZED, UNIT_UNDER_DISCUSSION)
    }
}

/** The leaves cut the way the screens ask: the hub's tiles, the document tabs, one form. */
data class FormSignatureUnread(val leaves: List<FormBadgeLeaf> = emptyList()) {

    val standardForms: Int get() = leaves.filter { it.unit == FormSignatureBadges.UNIT_GENERAL }.sumOf { it.unread }

    val documents: Int get() = leaves.filter { it.unit in FormSignatureBadges.documentUnits }.sumOf { it.unread }

    /** One standard form: rows naming it in `level_2` (the web matches `_id` and `document_id` alike). */
    fun form(vararg ids: String): Int =
        leaves
            .filter { it.unit == FormSignatureBadges.UNIT_GENERAL && it.level2 in ids.filter { id -> id.isNotBlank() } }
            .sumOf { it.unread }

    fun tab(tab: SignDocumentTab): Int = tabLeaves(tab).sumOf { it.unread }

    /** The Documents tab inside Standard Documents — `level_1 = all_forms` (`makeApiForBadges`). */
    val allFormsTab: Int
        get() = leaves.filter {
            it.unit == FormSignatureBadges.UNIT_GENERAL && it.level1 == FormSignatureBadges.LEVEL_ALL_FORMS
        }.sumOf { it.unread }

    /** The "Chat with …" button — rows of the general unit filed under the room (`makeApiForParentChatBadges`). */
    fun chat(unitId: String): Int =
        leaves.filter { it.unit == FormSignatureBadges.UNIT_GENERAL && it.level1 == unitId }.sumOf { it.unread }

    /**
     * The leaves behind one tab. Received and Fully signed are the web's two
     * `level_1` slices of the for-signature unit plus their older units;
     * Sent takes what is left, so no row of the tile can hide behind a tab
     * that never reads it.
     */
    fun tabLeaves(tab: SignDocumentTab): List<FormBadgeLeaf> = leaves.filter { leaf ->
        when (tab) {
            SignDocumentTab.Received ->
                leaf.isForSignature(FormSignatureBadges.LEVEL_RECEIVED) ||
                    leaf.unit == FormSignatureBadges.UNIT_RECEIVED
            SignDocumentTab.Finalized ->
                leaf.isForSignature(FormSignatureBadges.LEVEL_FULLY_SIGNED) ||
                    leaf.unit == FormSignatureBadges.UNIT_FINALIZED
            SignDocumentTab.Uploaded ->
                leaf.unit == FormSignatureBadges.UNIT_UNDER_DISCUSSION ||
                    (
                        leaf.unit == FormSignatureBadges.UNIT_FOR_SIGNATURE &&
                            leaf.level1 != FormSignatureBadges.LEVEL_RECEIVED &&
                            leaf.level1 != FormSignatureBadges.LEVEL_FULLY_SIGNED
                        )
        }
    }

    /**
     * The leaves one document opened from [tab] reads — iOS's per-document
     * read: unit, `level_1`, `level_2` = the document (`FormDocumentView.swift:401-446`).
     *
     * Its own leaves are the tab's leaves whose `level_2` names it ([ids]:
     * `_id` and `document_id`, which the rows key on alike). Beside them ride
     * the tab's leaves that name NONE of the documents [listed] in the tab —
     * a row filed without a document id, or for a document since withdrawn:
     * no open could ever name those, and with the tab's own entry read gone
     * they would count for good. So every leaf a tab counts is cleared by
     * some open in that tab.
     *
     * iOS scopes its discussion reads to two actions; here the discussion
     * unit has no room of its own to clear the rest, so the document's whole
     * leaf goes.
     */
    fun documentLeaves(
        tab: SignDocumentTab,
        ids: Collection<String>,
        listed: Collection<String>,
    ): List<FormBadgeLeaf> {
        val own = ids.filter { it.isNotBlank() }.toSet()
        val known = listed.filter { it.isNotBlank() }.toSet() + own
        return tabLeaves(tab).filter { it.level2 in own || it.level2 !in known }
    }

    /**
     * The All tab's `all_forms` leaves that name none of the forms [listed]
     * there — read with whichever form is opened, so the tab's count never
     * holds a row no open could clear (see [documentLeaves]).
     */
    fun unlistedAllForms(listed: Collection<String>): List<FormBadgeLeaf> {
        val known = listed.filter { it.isNotBlank() }.toSet()
        return leaves.filter {
            it.unit == FormSignatureBadges.UNIT_GENERAL && it.level1 == FormSignatureBadges.LEVEL_ALL_FORMS &&
                it.level2 !in known
        }
    }

    private fun FormBadgeLeaf.isForSignature(level1: String): Boolean =
        unit == FormSignatureBadges.UNIT_FOR_SIGNATURE && this.level1 == level1

    companion object {
        val None = FormSignatureUnread()
    }
}
