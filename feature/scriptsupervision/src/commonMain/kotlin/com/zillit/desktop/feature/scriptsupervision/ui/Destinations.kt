package com.zillit.desktop.feature.scriptsupervision.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.zillit.desktop.core.designsystem.icon.AhIcons
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * Every page of the tool and the side menu's order — the web's `ROUTES` and
 * `MENU`, in the order script supervisors know.
 *
 * One list rather than a route table and a menu table: the web keeps two and
 * the pair is what drifts (a page with no row, a row with no page). A detail
 * page is NOT in here — it is reached from its list and keeps its section
 * selected, as the web's `characters/:id/…` routes do.
 *
 * [route] is the web's own path segment, so a link, a log line and the web's
 * URL all say the same word.
 */
enum class SsPage(
    val route: String,
    val labelKey: String,
    val icon: ImageVector,
    val group: SsGroup,
) {
    QuickLog("quick-log", "quick_log", ZillitIcons.Edit, SsGroup.Top),
    Production("production", "production", ZillitIcons.Grid, SsGroup.Top),
    Dashboard("dashboard", "dashboard", AhIcons.CostReport, SsGroup.Top),

    Scenes("scenes", "scenes", ZillitIcons.File, SsGroup.OnSet),
    Takes("takes", "takes", AhIcons.Video, SsGroup.OnSet),
    Camera("camera", "camera_details", ZillitIcons.Camera, SsGroup.OnSet),
    Dialogue("dialogue", "dialogue_notes", ZillitIcons.Chat, SsGroup.OnSet),
    ActionContinuity("action-continuity", "action_continuity", ZillitIcons.Eye, SsGroup.OnSet),
    SoundReports("sound-reports", "sound_reports", ZillitIcons.Mic, SsGroup.OnSet),

    PropsWardrobe("props-wardrobe", "props_wardrobe", AhIcons.Archive, SsGroup.Continuity),
    Matching("matching", "matching_notes", ZillitIcons.Link, SsGroup.Continuity),
    StuntSafety("stunt-safety", "stunt_safety", ZillitIcons.Shield, SsGroup.Continuity),
    ContinuityPhotos("continuity-photos", "continuity_photos", ZillitIcons.Photo, SsGroup.Continuity),
    WeatherLighting("weather-lighting", "weather_lighting", ZillitIcons.Sun, SsGroup.Continuity),

    Characters("characters", "characters", ZillitIcons.User, SsGroup.Department),
    Actors("actors", "actors", ZillitIcons.StarOutline, SsGroup.Department),
    StoryDays("story-days", "story_timeline", ZillitIcons.Clock, SsGroup.Department),
    CostumeLooks("costume-looks", "costume_looks", ZillitIcons.Tag, SsGroup.Department),
    HairLooks("hair-looks", "hair_looks", AhIcons.Cut, SsGroup.Department),
    MakeupLooks("makeup-looks", "makeup_looks", AhIcons.Stamp, SsGroup.Department),
    Boxes("boxes", "boxes", ZillitIcons.Inbox, SsGroup.Department),

    ScriptBreakdown("script-breakdown", "script_breakdown", ZillitIcons.Tag, SsGroup.Script),
    ElementManager("element-manager", "element_manager", ZillitIcons.Search, SsGroup.Script),
    LinedScript("lined-script", "lined_script", ZillitIcons.Edit, SsGroup.Script),
    FacingPages("facing-pages", "facing_pages", AhIcons.Document, SsGroup.Script),
    EditorNotes("editor-notes", "editor_notes", AhIcons.Cut, SsGroup.Script),
    EditorDailyLog("editor-daily-log", "editor_daily_log", AhIcons.Document, SsGroup.Script),
    ScriptRevisions("script-revisions", "script_revisions", ZillitIcons.Clock, SsGroup.Script),

    Coverage("coverage", "daily_coverage", AhIcons.CostReport, SsGroup.Reports),
    VfxPlates("vfx-plates", "vfx_plates", AhIcons.Flag, SsGroup.Reports),

    ScriptImport("script-import", "script_import", ZillitIcons.Upload, SsGroup.Tools),
    CallSheetImport("callsheet-import", "callsheet_import", ZillitIcons.Calendar, SsGroup.Tools),
    Templates("templates", "templates", ZillitIcons.Grid, SsGroup.Tools),
    Export("export", "export", ZillitIcons.Download, SsGroup.Tools),
    AuditLog("audit-log", "audit_log", ZillitIcons.Clock, SsGroup.Tools),
    ;

    companion object {
        /** Where the tool opens, as the web's index route redirects. */
        val Start: SsPage = QuickLog

        /** The page a route segment names, or [Start] — the web's catch-all. */
        fun of(route: String?): SsPage = entries.firstOrNull { it.route == route } ?: Start
    }
}

/**
 * The side menu's groups. The first has no heading, as the web's first block
 * is `[null, [...]]` — quick log, production and the dashboard sit above the
 * sections rather than inside one.
 */
enum class SsGroup(val labelKey: String?) {
    Top(null),
    OnSet("group_on_set"),
    Continuity("group_continuity"),
    Department("group_department"),
    Script("group_script"),
    Reports("group_reports"),
    Tools("group_tools"),
}
