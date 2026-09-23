package com.zillit.desktop.feature.documentdistribution.domain

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Where a document can be published, and what each destination demands.
 *
 * Ported from the web's `PublishModal.jsx` tables, which are themselves a
 * mirror of what each receiving endpoint validates. The rules are not
 * decoration: the schedule and script endpoints reject a non-PDF, the page
 * endpoints reject a publish with no scene number, and the backend answers
 * `publication_mode_not_supported` if `mode` is sent to a destination that
 * does not republish. Getting one wrong produces a refusal the person
 * publishing cannot act on.
 */
data class PublishTarget(
    /** The category id the backend's `PUBLICATION_CATEGORIES` lists. */
    val category: String,
    private val labelKey: String,
    /** The film-tools rights row that gates it, or null when the home unit does. */
    val toolIdentifier: String? = null,
    /** The parent tool's name, when this destination sits under one. */
    private val groupKey: String? = null,
    /** PDF-only: the receiving endpoint validates `content_subtype`. */
    val pdfOnly: Boolean = false,
    /** One document per publish — a second would replace the first, not join it. */
    val singleFile: Boolean = false,
    /** Offers add / replace once something is already published there. */
    val republishable: Boolean = false,
    val needsScene: Boolean = false,
    val needsScheduleDate: Boolean = false,
    val needsScheduleType: Boolean = false,
    val needsName: Boolean = false,
    /** Mandatory on television projects only. */
    val needsEpisodeOnTelevision: Boolean = false,
) {
    val label: String get() = str(labelKey)

    /** The parent tool's name, when this destination sits under one. */
    val group: String? get() = groupKey?.let(::str)

    /** A note rides along except on the tool destinations, which have no field for it. */
    val takesNote: Boolean get() = !needsEpisodeOnTelevision

    /**
     * What stops this publish going, or null when it may.
     *
     * Checked in the order the person filling the form meets the fields, so
     * the first thing they can fix is the thing they are told about.
     */
    @Suppress("ReturnCount")
    fun problem(draft: PublishDraft, isTelevision: Boolean): String? {
        if (draft.documentIds.isEmpty()) return str(S.desktop_docdist_choose_at_least_one_document)
        if (singleFile && draft.documentIds.size > 1) {
            return str(S.desktop_docdist_takes_one_document_at_a_time, label)
        }
        return missingField(draft, isTelevision)
    }

    @Suppress("ReturnCount")
    private fun missingField(draft: PublishDraft, isTelevision: Boolean): String? {
        if (needsScene && draft.sceneNumber.isBlank()) return str(S.desktop_docdist_scene_number_required)
        if (needsScheduleType && draft.scheduleType.isBlank()) return str(S.desktop_docdist_choose_which_schedule)
        if (needsName && draft.name.isBlank()) return str(S.desktop_docdist_name_required)
        if (needsEpisodeOnTelevision && isTelevision && draft.episode.isBlank()) {
            return str(S.desktop_docdist_episode_required_television)
        }
        if (draft.mode == PublishMode.Replace && draft.replaceChatIds.isEmpty()) {
            return str(S.dd_publish_replace_target_placeholder)
        }
        return null
    }

    companion object {
        val CallSheet = PublishTarget(
            category = "call_sheet_unit",
            labelKey = S.cs_app_name,
            // Access comes from the home unit list, not film-tool rights.
            toolIdentifier = null,
            pdfOnly = true,
            republishable = true,
        )
        val ProductionReport = PublishTarget(
            category = "production_report",
            labelKey = S.dd_cat_report_label,
            toolIdentifier = "production_report_tool",
            pdfOnly = true,
            republishable = true,
        )
        val Info = PublishTarget(
            category = "info",
            labelKey = S.dd_cat_info_label,
            toolIdentifier = "info_tool",
        )
        val ConfidentialInfo = PublishTarget(
            category = "confidential_info",
            labelKey = S.dd_cat_confidential_label,
            toolIdentifier = "confidential_info_tool",
        )

        /**
         * D.O.D accumulates, so it stays multi-select where its siblings do
         * not — the other schedule destinations replace the project's record
         * on every upload, and a multi-file publish would collapse to the last.
         */
        val Dod = PublishTarget(
            category = "schedule_dod",
            labelKey = S.desktop_day_out_of_days,
            toolIdentifier = "dod_tool",
            groupKey = S.dd_pub_dest_dod_card,
            pdfOnly = true,
            needsName = true,
            needsEpisodeOnTelevision = true,
        )
        val ScheduleFull = PublishTarget(
            category = "schedule_full",
            labelKey = S.dd_pub_dest_schedule_full,
            toolIdentifier = "schedule_distribution_tool",
            groupKey = S.dd_pub_dest_schedule_card,
            pdfOnly = true,
            singleFile = true,
            needsScheduleDate = true,
            needsEpisodeOnTelevision = true,
        )
        val SchedulePages = PublishTarget(
            category = "schedule_page",
            labelKey = S.dd_pub_dest_schedule_pages,
            toolIdentifier = "schedule_distribution_tool",
            groupKey = S.dd_pub_dest_schedule_card,
            pdfOnly = true,
            singleFile = true,
            needsScene = true,
            needsScheduleType = true,
            needsEpisodeOnTelevision = true,
        )
        val ScheduleOneLine = PublishTarget(
            category = "schedule_oneline",
            labelKey = S.dd_pub_dest_schedule_oneline,
            toolIdentifier = "schedule_distribution_tool",
            groupKey = S.dd_pub_dest_schedule_card,
            pdfOnly = true,
            singleFile = true,
            needsScheduleDate = true,
            needsEpisodeOnTelevision = true,
        )
        val Script = PublishTarget(
            category = "script_distribution",
            labelKey = S.dd_pub_dest_script_option,
            toolIdentifier = "script_distribution_tool",
            groupKey = S.desktop_docdist_script_page_distribution,
            pdfOnly = true,
            singleFile = true,
            needsEpisodeOnTelevision = true,
        )
        val ScriptPages = PublishTarget(
            category = "page_distribution",
            labelKey = S.dd_pub_dest_page_option,
            toolIdentifier = "script_distribution_tool",
            groupKey = S.desktop_docdist_script_page_distribution,
            pdfOnly = true,
            singleFile = true,
            needsScene = true,
            needsEpisodeOnTelevision = true,
        )

        /** Every destination, in the web's card order. */
        val all: List<PublishTarget> = listOf(
            CallSheet, ProductionReport, Info, ConfidentialInfo,
            Dod, ScheduleFull, SchedulePages, ScheduleOneLine, Script, ScriptPages,
        )

        fun of(category: String): PublishTarget? = all.firstOrNull { it.category == category }
    }
}

/** How a republish lands: alongside what is there, or over it. */
enum class PublishMode(val wire: String, private val labelKey: String) {
    Add("addition", S.desktop_docdist_publish_mode_add),
    Replace("replace", S.replace),
    ;

    val label: String get() = str(labelKey)
}

/** Everything a publish carries beyond the category itself. */
data class PublishDraft(
    val documentIds: List<String> = emptyList(),
    val mode: PublishMode = PublishMode.Add,
    val replaceChatIds: List<String> = emptyList(),
    val note: String = "",
    val sceneNumber: String = "",
    val scheduleDate: Long? = null,
    val scheduleType: String = "",
    val episode: String = "",
    val name: String = "",
)

/** The two answers the Pages destination accepts for `schedule_type`. */
enum class ScheduleTypeChoice(val wire: String, private val labelKey: String) {
    Full("full_schedule_pages", S.dd_publish_schedule_type_full),
    OneLine("one_line_schedule_pages", S.dd_publish_schedule_type_one_line),
    ;

    val label: String get() = str(labelKey)
}
