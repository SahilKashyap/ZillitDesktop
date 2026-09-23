package com.zillit.desktop.feature.pagedistribution.domain

import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Three film tools, one engine.
 *
 * Schedule Full & One Line, Script & Pages Distribution and Schedule D.O.D
 * are — on the web — the same 2,000-line component mounted three times over
 * different REST segments: PDFs go up, sit in a flat list (a "single"
 * document that gets *replaced*) or in folders (pages by scene, D.O.D by
 * name), and come back down through view / download / counts / publish.
 * A [DistributionTool] names the tool; a [DistributionTab] names one of its
 * lists and how that list talks to the service. Everything else is shared.
 */
data class DistributionTool(
    /** The permission-grid identifier — `schedule_distribution_tool`… */
    val toolIdentifier: String,
    /** The catalogue key for the tool's on-screen name. */
    val titleKey: String,
    val service: ZillitService,
    /** The path segment under `/api/v2/` — `schedule-distribution` or `script-distribution`. */
    val segment: String,
    val tabs: List<DistributionTab>,
    /** The Document Distribution root folder, verbatim — shared with Android/iOS. */
    val publishRoot: String,
    /** The S3 key prefix, the web's `window.location.pathname` — kept so keys match. */
    val storagePath: String,
) {
    /** The tool's name as the crew reads it. */
    val title: String get() = str(titleKey)

    /** The page-number body key differs by service: `schedule_page_number` vs `script_page_number`. */
    val pageNumberKey: String
        get() = if (service == ZillitService.ScriptDistribution) "script_page_number" else "schedule_page_number"

    /** Only the schedule service reads `schedule_type`; the others never see it. */
    val sendsScheduleType: Boolean get() = service == ZillitService.ScheduleDistribution && toolIdentifier != "dod_tool"

    companion object {
        val ScheduleDistribution = DistributionTool(
            toolIdentifier = "schedule_distribution_tool",
            titleKey = S.dd_pub_dest_schedule_card,
            service = ZillitService.ScheduleDistribution,
            segment = "schedule-distribution",
            tabs = listOf(
                DistributionTab(
                    key = "full_script",
                    labelKey = S.full_schedule,
                    kind = TabKind.Single(
                        route = "schedule",
                        idQuery = "scheduleId",
                        parentKey = "parent_schedule_id",
                        dateKey = "schedule_date",
                        nameKey = "schedule_name",
                        countType = "schedule",
                    ),
                    badgeModule = "schedule_distribution_label",
                    badgeSegment = "schedule_distribution_label",
                    publishSubFolder = "Schedule Full",
                ),
                DistributionTab(
                    key = "page",
                    labelKey = S.pages,
                    kind = TabKind.Folders(
                        folderRoute = "page-folders",
                        itemRoute = "page",
                        folderKey = FolderKey.SceneNumber,
                        countType = "page",
                        scheduleTypeChoice = true,
                    ),
                    badgeModule = "schedule_distribution_pages_tool_label",
                    badgeSegment = null,
                    publishSubFolder = "Pages",
                ),
                DistributionTab(
                    key = "oneline",
                    labelKey = S.one_line_title,
                    kind = TabKind.Single(
                        route = "oneline",
                        idQuery = "scheduleId",
                        parentKey = "parent_schedule_id",
                        dateKey = "schedule_date",
                        nameKey = "schedule_name",
                        countType = "oneline",
                    ),
                    badgeModule = "schedule_oneline_label",
                    badgeSegment = "schedule_oneline_label",
                    publishSubFolder = "Schedule One Line",
                ),
            ),
            publishRoot = "Schedule Full & One Line",
            storagePath = "/film-tools/schedule-distribution",
        )

        val ScriptDistribution = DistributionTool(
            toolIdentifier = "script_distribution_tool",
            titleKey = S.dd_pub_dest_script_card,
            service = ZillitService.ScriptDistribution,
            segment = "script-distribution",
            tabs = listOf(
                DistributionTab(
                    key = "full_script",
                    labelKey = S.full_script,
                    kind = TabKind.Single(
                        route = "script",
                        idQuery = "scriptId",
                        parentKey = "parent_script_id",
                        dateKey = "script_date",
                        nameKey = "script_name",
                        countType = "script",
                    ),
                    badgeModule = "script_distribution_label",
                    // The wire's own spelling — `distibution` — is what the badge is keyed by.
                    badgeSegment = "script_distibution_script_label",
                    publishSubFolder = "Full Script",
                ),
                DistributionTab(
                    key = "page",
                    labelKey = S.pages,
                    kind = TabKind.Folders(
                        folderRoute = "page-folders",
                        itemRoute = "page",
                        folderKey = FolderKey.SceneNumber,
                        countType = "page",
                        scheduleTypeChoice = false,
                    ),
                    badgeModule = "script_distribution_pages_tool_label",
                    badgeSegment = null,
                    publishSubFolder = "Pages",
                ),
            ),
            publishRoot = "Script & Page Distribution",
            storagePath = "/film-tools/script-distribution",
        )

        /**
         * Schedule D.O.D — despite the name, not a day-out-of-days grid: PDFs
         * filed into NAMED folders on the schedule-distribution service.
         */
        val ScheduleDod = DistributionTool(
            toolIdentifier = "dod_tool",
            titleKey = S.dd_pub_dest_dod_card,
            service = ZillitService.ScheduleDistribution,
            segment = "schedule-distribution",
            tabs = listOf(
                DistributionTab(
                    key = "dod",
                    labelKey = S.desktop_dod_tab_label,
                    kind = TabKind.Folders(
                        folderRoute = "dod-folders",
                        itemRoute = "dod",
                        folderKey = FolderKey.Name,
                        countType = "dod",
                        scheduleTypeChoice = false,
                        canMove = true,
                    ),
                    badgeModule = "dod_label",
                    badgeSegment = null,
                    publishSubFolder = null,
                ),
            ),
            publishRoot = "Schedule D.O.D",
            storagePath = "/film-tools/dod",
        )
    }
}

data class DistributionTab(
    val key: String,
    /** The catalogue key for the tab's on-screen name. */
    val labelKey: String,
    val kind: TabKind,
    /** The `notification:read` module for this list. */
    val badgeModule: String,
    /** The read segment when the whole list is one segment; folder lists use the folder key. */
    val badgeSegment: String?,
    /** The Document Distribution sub-folder under the tool root; none for D.O.D. */
    val publishSubFolder: String?,
) {
    /** The tab's name as the crew reads it. */
    val label: String get() = str(labelKey)
}

/** How one list talks to the service. */
sealed interface TabKind {
    /** The route documents are read/written on — `schedule`, `page`, `dod`. */
    val itemRoute: String

    /** The `{type}/count/{id}` segment. */
    val countType: String

    /**
     * ONE current document, replaced rather than added to: `schedule`,
     * `oneline`, `script`. Old versions carry `replaced:1`; history reads
     * them back with `?replaced=`.
     */
    data class Single(
        val route: String,
        /** The single-read query key — `scheduleId` / `scriptId`. */
        val idQuery: String,
        /** The replace pointer — `parent_schedule_id` / `parent_script_id`. */
        val parentKey: String,
        /** The document's own date key — `schedule_date` / `script_date`. */
        val dateKey: String,
        /** The optional free-text name — `schedule_name` / `script_name`. */
        val nameKey: String,
        override val countType: String,
    ) : TabKind {
        override val itemRoute: String get() = route
    }

    /**
     * Folders of documents: pages grouped by scene number, D.O.D grouped by
     * a typed name. Deleted rows carry `deleted:1`; history reads them back
     * with `?deleted=`.
     */
    data class Folders(
        val folderRoute: String,
        override val itemRoute: String,
        val folderKey: FolderKey,
        override val countType: String,
        /** Schedule pages ask "full schedule pages or one-line pages?" — a folder-splitting choice. */
        val scheduleTypeChoice: Boolean,
        /** D.O.D documents can be moved between named folders. */
        val canMove: Boolean = false,
    ) : TabKind
}

/** What a folder is keyed by on the wire. */
enum class FolderKey(val query: String, val bodyKey: String) {
    /** `?sceneNumber=` on read; `scene_number` in the upload. */
    SceneNumber("sceneNumber", "scene_number"),
    /** `?name=` on read; `name` in the upload and the move. */
    Name("name", "name"),
}

/** The two schedule-page kinds; the web keeps them in separate folders by shifting the revision date. */
enum class ScheduleType(val wire: String, private val labelKey: String) {
    FullSchedulePages("full_schedule_pages", S.schedule_pages_2),
    OneLinePages("one_line_schedule_pages", S.desktop_one_line_pages_label),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun fromWire(value: String?): ScheduleType? = entries.firstOrNull { it.wire == value }
    }
}

/** The eleven revision colours, value = hex WITH `#`, in the web's order. */
enum class PageColour(val hex: String, private val labelKey: String) {
    White("#FFFFFF", S.color_white),
    Blue("#ADD8E6", S.color_blue),
    Pink("#FFB6C1", S.color_pink),
    Yellow("#FFFFE0", S.color_yellow),
    Green("#98FB98", S.color_green),
    Goldenrod("#DAA520", S.color_goldenrod),
    Buff("#F0DC82", S.color_buff),
    Salmon("#FA8072", S.color_salmon),
    Cherry("#FADADD", S.color_cherry),
    Tan("#D2B48C", S.color_tan),
    Ivory("#FFFFF0", S.color_ivory),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun fromHex(value: String?): PageColour? =
            entries.firstOrNull { it.hex.equals(value?.trim(), ignoreCase = true) }
    }
}
