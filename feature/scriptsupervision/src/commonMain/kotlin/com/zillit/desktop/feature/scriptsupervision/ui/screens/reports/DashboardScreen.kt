package com.zillit.desktop.feature.scriptsupervision.ui.screens.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitMeter
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.scriptsupervision.data.ContinuityCrud
import com.zillit.desktop.feature.scriptsupervision.domain.CategoryProgress
import com.zillit.desktop.feature.scriptsupervision.domain.DashboardStats
import com.zillit.desktop.feature.scriptsupervision.domain.DayProgress
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.Share
import com.zillit.desktop.feature.scriptsupervision.domain.Stats
import com.zillit.desktop.feature.scriptsupervision.domain.sceneStatusTone
import com.zillit.desktop.feature.scriptsupervision.ui.CardGrid
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.rememberRows
import com.zillit.desktop.feature.scriptsupervision.ui.scenes
import com.zillit.desktop.feature.scriptsupervision.ui.t

/**
 * The production at a glance — the web's `DashboardScreen`.
 *
 * ## Why this has no charts
 *
 * The web draws four recharts figures: a pie of scene statuses, a grouped bar
 * of scenes against completed per day, a line of running pages, and a stacked
 * bar of matching notes by category. Each one was re-read for what the READER
 * has to do with it, and three of the four turn out not to want a chart:
 *
 * - The numbers the page leads with are **headline figures**, which is a row
 *   of stat tiles, not a chart. A pie of five statuses asks a reader to
 *   compare angles for something a labelled count states outright.
 * - Scene status, per-day progress and a category's settled-against-open are
 *   each **a ratio against a whole**, which is a meter — one hue, longer is
 *   more. A meter row IS a horizontal bar, so this is the bar form, drawn
 *   with the design system's own primitive rather than a chart library.
 * - Per-day rows run well past the handful a chart reads comfortably, and
 *   past that the honest form is a table.
 *
 * The one figure that genuinely wants a chart is the running-pages TREND, and
 * it is the one thing left out: a trend over time is a line, and a line needs
 * drawing. It is noted in the hand-over rather than faked with a meter, which
 * would imply a part-to-whole that running pages is not.
 *
 * ## Colour
 *
 * No categorical palette is introduced here, so there is none to validate.
 * Every colour is the design system's STATUS tone, which is legitimate only
 * because these values really are states (completed, in progress, open,
 * resolved) — and each carries its own text label, so identity is never
 * colour alone.
 */
@Composable
internal fun DashboardScreen() {
    val ctx = LocalSs.current
    val sceneState = scenes()
    val takes = rememberRows(ctx) { ContinuityCrud(ctx.api, "takes").list() }
    val matching = rememberRows(ctx) { ContinuityCrud(ctx.api, "matching-notes").list() }

    val stats = DashboardStats.of(
        scenes = sceneState.dayScenes,
        allScenes = sceneState.scenes,
        day = sceneState.day,
        takes = takes.value.orEmpty(),
        matching = matching.value.orEmpty(),
    )

    SsPageHeader(
        title = t("dashboard"),
        // Which production, or which day of it, these figures are about —
        // the one thing a page of percentages must not leave implicit.
        sub = sceneState.day?.let { "${t("day")} $it" } ?: t("all_days"),
    )

    HeadlineTiles(stats)

    if (stats.statusRows.isNotEmpty()) {
        BarCard(t("scene_status")) {
            stats.statusRows.forEach { row ->
                ShareRow(t("status_${row.key}"), row, sceneStatusTone(row.key))
            }
        }
    }

    if (stats.takeCount > 0) {
        BarCard(t("takes_breakdown")) {
            stats.takeRows.filter { it.count > 0 }.forEach { row ->
                ShareRow(takeLabel(row.key), row, takeTone(row.key))
            }
        }
    }

    if (stats.dayRows.isNotEmpty()) {
        BarCard(t("by_shooting_day")) {
            stats.dayRows.forEach { DayRow(it) }
        }
    }

    if (stats.categoryRows.isNotEmpty()) {
        BarCard(t("matching_notes")) {
            // Two series, so a legend is present as well as the direct
            // counts on every row.
            CategoryLegend()
            // ONE axis for every row: the biggest category sets the length
            // the others are read against.
            val maxTotal = stats.categoryRows.maxOf { it.total }
            stats.categoryRows.forEach { CategoryRow(it, maxTotal) }
        }
    }
}

/**
 * A labelled card of bar rows.
 *
 * ⚠ The spaced column is the point. [ZillitSectionCard] pads its content but
 * deliberately does NOT space it — that is the caller's to decide — so a card
 * of several rows draws them touching, which the render showed before this
 * existed. Every section here is rows, so they all want the same gap.
 */
@Composable
private fun BarCard(label: String, rows: @Composable () -> Unit) {
    ZillitSectionLabel(label)
    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) { rows() }
    }
}

/**
 * The figures the page leads with.
 *
 * Tiles rather than a chart, because this is "a handful of headline numbers".
 * Only the two that mean something bad carry a tone: a tile where every
 * number is coloured teaches a reader nothing about which to look at.
 */
@Composable
private fun HeadlineTiles(stats: Stats) {
    CardGrid(headlineTiles(stats), minWidth = TILE_MIN) { tile ->
        ZillitStatTile(
            label = tile.label,
            value = tile.value,
            sub = tile.sub,
            tone = tile.tone,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private class Tile(val label: String, val value: String, val sub: String?, val tone: StatusTone?)

@Composable
private fun headlineTiles(stats: Stats): List<Tile> = listOf(
    Tile(t("filming_progress"), "${stats.completionPercent}%", "${stats.completed}/${stats.sceneCount}", null),
    Tile(t("total_scenes"), stats.sceneCount.toString(), null, null),
    Tile(t("total_takes"), stats.takeCount.toString(), null, null),
    // NG is a figure a script supervisor watches rise, so it is toned once
    // there is any to watch.
    Tile(
        label = t("flag_is_ng"),
        value = "${stats.ngPercent}%",
        sub = stats.ng.toString(),
        tone = if (stats.ng > 0) StatusTone.Rejected else null,
    ),
    Tile(
        label = t("unresolved_issues"),
        value = stats.openIssues.toString(),
        sub = stats.highPriorityOpen.takeIf { it > 0 }?.let { "$it ${t("priority_high")}" },
        tone = if (stats.openIssues > 0) StatusTone.Rejected else StatusTone.Ready,
    ),
)

/** One slice of a whole: its name, its count, its share, and the bar. */
@Composable
private fun ShareRow(label: String, row: Share, tone: StatusTone) {
    MeterRow(label = label, value = "${row.count}  ·  ${row.share}%", fraction = row.share / FULL, tone = tone)
}

/** One shooting day's progress. */
@Composable
private fun DayRow(row: DayProgress) {
    MeterRow(
        label = "${t("day")} ${row.day}",
        value = "${row.completed}/${row.scenes}  ·  ${row.share}%",
        fraction = row.share / FULL,
        // Progress, not a verdict: a day that is not finished is not a fault.
        tone = if (row.share == FULL.toInt()) StatusTone.Ready else StatusTone.Progress,
    )
}

/**
 * A label, its numbers, and a bar.
 *
 * The number is beside the bar on every row because the bar alone cannot be
 * read to a count, and these rows are short enough that a direct label on
 * each is right rather than noise.
 */
@Composable
private fun MeterRow(label: String, value: String, fraction: Float, tone: StatusTone) {
    BarRow(label, value) {
        ZillitMeter(fraction = fraction, tone = tone, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The shape every bar row on this page shares: a label, a fixed-width track,
 * the numbers, and then whatever space is left.
 *
 * ⚠ The track is a FIXED width rather than `weight(1f)`, which looking at the
 * rendered page is what fixed them: stretched across a wide window the bars
 * became page-wide rules and read as dividers between rows rather than as
 * data. A bar wants a length a reader can take in at a glance, so the row
 * ends with a spacer and the page's width goes unused rather than into the
 * mark.
 */
@Composable
private fun BarRow(label: String, value: String, track: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.bodySmall,
            modifier = Modifier.width(ROW_LABEL),
            maxLines = 1,
        )
        Row(Modifier.width(TRACK_WIDTH)) { track() }
        MutedText(value)
        Spacer(Modifier.weight(1f))
    }
}

/** Two series, so they are named once rather than left to colour. */
@Composable
private fun CategoryLegend() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SsTag(t("resolved"), StatusTone.Ready)
        SsTag(t("open"), StatusTone.Rejected)
    }
}

/**
 * One matching category: settled against outstanding, as one bar in two
 * parts.
 *
 * ⚠ [maxTotal] is the whole point. Each row's parts are weighted against the
 * BIGGEST category, not against the row's own total — the mistake looking at
 * the rendered page caught. Weighted per row, every bar filled its track, so
 * a category with one note drew the same length as one with five and the
 * reader lost exactly the comparison a bar chart exists to make. One shared
 * axis across the rows is what the web's stacked bar has, and this now keeps.
 *
 * The two fills are the THEME's own `success` and `danger` rather than a
 * `StatusTone`, because the tone-to-colour mapping is internal to the design
 * system; these are the public tokens behind the same two tones the legend's
 * pills use, so the bar and its legend agree.
 *
 * The parts are told apart by a 2dp gap in the SURFACE colour rather than by a
 * border, which is how the design guidance separates touching marks — a
 * stroke around each would thicken the bar and darken the whole row.
 */
@Composable
private fun CategoryRow(row: CategoryProgress, maxTotal: Int) {
    val colors = ZillitTheme.colors
    BarRow(
        label = t("category_${row.category}"),
        // Direct labels: both counts, so the bar is never measured to be read.
        value = "${row.resolved}  ·  ${row.open}",
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.pill)
                .background(colors.surfaceHover),
        ) {
            if (row.resolved > 0) {
                Spacer(Modifier.weight(row.resolved.toFloat()).barPart(colors.success))
            }
            // The surface gap, and only between two parts that both exist.
            if (row.resolved > 0 && row.open > 0) {
                Spacer(Modifier.width(SURFACE_GAP).height(BAR_HEIGHT).background(colors.surface))
            }
            if (row.open > 0) {
                Spacer(Modifier.weight(row.open.toFloat()).barPart(colors.danger))
            }
            // The unused remainder of the shared axis: what this category is
            // NOT, which is how a shorter bar comes to be shorter.
            val rest = (maxTotal - row.total).coerceAtLeast(0)
            if (rest > 0) Spacer(Modifier.weight(rest.toFloat()).height(BAR_HEIGHT))
        }
    }
}

/** A filled part of a two-part bar. */
private fun Modifier.barPart(fill: Color): Modifier = height(BAR_HEIGHT).background(fill)

/** A take flag's own word; "other" is the leftover, which has none. */
@Composable
private fun takeLabel(key: String): String = if (key == "other") t("other") else t("flag_$key")

/** Circled is good news, NG is not, and the rest are neither. */
private fun takeTone(key: String): StatusTone = when (key) {
    "is_circled" -> StatusTone.Ready
    "is_ng" -> StatusTone.Rejected
    else -> StatusTone.Neutral
}

private const val FULL = 100f
private val TILE_MIN = 200.dp
private val ROW_LABEL = 150.dp
private val TRACK_WIDTH = 280.dp
private val BAR_HEIGHT = 8.dp
private val SURFACE_GAP = 2.dp
