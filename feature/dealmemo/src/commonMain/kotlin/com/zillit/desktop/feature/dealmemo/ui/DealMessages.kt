package com.zillit.desktop.feature.dealmemo.ui

import com.zillit.desktop.core.localization.LabelKind
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.core.localization.localisedMessage

/**
 * Toast wording, resolved the way the web's `showApiSuccess` / `showApiError`
 * do: the server's message through the translations, else the fallback key
 * the page names.
 *
 * The fallback keys are the web's own, and most have no translation shipped —
 * the web then shows the raw key. English for them lives here so a toast reads
 * as a sentence while the dictionary catches up.
 */
internal object DealMessages {

    private val ENGLISH: Map<String, String> = mapOf(
        "deal_activated" to "Deal memo activated",
        "deal_memo_chased" to "Crew member chased",
        "deal_deleted" to "Deal memo deleted",
        "deal_deactivation_scheduled" to "Deactivation scheduled",
        "deal_saved" to "Deal memo saved",
        "deal_issued" to "Deal memo issued",
        "deal_submitted" to "Deal memo sent for approval",
        "deal_approved" to "Deal memo approved",
        "deal_rejected" to "Deal memo rejected",
        "deal_amendment_acknowledged" to "Thanks — your acknowledgement has been recorded.",
        "failed_to_acknowledge_amendment" to "Couldn't record your acknowledgement. Please try again.",
        "deal_rules_updated" to "Pay rules updated. The crew member has been asked to acknowledge the change.",
        "deal_no_rule_fields" to "There was nothing to amend — change at least one pay rule first.",
        "failed_to_update_deal_rules" to "Couldn't update the pay rules.",
        "failed_to_update_nominal_codes" to "Couldn't update the nominal codes.",
        "failed_to_save_personal_details" to "Couldn't save your details.",
        "failed_to_load_document_for_signing" to "Couldn't load this document for signing.",
        "failed_to_sign_document" to "Couldn't sign the document.",
        "failed_to_save_signature" to "Couldn't save the signature for next time.",
        "deal_resubmitted" to "Deal memo sent for approval again",
        "nominal_codes_updated" to "Nominal codes updated",
        "personal_details_saved" to "Your details were saved",
        "template_saved" to "Setup saved",
        "template_updated" to "Setup updated",
        "template_save_failed" to "Couldn't save the setup.",
        "template_load_failed" to "Couldn't load the setup.",
        "template_deleted" to "Setup deleted",
        "template_delete_failed" to "Couldn't delete the setup.",
        "deal_bank_account_not_updated" to "The deal was saved, but its linked bank account couldn't be updated.",
        "documents_uploaded" to "Documents uploaded",
        "document_upload_failed" to "Couldn't save the uploaded documents.",
        "document_update_failed" to "Couldn't update the document.",
        "document_delete_failed" to "Couldn't remove the document.",
        "rates_refreshed" to "Rates refreshed",
        "notice_template_saved" to "Notice template saved successfully.",
        "failed_to_load_overview" to "Couldn't load the overview.",
        "failed_to_load_approval_queue" to "Couldn't load the approval queue.",
        "failed_to_load_your_deal_memo" to "Couldn't load your deal memo.",
        "failed_to_load_deal_memo" to "Couldn't load the deal memo.",
        "export_failed" to "Export failed",
        "something_went_wrong" to "Something went wrong",
    )

    /** Hard-coded English the web shows for codes that have no translation yet. */
    private val OVERRIDES: Map<String, String> = mapOf(
        "deal_reject_reason_required" to "Add a reason before rejecting this deal.",
        "deal_crew_cannot_reject" to "This deal can no longer be rejected — it has already moved past your stage.",
        "deal_no_deal_for_user" to "We couldn't find a deal memo assigned to you.",
        "deal_portal_deal_missing" to "This deal memo link is no longer valid.",
        "deal_no_pending_amendment" to "There is no pending amendment on this deal memo.",
        "deal_cannot_edit_cancelled" to "This deal memo has been cancelled and can no longer be edited.",
    )

    fun text(serverMessage: String?, fallbackKey: String): String {
        val key = serverMessage?.takeIf { it.isNotBlank() } ?: fallbackKey
        OVERRIDES[key]?.let { return it }
        Labels.current.exact(key, LabelKind.Messages)?.let { return it }
        return ENGLISH[key] ?: key.localisedMessage()
    }

    fun override(code: String): String? =
        OVERRIDES[code] ?: ENGLISH[code]?.takeIf { Labels.current.exact(code, LabelKind.Messages) == null }
}
