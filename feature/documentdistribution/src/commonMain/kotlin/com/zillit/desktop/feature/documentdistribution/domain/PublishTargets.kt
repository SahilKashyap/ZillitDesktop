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
 * `publication_mode_not_supported` if `mode` reaches a destination that does
 * not republish. Getting one wrong produces a refusal the person publishing
 * cannot act on.
 */
data class PublishTarget(
    /** The category id the backend's `PUBLICATION_CATEGORIES` lists. */
    val category: String,
    private val labelKey: String,
    /** The film-tools rights row that gates it, or null when the home unit does. */
    val toolIdentifier: String? = null,
    /** The parent tool's name, when this destination sits under one. */
    private val groupKey: String? = null,
    /** The card's one-line sub-copy, where the web gives one. */
    private val descriptionKey: String? = null,
    /** PDF-only: the receiving endpoint validates `content_subtype`. */
    val pdfOnly: Boolean = false,
    /** One document per publish — a second would replace the first, not join it. */
    val singleFile: Boolean = false,
    /** Offers addition / replace / resend once something is already published there. */
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

    /** The card's sub-copy, or null where the web shows none. */
    val description: String? get() = descriptionKey?.let(::str)

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
        if (draft.mode == PublishMode.Replace) {
            if (draft.replaceChatId.isNullOrBlank()) return str(S.dd_publish_replace_target_placeholder)
            // One live file for one new one; the backend refuses the rest with
            // `publication_replace_single_only`.
            if (draft.documentIds.size != 1) return str(S.dd_publish_replace_single_hint)
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
            // It publishes into Production Report Chat, its own tool since Oct
            // 2026, so it needs that tool's rights (web `PublishModal` CATEGORIES).
            toolIdentifier = "production_report_chat_tool",
            pdfOnly = true,
            republishable = true,
        )
        val Info = PublishTarget(
            category = "info",
            labelKey = S.dd_cat_info_label,
            toolIdentifier = "info_tool",
            descriptionKey = S.dd_cat_info_sub,
        )
        val ConfidentialInfo = PublishTarget(
            category = "confidential_info",
            labelKey = S.dd_cat_confidential_label,
            toolIdentifier = "confidential_info_tool",
            descriptionKey = S.dd_cat_confidential_sub,
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

/**
 * One tile in the dialog's left rail.
 *
 * The web draws seven: four destinations that select directly, and three
 * *tools* whose tile opens a menu of the destinations beneath it (the
 * "Forward in App" pattern). A tool with a single destination — D.O.D today —
 * selects directly like a plain card, because a one-entry menu is a worse
 * version of a button.
 *
 * Built from [PublishTarget.all] rather than listed again, so a destination
 * added there cannot be missing from the rail: the grouped ones gather under
 * their [PublishTarget.group], the ungrouped ones each get a tile of their own.
 */
data class PublishCard(
    /** Stable across recompositions and language switches: the first option's category. */
    val key: String,
    val label: String,
    val description: String?,
    val options: List<PublishTarget>,
) {
    /** Selects on click rather than opening a menu. */
    val single: Boolean get() = options.size == 1

    /** Every option shares one rights row, so the tile's access is the first option's. */
    val category: String get() = options.first().category

    fun holds(category: String?): Boolean = options.any { it.category == category }

    companion object {
        val all: List<PublishCard> by lazy {
            val cards = mutableListOf<PublishCard>()
            PublishTarget.all.forEach { target ->
                val group = target.group
                if (group == null) {
                    cards += PublishCard(target.category, target.label, target.description, listOf(target))
                    return@forEach
                }
                val existing = cards.indexOfFirst { it.label == group }
                if (existing >= 0) {
                    cards[existing] = cards[existing].copy(options = cards[existing].options + target)
                } else {
                    cards += PublishCard(target.category, group, null, listOf(target))
                }
            }
            cards.toList()
        }
    }
}

/**
 * How a republish lands.
 *
 * Only sent where the destination republishes *and* something is already
 * live there — [PublishDraft.mode] is null on a first publish, which is what
 * keeps `mode` out of a body the backend would refuse.
 */
enum class PublishMode(val wire: String, private val labelKey: String, private val hintKey: String) {
    /** Join what is there. Nothing moves to History. */
    Add(
        "addition",
        S.dd_publish_option_addition,
        S.dd_publish_option_addition_hint,
    ),

    /** Swap one named live file for one new one. */
    Replace(
        "replace",
        S.dd_publish_option_file_names,
        S.dd_publish_option_file_names_hint,
    ),

    /** Everything live moves to History and the new file takes its place. */
    Resend(
        "resend",
        S.dd_publish_option_resend,
        S.dd_publish_option_resend_hint,
    ),
    ;

    /** Named with the destination, because "replace on the Call Sheet" is the real decision. */
    fun label(module: String): String = str(labelKey, module)

    fun hint(module: String): String = str(hintKey, module)
}

/** Everything a publish carries beyond the category itself. */
data class PublishDraft(
    val documentIds: List<String> = emptyList(),
    /** The folder the publish came from, when it did; the web sends `folder_id` either way. */
    val folderId: String? = null,
    /**
     * Null on a first publish — the destination has nothing live, so no `mode`
     * goes out at all. Set the moment the published-files read comes back
     * non-empty.
     */
    val mode: PublishMode? = null,
    /** One live file, not a set: a replacement is one-for-one on the wire. */
    val replaceChatId: String? = null,
    /** Optional per-file display name, document id → name. The two republishing destinations only. */
    val displayNames: Map<String, String> = emptyMap(),
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
