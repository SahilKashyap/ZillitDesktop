package com.zillit.desktop.feature.dealmemo.ui

import com.zillit.desktop.core.localization.LabelKind
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

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

    /** The web's fallback keys, each backed by a catalogue key so the toast reads in the app's language. */
    private val ENGLISH: Map<String, String> = mapOf(
        "deal_activated" to S.desktop_dm_deal_memo_activated,
        "deal_memo_chased" to S.desktop_dm_crew_member_chased,
        "deal_deleted" to S.desktop_dm_deal_memo_deleted,
        "deal_deactivation_scheduled" to S.desktop_dm_deactivation_scheduled,
        "deal_saved" to S.desktop_dm_deal_memo_saved,
        "deal_issued" to S.desktop_dm_deal_memo_issued,
        "deal_submitted" to S.desktop_dm_deal_memo_sent_for_approval,
        "deal_approved" to S.desktop_dm_deal_memo_approved,
        "deal_rejected" to S.desktop_dm_deal_memo_rejected,
        "deal_amendment_acknowledged" to S.dm_amend_acknowledged,
        "failed_to_acknowledge_amendment" to S.dm_amend_acknowledge_failed,
        "deal_rules_updated" to S.desktop_dm_pay_rules_updated_the_crew_member_has,
        "deal_no_rule_fields" to S.desktop_dm_there_was_nothing_to_amend_change_at,
        "failed_to_update_deal_rules" to S.desktop_dm_couldnt_update_the_pay_rules,
        "failed_to_update_nominal_codes" to S.desktop_dm_couldnt_update_the_nominal_codes,
        "failed_to_save_personal_details" to S.desktop_dm_couldnt_save_your_details,
        "failed_to_load_document_for_signing" to S.desktop_dm_couldnt_load_this_document_for_signing,
        "failed_to_sign_document" to S.desktop_dm_couldnt_sign_the_document,
        "failed_to_save_signature" to S.desktop_dm_couldnt_save_the_signature_for_next_time,
        "deal_resubmitted" to S.desktop_dm_deal_memo_sent_for_approval_again,
        "nominal_codes_updated" to S.dm_amend_nominals_saved,
        "personal_details_saved" to S.desktop_dm_your_details_were_saved,
        "template_saved" to S.dm_builder_saved,
        "template_updated" to S.desktop_dm_setup_updated,
        "template_save_failed" to S.desktop_dm_couldnt_save_the_setup,
        "template_load_failed" to S.desktop_dm_couldnt_load_the_setup,
        "template_deleted" to S.desktop_dm_setup_deleted,
        "template_delete_failed" to S.desktop_dm_couldnt_delete_the_setup,
        "deal_bank_account_not_updated" to S.desktop_dm_the_deal_was_saved_but_its_linked,
        "documents_uploaded" to S.desktop_dm_documents_uploaded,
        "document_upload_failed" to S.desktop_dm_couldnt_save_the_uploaded_documents,
        "document_update_failed" to S.dm_docs_update_failed,
        "document_delete_failed" to S.desktop_dm_couldnt_remove_the_document,
        "rates_refreshed" to S.dm_gpr_refreshed,
        "notice_template_saved" to S.desktop_dm_notice_template_saved_successfully,
        "failed_to_load_overview" to S.desktop_dm_couldnt_load_the_overview,
        "failed_to_load_approval_queue" to S.desktop_dm_couldnt_load_the_approval_queue,
        "failed_to_load_your_deal_memo" to S.desktop_dm_couldnt_load_your_deal_memo,
        "failed_to_load_deal_memo" to S.desktop_dm_couldnt_load_the_deal_memo,
        "export_failed" to S.asset_export_failed,
        "something_went_wrong" to S.something_went_wrong,
    )

    /** Hard-coded wording the web shows for codes that have no translation yet, as catalogue keys. */
    private val OVERRIDES: Map<String, String> = mapOf(
        "deal_reject_reason_required" to S.desktop_dm_add_a_reason_before_rejecting_this_deal,
        "deal_crew_cannot_reject" to S.desktop_dm_this_deal_can_no_longer_be_rejected,
        "deal_no_deal_for_user" to S.desktop_dm_we_couldnt_find_a_deal_memo_assigned,
        "deal_portal_deal_missing" to S.desktop_dm_this_deal_memo_link_is_no_longer,
        "deal_no_pending_amendment" to S.desktop_dm_there_is_no_pending_amendment_on_this,
        "deal_cannot_edit_cancelled" to S.desktop_dm_this_deal_memo_has_been_cancelled_and,
    )

    fun text(serverMessage: String?, fallbackKey: String): String {
        val key = serverMessage?.takeIf { it.isNotBlank() } ?: fallbackKey
        OVERRIDES[key]?.let { return str(it) }
        Labels.current.exact(key, LabelKind.Messages)?.let { return it }
        return ENGLISH[key]?.let { str(it) } ?: key.localisedMessage()
    }

    fun override(code: String): String? =
        OVERRIDES[code]?.let { str(it) }
            ?: ENGLISH[code]?.takeIf { Labels.current.exact(code, LabelKind.Messages) == null }?.let { str(it) }
}
