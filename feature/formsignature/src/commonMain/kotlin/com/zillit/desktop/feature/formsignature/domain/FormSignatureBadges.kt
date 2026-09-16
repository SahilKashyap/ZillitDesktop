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
 * The tool's unread rows and the reads its screens make — a standard form
 * opened (`FormDetailsV2.jsx:690-706`), a documents tab shown
 * (`DocumentsForSignature.jsx:95-108`).
 */
interface FormSignatureBadges {
    val leaves: Flow<List<FormBadgeLeaf>> get() = emptyFlow()

    /** A standard form's detail on screen: its rows under `all_forms`. */
    fun readStandardForm(formId: String) {}

    /** A documents tab on screen: every leaf the tab is made of. */
    fun readDocumentsTab(leaves: List<FormBadgeLeaf>) {}

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

    private fun FormBadgeLeaf.isForSignature(level1: String): Boolean =
        unit == FormSignatureBadges.UNIT_FOR_SIGNATURE && this.level1 == level1

    companion object {
        val None = FormSignatureUnread()
    }
}
