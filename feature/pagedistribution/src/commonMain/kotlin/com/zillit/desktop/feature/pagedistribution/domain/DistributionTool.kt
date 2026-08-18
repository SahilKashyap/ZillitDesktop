package com.zillit.desktop.feature.pagedistribution.domain

import com.zillit.desktop.core.config.ZillitService

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
    val title: String,
    val service: ZillitService,
    /** The path segment under `/api/v2/` — `schedule-distribution` or `script-distribution`. */
    val segment: String,
    val tabs: List<DistributionTab>,
    /** The Document Distribution root folder, verbatim — shared with Android/iOS. */
    val publishRoot: String,
    /** The S3 key prefix, the web's `window.location.pathname` — kept so keys match. */
    val storagePath: String,
) {
    /** The page-number body key differs by service: `schedule_page_number` vs `script_page_number`. */
    val pageNumberKey: String
        get() = if (service == ZillitService.ScriptDistribution) "script_page_number" else "schedule_page_number"

    /** Only the schedule service reads `schedule_type`; the others never see it. */
    val sendsScheduleType: Boolean get() = service == ZillitService.ScheduleDistribution && toolIdentifier != "dod_tool"

    companion object {
        val ScheduleDistribution = DistributionTool(
            toolIdentifier = "schedule_distribution_tool",
            title = "Schedule Full & One Line",
            service = ZillitService.ScheduleDistribution,
            segment = "schedule-distribution",
            tabs = listOf(
                DistributionTab(
                    key = "full_script",
                    label = "Schedule Full",
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
                    label = "Pages",
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
                    label = "Schedule One Line",
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
            title = "Script & Pages Distribution",
            service = ZillitService.ScriptDistribution,
            segment = "script-distribution",
            tabs = listOf(
                DistributionTab(
                    key = "full_script",
                    label = "Full Script",
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
                    label = "Pages",
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
            title = "Schedule D.O.D",
            service = ZillitService.ScheduleDistribution,
            segment = "schedule-distribution",
            tabs = listOf(
                DistributionTab(
                    key = "dod",
                    label = "D.O.D",
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
    val label: String,
    val kind: TabKind,
    /** The `notification:read` module for this list. */
    val badgeModule: String,
    /** The read segment when the whole list is one segment; folder lists use the folder key. */
    val badgeSegment: String?,
    /** The Document Distribution sub-folder under the tool root; none for D.O.D. */
    val publishSubFolder: String?,
)

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
enum class ScheduleType(val wire: String, val label: String) {
    FullSchedulePages("full_schedule_pages", "Schedule pages"),
    OneLinePages("one_line_schedule_pages", "One line pages"),
    ;

    companion object {
        fun fromWire(value: String?): ScheduleType? = entries.firstOrNull { it.wire == value }
    }
}

/** The eleven revision colours, value = hex WITH `#`, in the web's order. */
enum class PageColour(val hex: String, val label: String) {
    White("#FFFFFF", "White"),
    Blue("#ADD8E6", "Blue"),
    Pink("#FFB6C1", "Pink"),
    Yellow("#FFFFE0", "Yellow"),
    Green("#98FB98", "Green"),
    Goldenrod("#DAA520", "Goldenrod"),
    Buff("#F0DC82", "Buff"),
    Salmon("#FA8072", "Salmon"),
    Cherry("#FADADD", "Cherry"),
    Tan("#D2B48C", "Tan"),
    Ivory("#FFFFF0", "Ivory"),
    ;

    companion object {
        fun fromHex(value: String?): PageColour? =
            entries.firstOrNull { it.hex.equals(value?.trim(), ignoreCase = true) }
    }
}
