package com.zillit.desktop.feature.home.domain

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * How a section heading on the Film Tools page looks — the web's
 * `constants/toolGroups.jsx` ("Film Tools (Option B)"): a coloured glyph, the
 * group's name, and a one-line subheading.
 *
 * The six default groups carry their own colour, glyph and subheading; an
 * admin-created group gets the neutral grid glyph and no subheading. The
 * *name* never comes from here — it is the live `group_name`.
 */
data class GroupVisual(val color: Color, val icon: ImageVector, private val subKey: String?) {
    /** The subheading; English on the web too, which renders `sub` rather than its `subKey`. */
    val sub: String? get() = subKey?.let { str(it) }
}

/** The visual for [groupIdentifier]; custom groups and Ungrouped fall back to the neutral one. */
fun groupVisual(groupIdentifier: String?): GroupVisual =
    DEFAULT_GROUPS[groupIdentifier] ?: NEUTRAL

/**
 * The web's identifier → group fallback (`TOOL_GROUP_FALLBACK_MAP`), for a
 * tool whose row carries no `group_identifier` at all — a production whose
 * tool docs predate the grouping migration. Anything not listed goes to
 * `group_admin`, so no tile is ever dropped. An explicit empty string is a
 * different answer — "removed from every group" — and is not routed here.
 */
fun fallbackGroupFor(identifier: String): String = FALLBACK_GROUPS[identifier] ?: ADMIN_GROUP

/** The group a tile with an unknown group id is filed under, as on the web. */
const val ADMIN_GROUP = "group_admin"

private val DEFAULT_GROUPS: Map<String, GroupVisual> = mapOf(
    "group_accounts_payroll" to GroupVisual(
        Color(0xFF2F8F5B),
        glyph("accounts", "M3 21h18M5 21V9.5M9.67 21V9.5M14.33 21V9.5M19 21V9.5M12 3 3.5 8h17z"),
        S.desktop_ft_sub_accounts,
    ),
    "group_ads" to GroupVisual(
        Color(0xFFD97706),
        glyph("ads", rect(3.5f, 4.5f, 17f, 16f, 2.5f), "M3.5 9h17M8 3v3M16 3v3"),
        S.desktop_ft_sub_ads,
    ),
    "group_departments" to GroupVisual(
        Color(0xFF7C3AED),
        glyph(
            "departments",
            circle(9f, 8f, 3.2f),
            "M3.5 20a5.5 5.5 0 0 1 11 0M16 5.2a3.2 3.2 0 0 1 0 5.9M17.5 14.2a5.5 5.5 0 0 1 3 5.3",
        ),
        S.desktop_ft_sub_departments,
    ),
    "group_location" to GroupVisual(
        Color(0xFF2563EB),
        glyph("location", "M19 10.5c0 5.5-7 11-7 11s-7-5.5-7-11a7 7 0 0 1 14 0z", circle(12f, 10.5f, 2.6f)),
        S.desktop_ft_sub_location,
    ),
    "group_productions" to GroupVisual(
        Color(0xFFDB2777),
        glyph(
            "productions",
            rect(3.5f, 9f, 17f, 11.5f, 2f),
            "M3.5 9 6 4.5h12L20.5 9M8 4.5 5.5 9M13 4.5 10.5 9M18 4.5 15.5 9",
        ),
        S.desktop_ft_sub_productions,
    ),
    ADMIN_GROUP to GroupVisual(
        Color(0xFF0891B2),
        glyph(
            "admin",
            rect(4f, 10.5f, 16f, 10.5f, 2.5f),
            "M7.5 10.5V7a4.5 4.5 0 0 1 9 0v3.5",
            circle(12f, 15.5f, 1.4f),
        ),
        S.desktop_ft_sub_admin,
    ),
)

private val NEUTRAL = GroupVisual(
    Color(0xFF64748B),
    glyph(
        "custom",
        rect(3.5f, 4.5f, 7f, 7f, 1.6f),
        rect(13.5f, 4.5f, 7f, 7f, 1.6f),
        rect(3.5f, 14.5f, 7f, 7f, 1.6f),
        rect(13.5f, 14.5f, 7f, 7f, 1.6f),
    ),
    null,
)

private val FALLBACK_GROUPS: Map<String, String> = buildMap {
    listOf(
        "account_hub_tool", "accounting_tool", "asset_report_tool", "card_expenses_tool", "cash_expenses_tool",
        "cost_report_tool", "deal_memo_tool", "department_budget_tool", "main_budget_tool", "payroll_tool",
        "purchase_order_tool", "timecard_tool",
    ).forEach { put(it, "group_accounts_payroll") }
    listOf(
        "ad_dashboard_tool", "callsheet_tool", "casting_background_tool", "casting_main_tool", "catering_tool",
        "dod_tool", "production_report_tool", "schedule_distribution_tool", "script_notes_tool",
        "supporting_artistes_extras_tool",
    ).forEach { put(it, "group_ads") }
    listOf("continuity_tool", "reports_tool", "wardrobe_background_tool", "wardrobe_main_tool")
        .forEach { put(it, "group_departments") }
    listOf("location_tool", "map_tool", "recce_tool").forEach { put(it, "group_location") }
    listOf(
        "box_schedule_tool", "distribution_tool", "document_distribution_tool", "e_signature_tool", "email_tool",
        "forms_and_signature_tool", "generate_crew_list_tool", "pre_production_tool", "production_tool",
        "script_distribution_tool", "sides_tool", "transportation_tool", "weather_tool",
    ).forEach { put(it, "group_productions") }
    listOf("confidential_info_tool", "drive_tool", "external_users_tool", "info_tool", "permission_grid_tool")
        .forEach { put(it, ADMIN_GROUP) }
}

/** The web's inline stroke SVG — 24 viewport, 1.6 stroke, round caps and joins. */
private fun glyph(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(
        name = "ToolGroup.$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = VIEWPORT,
        viewportHeight = VIEWPORT,
    ).apply {
        paths.forEach { data ->
            addPath(
                pathData = PathParser().parsePathString(data).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = STROKE,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

/** An SVG `<rect rx>` as a path. */
private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float): String =
    "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 -$r ${r}" +
        "h-${w - 2 * r}a$r $r 0 0 1 -$r -${r}v-${h - 2 * r}a$r $r 0 0 1 $r -${r}z"

/** An SVG `<circle>` as a path. */
private fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 -${2 * r} 0z"

private const val VIEWPORT = 24f
private const val STROKE = 1.6f
