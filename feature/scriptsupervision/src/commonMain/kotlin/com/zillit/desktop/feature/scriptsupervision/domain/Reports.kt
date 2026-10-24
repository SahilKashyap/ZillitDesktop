package com.zillit.desktop.feature.scriptsupervision.domain

/**
 * The reports group's lists and rules — the web's `screens/reports`
 * `auditModules.js` and the export screen's own tables.
 *
 * The export lists carry the service's `Content-Disposition` file names,
 * because a reader who downloads `scenes_export.csv` on the web and
 * `scenes.csv` here has two files and no way to tell them apart.
 */
object Reports {

    /** A file the viewer saves: the route's kind, its format, and its name. */
    data class Export(
        val kind: String,
        val format: String,
        val fileName: String,
        /** A PDF the service renders, which is opened rather than saved. */
        val isReport: Boolean = false,
        /** The one the web draws as the primary action: the editor's ALE. */
        val highlight: Boolean = false,
    ) {
        /** The module text key for this export's name — the web's `keyOf`. */
        val labelKey: String get() = "export_" + kind.replace('-', '_')
    }

    /** `GET /export/{kind}/{projectId}` — files the viewer saves. */
    val DATA_EXPORTS: List<Export> = listOf(
        Export("scenes", "CSV", "scenes_export.csv"),
        Export("takes", "CSV", "takes_export.csv"),
        Export("editor-log", "CSV", "editor_log_export.csv"),
        Export("matching", "CSV", "matching_notes_export.csv"),
        // The ALE is the one the cutting room asks for, so the web makes it
        // the primary button.
        Export("ale", "ALE", "takes_export.ale", highlight = true),
    )

    /** Costume, hair and make-up: a CSV to save, and two PDFs to read. */
    val DEPARTMENT_EXPORTS: List<Export> = listOf(
        Export("garments", "CSV", "garments_export.csv"),
        Export("garment-list", "PDF", "garment_list.pdf", isReport = true),
        Export("product-info", "PDF", "hair_makeup_products.pdf", isReport = true),
    )

    /**
     * `GET /reports/{kind}/{projectId}/{shootingDay}` — a PDF for one day.
     *
     * The day is part of the path, and the file name gets it appended, so two
     * days' reports do not overwrite each other in the viewer's downloads.
     */
    val DAY_REPORTS: List<Export> = listOf(
        Export("daily-progress", "PDF", "daily_progress_day", isReport = true),
        Export("editor-log", "PDF", "editor_log_day", isReport = true),
        Export("continuity-notes", "PDF", "continuity_notes_day", isReport = true),
        Export("selected-takes", "PDF", "selected_takes_day", isReport = true),
    )

    /** The module text key for a day report's name. */
    fun reportLabelKey(kind: String): String = "report_" + kind.replace('-', '_')

    /**
     * The record kinds the service writes to the audit trail.
     *
     * ⚠ Scenes and takes are NOT audited, which is worth knowing before
     * looking for a scene's history and concluding the trail is broken.
     */
    val AUDIT_MODULES: List<String> = listOf(
        "timing_log",
        "camera_detail",
        "dialogue_note",
        "action_continuity",
        "props_wardrobe",
        "matching_note",
        "editor_note",
        "weather_lighting",
        "script_coverage",
        "lined_script",
        "facing_page",
        "editor_daily_log",
        "sound_report",
        "script_revision",
        "stunt_safety",
        "vfx_plate_log",
        "breakdown_category",
        "breakdown_element",
        "breakdown_tag",
        "template",
        "character",
        "actor",
        "story_day",
        "look",
        "garment",
        "prop",
        "box",
        "scene_asset",
        "scene_note",
    )

    val AUDIT_ACTIONS: List<String> = listOf("create", "update", "delete", "restore")

    /**
     * The kinds the service can actually restore.
     *
     * `auditService.restoreRecord` has a model for these and answers "Unknown
     * module" for anything else, so a row of another kind offers no Restore
     * rather than offering one that fails. Note it includes `scene` and
     * `take`, which are NOT in [AUDIT_MODULES] — the trail does not record
     * them, but it can put one back if something else did.
     */
    private val RESTORABLE: Set<String> = setOf(
        "scene",
        "take",
        "timing_log",
        "camera_detail",
        "dialogue_note",
        "action_continuity",
        "props_wardrobe",
        "matching_note",
        "editor_note",
        "weather_lighting",
        "script_coverage",
        "lined_script",
        "facing_page",
        "editor_daily_log",
        "sound_report",
        "script_revision",
        "stunt_safety",
        "vfx_plate_log",
    )

    /**
     * Whether this entry can be put back.
     *
     * BOTH conditions, as the web has them: the entry must carry the record's
     * previous state (there is nothing to restore from otherwise) AND its
     * kind must be one the service can rebuild.
     */
    fun canRestore(entry: Rec): Boolean =
        entry.has("previous_state") && entry.str("module") in RESTORABLE

    /** The changed fields of an entry, as `field: from → to` rows. */
    fun changes(entry: Rec): List<FieldChange> {
        val changes = entry.rec("changes") ?: return emptyList()
        return changes.keys.map { field ->
            val diff = changes.rec(field)
            FieldChange(
                field = field,
                from = diff?.str("from").orEmpty().ifBlank { EMPTY_VALUE },
                to = diff?.str("to").orEmpty().ifBlank { EMPTY_VALUE },
            )
        }
    }

    /** What an unset side of a change reads as — the web's en dash. */
    const val EMPTY_VALUE: String = "–"
}

/** One field an audit entry changed. */
data class FieldChange(val field: String, val from: String, val to: String)
