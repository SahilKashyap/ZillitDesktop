package com.zillit.desktop.feature.scriptsupervision.ui.screens.script

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.scriptsupervision.domain.LineKind
import com.zillit.desktop.feature.scriptsupervision.domain.LinedScript
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.t

/**
 * The scene's script page with a tram line per coverage line — the web's
 * `TramLinesPage.jsx`.
 *
 * ## It is a grid, not an overlay
 *
 * The obvious way to draw tram lines is a Canvas over the text, and that is
 * NOT what the web does and not what this does. The web lays out one flex row
 * per script line — `[number][text][one narrow column per coverage line]` —
 * and paints a short bar in a column wherever that row falls inside that
 * line's span. Consecutive rows stack their bars into what reads as one
 * continuous line.
 *
 * A grid rather than an overlay is the better structure anyway, and not only
 * for parity: every segment is a real composable, so it takes a click and a
 * hover of its own with no hit-testing arithmetic, and the lines reflow with
 * the text instead of needing to be redrawn against it.
 *
 * ⚠ **[ROW_HEIGHT] must stay fixed.** The segments only read as a continuous
 * line because every row is exactly as tall as the bar inside it. Let a row
 * size to its content — a longer line of dialogue, a larger font — and the
 * tram lines break into dashes with gaps the reader will take for meaning.
 * This is why the text is one line, clipped, rather than wrapped.
 */
@Composable
internal fun TramLinesPage(
    scene: Rec?,
    lines: List<Rec>,
    gaps: List<Rec>,
    selectedId: String?,
    onSelect: (Rec) -> Unit,
) {
    val colours = LinedScript.shotColours(lines)
    val script = scene?.strings("script_text").orEmpty()
    val rows = LinedScript.totalRows(script, scene?.int("page_count_eighths") ?: 0, lines)

    // ⚠ The page is bound to its OWN width, not the window's. Rows that
    // filled the window shaded a coverage gap right across the empty space
    // beside the page and centred the scene's name over the window instead
    // of over the page — both of which the render showed and no test could.
    // Everything inside now fills the PAGE.
    val pageWidth = STRIPE_WIDTH + NUMBER_WIDTH + TEXT_WIDTH +
        (CELL_WIDTH + CELL_GAP) * lines.size.coerceAtLeast(1)
    Column(Modifier.width(pageWidth).padding(vertical = ZillitTheme.spacing.md)) {
        PageTitle(scene)
        // A plain loop, not `(1..rows).forEach`: the range allocates per page
        // and this draws one iteration per script line.
        for (row in 1..rows) {
            ScriptRow(
                row = row,
                text = script.getOrNull(row - 1).orEmpty(),
                gap = LinedScript.gapAt(gaps, row),
                lines = lines,
                colours = colours,
                selectedId = selectedId,
                onSelect = onSelect,
            )
        }
        Spacer(Modifier.height(ZillitTheme.spacing.md))
        TramLegend(lines, colours, onSelect)
    }
}

/** The scene's name over the page, as a script page carries its own heading. */
@Composable
private fun PageTitle(scene: Rec?) {
    val name = scene?.str("scene_name")?.takeIf { it.isNotBlank() }
        ?: "${t("scene")} ${scene?.str("scene_number").orEmpty()}".trim()
    ZillitText(
        text = name.uppercase(),
        style = ZillitTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
    val description = scene?.str("scene_description").orEmpty()
    if (description.isNotBlank()) {
        ZillitText(
            text = description.take(DESCRIPTION_MAX) + if (description.length > DESCRIPTION_MAX) "…" else "",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.fillMaxWidth().padding(bottom = ZillitTheme.spacing.sm),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * One numbered row of the page: its number, its text, and a cell per coverage
 * line.
 *
 * A row inside a coverage gap is washed in the gap's colour and carries a
 * stripe down its left edge, which is the web's own treatment — the wash says
 * where and the stripe survives a reader who cannot see the wash.
 */
@Composable
private fun ScriptRow(
    row: Int,
    text: String,
    gap: Rec?,
    lines: List<Rec>,
    colours: Map<String, String>,
    selectedId: String?,
    onSelect: (Rec) -> Unit,
) {
    val colors = ZillitTheme.colors
    val serious = gap?.str("gap_type") == "no_coverage"
    val stripe = when {
        gap == null -> Color.Transparent
        serious -> colors.danger
        else -> colors.warning
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(
                when {
                    gap == null -> Color.Transparent
                    serious -> colors.dangerSoft
                    else -> colors.warningSoft
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(STRIPE_WIDTH).fillMaxHeight().background(stripe))
        ZillitText(
            text = row.toString(),
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
            modifier = Modifier.padding(start = ZillitTheme.spacing.xxs).width(NUMBER_WIDTH),
            maxLines = 1,
        )
        // Weighted inside a page of KNOWN width, so the text fills what the
        // numbers and the tram strip leave and the strip sits right beside
        // the words it annotates. Weighted inside the WINDOW it shoved the
        // tram lines a thousand points away from them.
        ScriptText(text, Modifier.weight(1f))
        TramCells(row, lines, colours, selectedId, onSelect)
    }
}

/**
 * One line of the script, set the way a screenplay sets it.
 *
 * A row with no text draws a run of dots rather than nothing, which is the
 * web's own device: the page is as long as the scene is MEASURED to be, so
 * the rows past the imported text are real page somebody can still line, and
 * an empty row would read as the page having ended.
 */
@Composable
private fun ScriptText(text: String, modifier: Modifier) {
    val colors = ZillitTheme.colors
    if (text.isBlank()) {
        ZillitText(
            text = BLANK_RUN,
            style = ZillitTheme.typography.numeric,
            color = colors.border,
            modifier = modifier,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        return
    }
    val kind = LinedScript.lineKind(text)
    ZillitText(
        text = text,
        style = ZillitTheme.typography.numeric,
        color = if (kind == LineKind.Parenthetical) colors.textSecondary else colors.textPrimary,
        modifier = modifier.padding(
            start = when (kind) {
                LineKind.Cue -> CUE_INDENT
                LineKind.Parenthetical -> PAREN_INDENT
                LineKind.Plain -> 0.dp
            },
            end = ZillitTheme.spacing.sm,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The strip of coverage columns beside one row — one cell per coverage line. */
@Composable
private fun TramCells(
    row: Int,
    lines: List<Rec>,
    colours: Map<String, String>,
    selectedId: String?,
    onSelect: (Rec) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
        lines.forEach { line ->
            TramCell(
                line = line,
                row = row,
                colour = colours[line.str("shot_number")],
                selected = selectedId == line.id,
                onSelect = onSelect,
            )
        }
    }
}

/**
 * One cell: a segment of one coverage line beside one row, or empty space.
 *
 * The whole cell is clickable, not just the 3dp bar — a 3dp target is not a
 * target. Empty cells stay clickable too, so a reader aiming at a line and
 * missing by a row still selects it rather than nothing.
 */
@Composable
private fun TramCell(line: Rec, row: Int, colour: String?, selected: Boolean, onSelect: (Rec) -> Unit) {
    val fill = colour?.let(::dataColour)
    Box(
        Modifier
            .width(CELL_WIDTH)
            .fillMaxHeight()
            .clickable { onSelect(line) },
        contentAlignment = Alignment.Center,
    ) {
        if (!LinedScript.covers(line, row) || fill == null) return@Box
        val dashed = line.str("line_type") == "squiggly"
        Spacer(
            Modifier
                .width(if (selected) BAR_SELECTED else BAR_WIDTH)
                .fillMaxHeight()
                .then(if (dashed) Modifier.dashedBar(fill, selected) else Modifier.solidBar(fill)),
        )
        // The shot number at the line's first row, as the web prints it: this
        // is the secondary encoding the palette's colourblind separation
        // DEPENDS on, not a decoration. It overflows its 12dp cell on
        // purpose, which the web's own `left:-2px; white-space:nowrap` does.
        if (row == line.int("line_start")) {
            ZillitText(
                text = line.str("shot_number"),
                style = ZillitTheme.typography.labelSmall.copy(fontSize = LABEL_SIZE),
                color = fill,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(y = -LABEL_LIFT)
                    .wrapContentWidth(unbounded = true),
                maxLines = 1,
            )
        }
    }
}

/** A solid segment: on screen. */
private fun Modifier.solidBar(fill: Color): Modifier =
    clip(androidx.compose.foundation.shape.RoundedCornerShape(BAR_RADIUS)).background(fill)

/**
 * A dashed segment: off screen.
 *
 * Drawn rather than built from boxes because the dash has to be a dash — the
 * web's `repeating-linear-gradient` restarts at every row's top, and so does
 * this, because the stroke is drawn per cell. At a 6dp period inside an 18dp
 * row that lands on three whole dashes either way, so the two clients show
 * the same pattern.
 */
private fun Modifier.dashedBar(fill: Color, selected: Boolean): Modifier = drawBehind {
    val width = if (selected) BAR_SELECTED.toPx() else BAR_WIDTH.toPx()
    drawLine(
        color = fill,
        start = Offset(size.width / 2, 0f),
        end = Offset(size.width / 2, size.height),
        strokeWidth = width,
        cap = StrokeCap.Butt,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH_ON.toPx(), DASH_OFF.toPx())),
    )
}

/**
 * Which colour is which shot, and what solid and dashed mean.
 *
 * Present always rather than only past four series: these are eight hues on a
 * dense page, the palette's worst adjacent pair separates by only ΔE 7.9 for
 * protanopia, and the legend is also how a reader picks a setup without
 * hunting for its line on the page.
 */
@Composable
private fun TramLegend(lines: List<Rec>, colours: Map<String, String>, onSelect: (Rec) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        colours.forEach { (shot, hex) ->
            val line = lines.firstOrNull { it.str("shot_number") == shot } ?: return@forEach
            Row(
                Modifier.clickable { onSelect(line) },
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Swatch(hex)
                ZillitText(shot, style = ZillitTheme.typography.label, maxLines = 1)
                MutedText(
                    listOf(
                        line.str("shot_size"),
                        line.first("character_focus", "shot_description"),
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                )
                val circled = line.strings("circled_takes")
                if (circled.isNotEmpty()) MutedText("⊙${circled.joinToString(",")}")
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ⚠ These two are drawn as the MARK, not as swatches. Two
            // squares would tell a reader the two colours apart and say
            // nothing about solid against dashed, which is the only thing
            // this pair of the legend exists to teach — and the render
            // showed exactly that: two squares, no dashes anywhere.
            LineSample(LinedScript.LEGEND_ON_SCREEN, dashed = false)
            MutedText(t("on_screen"))
            LineSample(LinedScript.LEGEND_OFF_SCREEN, dashed = true)
            MutedText(t("off_screen"))
        }
    }
}

/**
 * A sample of the mark itself: one short tram line, solid or dashed.
 *
 * Deliberately the same 3dp bar at the same dash period as the page draws, so
 * what the legend shows is literally what is beside the text.
 */
@Composable
private fun LineSample(hex: String, dashed: Boolean) {
    val fill = dataColour(hex) ?: ZillitTheme.colors.textMuted
    Spacer(
        Modifier
            .width(BAR_WIDTH)
            .height(SAMPLE_HEIGHT)
            .then(if (dashed) Modifier.dashedBar(fill, selected = false) else Modifier.solidBar(fill)),
    )
}

/** A legend swatch in a data colour. */
@Composable
private fun Swatch(hex: String) {
    val fill = dataColour(hex) ?: ZillitTheme.colors.textMuted
    Spacer(
        Modifier
            .width(SWATCH)
            .height(SWATCH)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(SWATCH_RADIUS))
            .background(fill),
    )
}

/**
 * One of the service's `#rrggbb` colours as a [Color].
 *
 * `#rrggbb` carries no alpha, so it is forced opaque — without the OR the
 * colour would come out fully transparent and the line would simply not be
 * there. Null for anything unparseable, so a colour the service invents
 * leaves a gap rather than throwing in a composable.
 */
private fun dataColour(hex: String): Color? =
    runCatching { Color(hex.removePrefix("#").toLong(HEX_RADIX) or OPAQUE) }.getOrNull()


private const val OPAQUE = 0xFF000000L
private const val HEX_RADIX = 16
private const val DESCRIPTION_MAX = 120
private val BLANK_RUN = "·".repeat(30)
private val ROW_HEIGHT = 18.dp
private val STRIPE_WIDTH = 3.dp
private val NUMBER_WIDTH = 24.dp
private val TEXT_WIDTH = 560.dp
private val CUE_INDENT = 120.dp
private val PAREN_INDENT = 100.dp
private val CELL_WIDTH = 12.dp
private val CELL_GAP = 2.dp
private val BAR_WIDTH = 3.dp
private val BAR_SELECTED = 5.dp
private val BAR_RADIUS = 1.dp
private val DASH_ON = 3.dp
private val DASH_OFF = 3.dp
private val LABEL_SIZE = 8.sp
private val LABEL_LIFT = 9.dp
private val SWATCH = 10.dp
private val SAMPLE_HEIGHT = 14.dp
private val SWATCH_RADIUS = 2.dp
