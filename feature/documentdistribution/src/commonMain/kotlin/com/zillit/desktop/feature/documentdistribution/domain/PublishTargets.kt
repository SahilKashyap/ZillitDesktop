package com.zillit.desktop.feature.documentdistribution.domain

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
    val label: String,
    /** The film-tools rights row that gates it, or null when the home unit does. */
    val toolIdentifier: String? = null,
    /** The parent tool's name, when this destination sits under one. */
    val group: String? = null,
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
        if (draft.documentIds.isEmpty()) return "Choose at least one document"
        if (singleFile && draft.documentIds.size > 1) {
            return "$label takes one document at a time"
        }
        return missingField(draft, isTelevision)
    }

    @Suppress("ReturnCount")
    private fun missingField(draft: PublishDraft, isTelevision: Boolean): String? {
        if (needsScene && draft.sceneNumber.isBlank()) return "A scene number is required"
        if (needsScheduleType && draft.scheduleType.isBlank()) return "Choose which schedule this is"
        if (needsName && draft.name.isBlank()) return "A name is required"
        if (needsEpisodeOnTelevision && isTelevision && draft.episode.isBlank()) {
            return "An episode is required on a television project"
        }
        if (draft.mode == PublishMode.Replace && draft.replaceChatIds.isEmpty()) {
            return "Pick the published file to replace"
        }
        return null
    }

    companion object {
        val CallSheet = PublishTarget(
            category = "call_sheet_unit",
            label = "Call Sheet",
            // Access comes from the home unit list, not film-tool rights.
            toolIdentifier = null,
            pdfOnly = true,
            republishable = true,
        )
        val ProductionReport = PublishTarget(
            category = "production_report",
            label = "Production Report",
            toolIdentifier = "production_report_tool",
            pdfOnly = true,
            republishable = true,
        )
        val Info = PublishTarget(
            category = "info",
            label = "Info",
            toolIdentifier = "info_tool",
        )
        val ConfidentialInfo = PublishTarget(
            category = "confidential_info",
            label = "Confidential Info",
            toolIdentifier = "confidential_info_tool",
        )

        /**
         * D.O.D accumulates, so it stays multi-select where its siblings do
         * not — the other schedule destinations replace the project's record
         * on every upload, and a multi-file publish would collapse to the last.
         */
        val Dod = PublishTarget(
            category = "schedule_dod",
            label = "Day Out of Days",
            toolIdentifier = "dod_tool",
            group = "Schedule D.O.D",
            pdfOnly = true,
            needsName = true,
            needsEpisodeOnTelevision = true,
        )
        val ScheduleFull = PublishTarget(
            category = "schedule_full",
            label = "Schedule Full",
            toolIdentifier = "schedule_distribution_tool",
            group = "Schedule Full & One Line",
            pdfOnly = true,
            singleFile = true,
            needsScheduleDate = true,
            needsEpisodeOnTelevision = true,
        )
        val SchedulePages = PublishTarget(
            category = "schedule_page",
            label = "Pages",
            toolIdentifier = "schedule_distribution_tool",
            group = "Schedule Full & One Line",
            pdfOnly = true,
            singleFile = true,
            needsScene = true,
            needsScheduleType = true,
            needsEpisodeOnTelevision = true,
        )
        val ScheduleOneLine = PublishTarget(
            category = "schedule_oneline",
            label = "Schedule One Line",
            toolIdentifier = "schedule_distribution_tool",
            group = "Schedule Full & One Line",
            pdfOnly = true,
            singleFile = true,
            needsScheduleDate = true,
            needsEpisodeOnTelevision = true,
        )
        val Script = PublishTarget(
            category = "script_distribution",
            label = "Script",
            toolIdentifier = "script_distribution_tool",
            group = "Script & Page Distribution",
            pdfOnly = true,
            singleFile = true,
            needsEpisodeOnTelevision = true,
        )
        val ScriptPages = PublishTarget(
            category = "page_distribution",
            label = "Page",
            toolIdentifier = "script_distribution_tool",
            group = "Script & Page Distribution",
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
enum class PublishMode(val wire: String, val label: String) {
    Add("addition", "Add alongside"),
    Replace("replace", "Replace"),
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
enum class ScheduleTypeChoice(val wire: String, val label: String) {
    Full("full_schedule_pages", "Full Schedule Pages"),
    OneLine("one_line_schedule_pages", "One Line Schedule Pages"),
}
