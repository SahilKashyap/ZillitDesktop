package com.zillit.desktop.feature.scriptsupervision.ui.screens

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.ui.t

/**
 * The twelve continuity logs, each as its spec — the web's `shoot/` and
 * `takeLogs/` screens.
 *
 * Every list, every column and every option set is the SERVICE's own: a value
 * outside its list is refused on a write, so these are transcribed from the
 * web's screens rather than renamed. What the reader sees is the label beside
 * each value, and a stored value the list does not hold still shows as stored
 * — an older client, or the service's AI, may have written one.
 */

/** `value to label` options from the service's own slugs, labelled `<prefix>_<slug>`. */
private fun options(values: List<String>, prefix: String): List<Pair<String, String>> =
    values.map { it to t("${prefix}_$it") }

/** Options whose stored value IS the word (INT/EXT, a camera letter). */
private fun plain(values: List<String>): List<Pair<String, String>> = values.map { it to it }

private val CELL = Modifier.width(240.dp)
private val HALF = Modifier.width(140.dp)

// ---------------------------------------------------------------- vocabulary

/** Shot sizes in frame order; the acronym is what the crew says, its name beside it. */
private val SHOT_SIZES = listOf(
    "EWS" to "ews", "WS" to "ws", "MWS" to "mws", "MS" to "ms", "MCU" to "mcu",
    "CU" to "cu", "BCU" to "bcu", "ECU" to "ecu", "OTS" to "ots", "POV" to "pov",
    "2-Shot" to "two_shot", "Group" to "group", "Insert" to "insert", "Cutaway" to "cutaway",
    "Establishing" to "establishing", "Aerial" to "aerial",
)

private val CAMERA_ANGLES = listOf(
    "Eye Level" to "eye_level", "High Angle" to "high", "Low Angle" to "low", "Dutch" to "dutch",
    "Bird's Eye" to "birds_eye", "Worm's Eye" to "worms_eye", "Frontal" to "frontal",
    "3/4 Left" to "three_quarter_left", "3/4 Right" to "three_quarter_right", "Profile" to "profile",
)

private val CAMERA_MOVEMENTS = listOf(
    "Static" to "static", "Pan" to "pan", "Tilt" to "tilt", "Dolly" to "dolly", "Truck" to "truck",
    "Crane" to "crane", "Steadicam" to "steadicam", "Handheld" to "handheld", "Slider" to "slider",
    "Drone" to "drone", "Push In" to "push_in", "Pull Out" to "pull_out", "Zoom" to "zoom",
    "Roving" to "roving", "Tracking" to "tracking", "Whip Pan" to "whip_pan",
)

/** Arrows are drawn, not words: those two need no translation. */
private val SCREEN_DIRECTIONS = listOf(
    "L→R" to null, "R→L" to null, "Toward Camera" to "toward_camera",
    "Away From Camera" to "away_from_camera", "N/A" to "not_applicable",
)

private val FILTERS = listOf(
    "Clear" to "clear", "ND 0.3" to "nd_03", "ND 0.6" to "nd_06", "ND 0.9" to "nd_09",
    "ND 1.2" to "nd_12", "ND 1.5" to "nd_15", "ND 1.8" to "nd_18", "ND 2.1" to "nd_21",
    "Polarizer" to "polarizer", "UV" to null, "Black Pro-Mist 1/8" to null,
    "Black Pro-Mist 1/4" to null, "Black Pro-Mist 1/2" to null,
)

private val LENSES = listOf(
    12, 14, 16, 18, 21, 24, 25, 27, 28, 32, 35, 40, 45, 50, 55, 65, 75, 85, 100, 135, 150, 200, 300,
)

private val CAMERAS = listOf("A", "B", "C", "D", "E", "F")

/** `value to slug` pairs as options; a null slug is shown as the value itself. */
private fun vocabulary(list: List<Pair<String, String?>>, prefix: String): List<Pair<String, String>> =
    list.map { (value, slug) ->
        value to (slug?.let { t("${prefix}_$it") } ?: value.replace("→", " → "))
    }

// ------------------------------------------------------------------- screens

/** Camera details: what the camera did on a take. */
@Composable
internal fun CameraDetailsScreen() = ContinuityLogScreen(
    LogSpec(
        folder = "camera-details",
        titleKey = "camera_details",
        addKey = "add_camera_detail",
        editKey = "edit_camera_detail",
        emptyKey = "no_camera_details",
        deleteTitleKey = "delete_camera_detail_title",
        columns = listOf(
            LogColumn("scene_id", "scene", ColumnWidth.Fixed(100.dp), CellKind.Scene),
            LogColumn("camera", "camera", ColumnWidth.Fixed(80.dp)),
            LogColumn("lens_mm", "lens_mm", ColumnWidth.Fixed(90.dp), CellKind.Number),
            LogColumn("shot_size", "shot_size", ColumnWidth.Fixed(110.dp), CellKind.Pick, shotSizes()),
            LogColumn("camera_angle", "angle", ColumnWidth.Fixed(120.dp), CellKind.Pick, angles()),
            LogColumn("screen_direction", "screen_dir", ColumnWidth.Fixed(120.dp), CellKind.Pick, directions()),
            LogColumn("camera_movement", "movement", ColumnWidth.Fixed(120.dp), CellKind.Pick, movements()),
            LogColumn("filter", "filter", ColumnWidth.Fixed(110.dp), CellKind.Pick, filters()),
            LogColumn("notes", "notes"),
        ),
        fields = listOf(
            LogField("scene_id", "scene", FieldKind.Scene, CELL, required = true),
            LogField("camera", "camera", FieldKind.Pick, HALF, options = plain(CAMERAS)),
            LogField("lens_mm", "lens_mm", FieldKind.Pick, HALF, options = lensOptions()),
            LogField("shot_size", "shot_size", FieldKind.Pick, CELL, options = shotSizes()),
            LogField("camera_angle", "camera_angle", FieldKind.Pick, CELL, options = angles()),
            LogField("screen_direction", "screen_direction", FieldKind.Pick, CELL, options = directions()),
            LogField("camera_movement", "camera_movement", FieldKind.Pick, CELL, options = movements()),
            LogField("filter", "filter", FieldKind.Pick, CELL, options = filters()),
            LogField("notes", "notes", FieldKind.LongText),
        ),
        describe = { it.str("camera") },
    ),
)

/** Dialogue notes: what was written, what was said, and whether the change was approved. */
@Composable
internal fun DialogueNotesScreen() = ContinuityLogScreen(
    LogSpec(
        folder = "dialogue-notes",
        titleKey = "dialogue_notes",
        addKey = "add_dialogue_note",
        editKey = "edit_dialogue_note",
        emptyKey = "no_dialogue_notes",
        deleteTitleKey = "delete_dialogue_note_title",
        columns = listOf(
            LogColumn("scene_id", "scene", ColumnWidth.Fixed(100.dp), CellKind.Scene),
            LogColumn("character", "character", ColumnWidth.Fixed(140.dp)),
            LogColumn("script_line", "script_line"),
            LogColumn("actual_line", "actual_line"),
            LogColumn("is_ad_lib", "ad_lib", ColumnWidth.Fixed(110.dp), CellKind.Flag),
            LogColumn("line_change_approved", "approved", ColumnWidth.Fixed(110.dp), CellKind.Flag),
            LogColumn("notes", "notes"),
        ),
        fields = listOf(
            LogField("scene_id", "scene", FieldKind.Scene, CELL, required = true),
            LogField("character", "character", FieldKind.Text, CELL, required = true),
            LogField("script_line", "script_line", FieldKind.LongText),
            LogField("actual_line", "actual_line", FieldKind.LongText),
            LogField("is_ad_lib", "ad_lib", FieldKind.Switch),
            LogField("line_change_approved", "line_change_approved", FieldKind.Switch),
            LogField("notes", "notes", FieldKind.LongText),
        ),
        describe = { it.str("character") },
    ),
)

/**
 * Action continuity: the free-text sections the old page had, in its order.
 *
 * `continuity_errors` is one comma-separated line, as the web types it.
 */
@Composable
internal fun ActionContinuityScreen() = ContinuityLogScreen(
    LogSpec(
        folder = "action-continuity",
        titleKey = "action_continuity",
        addKey = "add_action_continuity",
        editKey = "edit_action_continuity",
        emptyKey = "no_action_continuity",
        deleteTitleKey = "delete_action_continuity_title",
        columns = listOf(
            LogColumn("scene_id", "scene", ColumnWidth.Fixed(100.dp), CellKind.Scene),
            LogColumn("character", "character", ColumnWidth.Fixed(140.dp)),
            LogColumn("description", "description"),
            LogColumn("hand_props", "hand_props"),
            LogColumn("body_position", "body_position"),
            LogColumn("eyeline", "eyeline"),
        ),
        fields = listOf(
            LogField("scene_id", "scene", FieldKind.Scene, CELL, required = true),
            LogField("character", "character", FieldKind.Text, CELL),
            LogField("description", "description", FieldKind.LongText),
            LogField("hand_props", "hand_props", FieldKind.LongText),
            LogField("body_position", "body_position", FieldKind.LongText),
            LogField("eyeline", "eyeline", FieldKind.LongText),
            LogField("movement_notes", "movement_notes", FieldKind.LongText),
            LogField("continuity_errors", "continuity_errors_comma", FieldKind.LongText),
        ),
        describe = { it.str("character") },
    ),
)

/** Matching notes: what has to match between shots, and whether it was resolved. */
@Composable
internal fun MatchingNotesScreen() = ContinuityLogScreen(
    LogSpec(
        folder = "matching-notes",
        titleKey = "matching_notes",
        addKey = "add_matching_note",
        editKey = "edit_matching_note",
        emptyKey = "no_matching_notes",
        deleteTitleKey = "delete_matching_note_title",
        columns = listOf(
            LogColumn("category", "category", ColumnWidth.Fixed(160.dp), CellKind.Pick, matchCategories()),
            LogColumn("description", "description"),
            LogColumn("priority", "priority", ColumnWidth.Fixed(110.dp), CellKind.Pick, priorities()),
            LogColumn("resolved", "resolved", ColumnWidth.Fixed(110.dp), CellKind.Flag),
        ),
        fields = listOf(
            LogField("category", "category", FieldKind.Pick, CELL, required = true, options = matchCategories()),
            LogField("description", "description", FieldKind.LongText),
            LogField("priority", "priority", FieldKind.Pick, HALF, options = priorities()),
            LogField("resolved", "resolved", FieldKind.Switch),
        ),
        describe = { it.str("description") },
    ),
)

/** Sound reports: the roll, the track and how it sounded. */
@Composable
internal fun SoundReportsScreen() = ContinuityLogScreen(
    LogSpec(
        folder = "sound-reports",
        titleKey = "sound_reports",
        addKey = "add_sound_report",
        editKey = "edit_sound_report",
        emptyKey = "no_sound_reports",
        deleteTitleKey = "delete_sound_report_title",
        columns = listOf(
            LogColumn("scene_id", "scene", ColumnWidth.Fixed(100.dp), CellKind.Scene),
            LogColumn("sound_roll", "sound_roll", ColumnWidth.Fixed(110.dp)),
            LogColumn("track_number", "track", ColumnWidth.Fixed(80.dp), CellKind.Number),
            LogColumn("sound_timecode", "timecode", ColumnWidth.Fixed(130.dp)),
            LogColumn("sample_rate", "sample_rate", ColumnWidth.Fixed(110.dp)),
            LogColumn("channels", "channels", ColumnWidth.Fixed(90.dp), CellKind.Number),
            LogColumn("mic_setup", "mic_setup"),
            LogColumn("quality_rating", "quality", ColumnWidth.Fixed(120.dp), CellKind.Pick, qualities()),
            LogColumn("adr_needed", "adr_needed", ColumnWidth.Fixed(100.dp), CellKind.Flag),
            LogColumn("notes", "notes"),
        ),
        fields = listOf(
            LogField("scene_id", "scene", FieldKind.Scene, CELL, required = true),
            LogField("sound_roll", "sound_roll", FieldKind.Text, HALF, required = true),
            LogField("track_number", "track", FieldKind.Number, HALF),
            LogField("sound_timecode", "timecode", FieldKind.Text, HALF, placeholder = "timecode_hint"),
            LogField("sample_rate", "sample_rate", FieldKind.Text, HALF, placeholder = "sample_rate_hint"),
            LogField("channels", "channels", FieldKind.Number, HALF),
            LogField("mic_setup", "mic_setup", FieldKind.Text, CELL, placeholder = "mic_setup_hint"),
            LogField("quality_rating", "quality", FieldKind.Pick, CELL, options = qualities()),
            LogField("adr_needed", "adr_needed", FieldKind.Switch),
            LogField("notes", "notes", FieldKind.LongText),
        ),
        describe = { it.str("sound_roll") },
    ),
)

/** Weather and lighting, as the day actually was. */
@Composable
internal fun WeatherLightingScreen() = ContinuityLogScreen(
    LogSpec(
        folder = "weather-lighting",
        titleKey = "weather_lighting",
        addKey = "add_weather",
        editKey = "edit_weather",
        emptyKey = "no_weather",
        deleteTitleKey = "delete_weather_title",
        columns = listOf(
            LogColumn("scene_id", "scene", ColumnWidth.Fixed(100.dp), CellKind.Scene),
            LogColumn("story_time_of_day", "story_time_of_day", ColumnWidth.Fixed(150.dp)),
            LogColumn("actual_time_of_day", "actual_time_of_day", ColumnWidth.Fixed(150.dp)),
            LogColumn("interior_exterior", "int_ext", ColumnWidth.Fixed(100.dp)),
            LogColumn("weather_actual", "weather_actual", ColumnWidth.Fixed(150.dp)),
            LogColumn("natural_light", "natural_light", ColumnWidth.Fixed(130.dp)),
            LogColumn("color_temp", "color_temp", ColumnWidth.Fixed(120.dp)),
            LogColumn("notes", "notes"),
        ),
        fields = listOf(
            LogField("scene_id", "scene", FieldKind.Scene, CELL, required = true),
            LogField("story_time_of_day", "story_time_of_day", FieldKind.Text, CELL),
            LogField("actual_time_of_day", "actual_time_of_day", FieldKind.Text, CELL),
            // The service's list for THIS record has no EXT/INT, unlike a scene's.
            LogField("interior_exterior", "int_ext", FieldKind.Pick, HALF, options = plain(WEATHER_INT_EXT)),
            LogField("weather_actual", "weather_actual", FieldKind.Text, CELL),
            LogField("lighting_setup", "lighting_setup", FieldKind.LongText),
            LogField("natural_light", "natural_light", FieldKind.Text, CELL),
            LogField("color_temp", "color_temp", FieldKind.Text, HALF),
            LogField("notes", "notes", FieldKind.LongText),
        ),
        describe = { it.str("weather_actual") },
    ),
)

/** VFX plates: what was shot for the vendor, and where it stands. */
@Composable
internal fun VfxPlatesScreen() = ContinuityLogScreen(
    LogSpec(
        folder = "vfx-plates",
        titleKey = "vfx_plates",
        addKey = "add_vfx_plate",
        editKey = "edit_vfx_plate",
        emptyKey = "no_vfx_plates",
        deleteTitleKey = "delete_vfx_plate_title",
        columns = listOf(
            LogColumn("scene_id", "scene", ColumnWidth.Fixed(100.dp), CellKind.Scene),
            LogColumn("plate_type", "plate_type", ColumnWidth.Fixed(160.dp), CellKind.Pick, plateTypes()),
            LogColumn("plate_number", "plate_number", ColumnWidth.Fixed(120.dp)),
            LogColumn("vfx_shot_id", "vfx_shot_id", ColumnWidth.Fixed(130.dp)),
            LogColumn("camera", "camera", ColumnWidth.Fixed(80.dp)),
            LogColumn("lens_mm", "lens_mm", ColumnWidth.Fixed(90.dp), CellKind.Number),
            LogColumn("frame_rate", "fps", ColumnWidth.Fixed(80.dp), CellKind.Number),
            LogColumn("vendor", "vendor", ColumnWidth.Fixed(140.dp)),
            LogColumn("priority", "priority", ColumnWidth.Fixed(110.dp), CellKind.Pick, platePriorities()),
            LogColumn("status", "status", ColumnWidth.Fixed(130.dp), CellKind.Pick, plateStatuses()),
        ),
        fields = listOf(
            LogField("scene_id", "scene", FieldKind.Scene, CELL, required = true),
            LogField("plate_type", "plate_type", FieldKind.Pick, CELL, required = true, options = plateTypes()),
            LogField("plate_number", "plate_number", FieldKind.Text, HALF),
            LogField("vfx_shot_id", "vfx_shot_id", FieldKind.Text, CELL),
            LogField("camera", "camera", FieldKind.Pick, HALF, options = plain(CAMERAS)),
            LogField("lens_mm", "lens_mm", FieldKind.Number, HALF),
            LogField("frame_rate", "fps", FieldKind.Number, HALF),
            LogField("resolution", "resolution", FieldKind.Text, CELL),
            LogField("color_space", "color_space", FieldKind.Text, CELL),
            LogField("duration", "duration", FieldKind.Text, HALF),
            LogField("tracking_markers_used", "flag_tracking_markers_used", FieldKind.Switch),
            LogField("hdri_captured", "flag_hdri_captured", FieldKind.Switch),
            LogField("chrome_ball_captured", "flag_chrome_ball_captured", FieldKind.Switch),
            LogField("vendor", "vendor", FieldKind.Text, CELL),
            LogField("priority", "priority", FieldKind.Pick, HALF, options = platePriorities()),
            LogField("status", "status", FieldKind.Pick, CELL, options = plateStatuses()),
            LogField("notes", "notes", FieldKind.LongText),
        ),
        describe = { it.str("plate_number") },
    ),
)

/** Stunts and safety: the risk, who ran it, and what was checked. */
@Composable
internal fun StuntSafetyScreen() = ContinuityLogScreen(
    LogSpec(
        folder = "stunt-safety",
        titleKey = "stunt_safety",
        addKey = "add_stunt",
        editKey = "edit_stunt",
        emptyKey = "no_stunts",
        deleteTitleKey = "delete_stunt_title",
        columns = listOf(
            LogColumn("scene_id", "scene", ColumnWidth.Fixed(100.dp), CellKind.Scene),
            LogColumn("risk_level", "risk_level", ColumnWidth.Fixed(120.dp), CellKind.Pick, riskLevels()),
            LogColumn("stunt_description", "stunt_description"),
            LogColumn("stunt_coordinator", "stunt_coordinator", ColumnWidth.Fixed(170.dp)),
            LogColumn("safety_briefing_done", "flag_safety_briefing_done", ColumnWidth.Fixed(140.dp), CellKind.Flag),
            LogColumn("medic_on_set", "flag_medic_on_set", ColumnWidth.Fixed(120.dp), CellKind.Flag),
            LogColumn("fire_safety_on_set", "flag_fire_safety_on_set", ColumnWidth.Fixed(130.dp), CellKind.Flag),
        ),
        fields = listOf(
            LogField("scene_id", "scene", FieldKind.Scene, CELL, required = true),
            LogField("risk_level", "risk_level", FieldKind.Pick, HALF, required = true, options = riskLevels()),
            LogField("stunt_description", "stunt_description", FieldKind.LongText),
            LogField("stunt_coordinator", "stunt_coordinator", FieldKind.Text, CELL),
            LogField("safety_briefing_done", "flag_safety_briefing_done", FieldKind.Switch),
            LogField("medic_on_set", "flag_medic_on_set", FieldKind.Switch),
            LogField("fire_safety_on_set", "flag_fire_safety_on_set", FieldKind.Switch),
            LogField("pre_stunt_wardrobe", "wardrobe_before", FieldKind.Text, CELL),
            LogField("post_stunt_wardrobe", "wardrobe_after", FieldKind.Text, CELL),
            LogField("pre_stunt_makeup", "makeup_before", FieldKind.Text, CELL),
            LogField("post_stunt_makeup", "makeup_after", FieldKind.Text, CELL),
            LogField("incidents", "incidents", FieldKind.LongText),
            LogField("notes", "notes", FieldKind.LongText),
        ),
        describe = { it.str("stunt_description") },
    ),
)

/** Editor notes: which take the cutting room should reach for. */
@Composable
internal fun EditorNotesScreen() = ContinuityLogScreen(
    LogSpec(
        folder = "editor-notes",
        titleKey = "editor_notes",
        addKey = "add_editor_note",
        editKey = "edit_editor_note",
        emptyKey = "no_editor_notes",
        deleteTitleKey = "delete_editor_note_title",
        columns = listOf(
            LogColumn("scene_id", "scene", ColumnWidth.Fixed(100.dp), CellKind.Scene),
            LogColumn("preferred_take", "preferred_take", ColumnWidth.Fixed(130.dp), CellKind.Number),
            LogColumn("alternate_takes", "alternate_takes", ColumnWidth.Fixed(150.dp)),
            LogColumn("vfx_notes", "vfx_notes"),
            LogColumn("sound_notes", "sound_notes"),
            LogColumn("editor_comments", "editor_comments"),
        ),
        fields = listOf(
            LogField("scene_id", "scene", FieldKind.Scene, CELL, required = true),
            LogField("preferred_take", "preferred_take", FieldKind.Number, HALF),
            LogField("alternate_takes", "alternate_takes", FieldKind.Text, CELL, placeholder = "alternate_takes_hint"),
            LogField("vfx_notes", "vfx_notes", FieldKind.LongText),
            LogField("sound_notes", "sound_notes", FieldKind.LongText),
            LogField("special_instructions", "special_instructions", FieldKind.LongText),
            LogField("editor_comments", "editor_comments", FieldKind.LongText),
        ),
        describe = { it.str("alternate_takes") },
    ),
)

/** The editor's daily log: every slate and take of a shooting day, in order. */
@Composable
internal fun EditorDailyLogScreen() = ContinuityLogScreen(
    LogSpec(
        folder = "editor-daily-log",
        titleKey = "editor_daily_log",
        addKey = "add_log_entry",
        editKey = "edit_log_entry",
        emptyKey = "no_log_entries",
        deleteTitleKey = "delete_log_entry_title",
        columns = listOf(
            LogColumn("shooting_day", "day", ColumnWidth.Fixed(80.dp), CellKind.Number),
            LogColumn("log_order", "order_number", ColumnWidth.Fixed(80.dp), CellKind.Number),
            LogColumn("scene_id", "scene", ColumnWidth.Fixed(100.dp), CellKind.Scene),
            LogColumn("slate_number", "slate", ColumnWidth.Fixed(100.dp)),
            LogColumn("take_number", "take", ColumnWidth.Fixed(80.dp), CellKind.Number),
            LogColumn("camera", "camera", ColumnWidth.Fixed(80.dp)),
            LogColumn("lens_mm", "lens_mm", ColumnWidth.Fixed(90.dp), CellKind.Number),
            LogColumn("shot_size", "shot_size", ColumnWidth.Fixed(110.dp), CellKind.Pick, shotSizes()),
            LogColumn("shot_description", "shot_description"),
            LogColumn("sound_roll", "sound_roll", ColumnWidth.Fixed(110.dp)),
            LogColumn("timecode_in", "tc_in", ColumnWidth.Fixed(120.dp)),
            LogColumn("timecode_out", "tc_out", ColumnWidth.Fixed(120.dp)),
        ),
        fields = listOf(
            LogField("shooting_day", "shooting_day", FieldKind.Number, HALF),
            LogField("date", "date", FieldKind.Text, HALF, placeholder = "date_hint"),
            LogField("log_order", "order_number", FieldKind.Number, HALF),
            LogField("scene_id", "scene", FieldKind.Scene, CELL, required = true),
            LogField("slate_number", "slate", FieldKind.Text, HALF),
            LogField("take_number", "take", FieldKind.Number, HALF),
            LogField("camera", "camera", FieldKind.Pick, HALF, options = plain(CAMERAS)),
            LogField("lens_mm", "lens_mm", FieldKind.Number, HALF),
            LogField("shot_size", "shot_size", FieldKind.Pick, CELL, options = shotSizes()),
            LogField("shot_description", "shot_description", FieldKind.LongText),
            LogField("sound_roll", "sound_roll", FieldKind.Text, HALF),
            LogField("duration", "duration", FieldKind.Text, HALF),
            LogField("timecode_in", "tc_in", FieldKind.Text, HALF),
            LogField("timecode_out", "tc_out", FieldKind.Text, HALF),
            LogField("director_comments", "director_comments", FieldKind.LongText),
            LogField("script_supervisor_notes", "script_supervisor_notes", FieldKind.LongText),
        ),
        describe = { it.str("slate_number") },
    ),
)

/** Facing pages: the page facing the lined script, scene by scene. */
@Composable
internal fun FacingPagesScreen() = ContinuityLogScreen(
    LogSpec(
        folder = "facing-pages",
        titleKey = "facing_pages",
        addKey = "add_facing_page",
        editKey = "edit_facing_page",
        emptyKey = "no_facing_pages",
        deleteTitleKey = "delete_facing_page_title",
        columns = listOf(
            LogColumn("scene_id", "scene", ColumnWidth.Fixed(100.dp), CellKind.Scene),
            LogColumn("page_number", "page", ColumnWidth.Fixed(90.dp)),
            LogColumn("notes", "notes"),
        ),
        fields = listOf(
            LogField("scene_id", "scene", FieldKind.Scene, CELL, required = true),
            LogField("page_number", "page", FieldKind.Text, HALF),
            LogField("notes", "notes", FieldKind.LongText),
        ),
        describe = { it.str("page_number") },
    ),
)

/** Daily coverage: how much of the day's work is in the can. */
@Composable
internal fun CoverageScreen() = ContinuityLogScreen(
    LogSpec(
        folder = "script-coverage",
        titleKey = "daily_coverage",
        addKey = "add_coverage",
        editKey = "edit_coverage",
        emptyKey = "no_coverage",
        deleteTitleKey = "delete_coverage_title",
        columns = listOf(
            LogColumn("scene_id", "scene", ColumnWidth.Fixed(100.dp), CellKind.Scene),
            LogColumn("pages_shot", "pages_shot", ColumnWidth.Fixed(120.dp)),
            LogColumn("setups", "setups", ColumnWidth.Fixed(100.dp), CellKind.Number),
            LogColumn("notes", "notes"),
        ),
        fields = listOf(
            LogField("scene_id", "scene", FieldKind.Scene, CELL, required = true),
            LogField("pages_shot", "pages_shot", FieldKind.Text, HALF),
            LogField("setups", "setups", FieldKind.Number, HALF),
            LogField("notes", "notes", FieldKind.LongText),
        ),
        describe = { it.str("pages_shot") },
    ),
)

// ------------------------------------------------------------- option builders

private val WEATHER_INT_EXT = listOf("INT", "EXT", "INT/EXT")

private val MATCH_CATEGORIES = listOf(
    "prop_match", "wardrobe_match", "eyeline_match", "action_overlap", "continuity_error", "other",
)

private val PRIORITIES = listOf("high", "medium", "low")
private val PLATE_PRIORITIES = listOf("low", "medium", "high", "critical")
private val PLATE_TYPES = listOf(
    "clean_plate", "dirty_plate", "background_plate", "green_screen", "blue_screen", "witness_camera",
    "hdri", "chrome_ball", "grey_ball", "lidar_scan", "tracking_markers", "reference", "element", "other",
)
private val PLATE_STATUSES = listOf("captured", "in_progress", "delivered", "approved", "retake_needed")
private val RISK_LEVELS = listOf("low", "medium", "high", "extreme")
private val QUALITIES = listOf("excellent", "good", "usable", "noisy", "unusable")

private fun shotSizes() = vocabulary(SHOT_SIZES, "shot_size")
private fun angles() = vocabulary(CAMERA_ANGLES, "angle")
private fun movements() = vocabulary(CAMERA_MOVEMENTS, "movement")
private fun directions() = vocabulary(SCREEN_DIRECTIONS, "direction")
private fun filters() = vocabulary(FILTERS, "filter")
private fun lensOptions() = LENSES.map { it.toString() to "${it}mm" }
private fun matchCategories() = options(MATCH_CATEGORIES, "category")
private fun priorities() = options(PRIORITIES, "priority")
private fun platePriorities() = options(PLATE_PRIORITIES, "priority")
private fun plateTypes() = options(PLATE_TYPES, "plate")
private fun plateStatuses() = options(PLATE_STATUSES, "plate_status")
private fun riskLevels() = options(RISK_LEVELS, "risk")
private fun qualities() = options(QUALITIES, "quality")
