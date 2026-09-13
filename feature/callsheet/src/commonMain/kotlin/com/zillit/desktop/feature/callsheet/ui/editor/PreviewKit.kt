// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("CyclomaticComplexMethod")

package com.zillit.desktop.feature.callsheet.ui.editor

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.feature.callsheet.domain.ColumnSpec
import com.zillit.desktop.feature.callsheet.domain.SheetTime
import com.zillit.desktop.feature.callsheet.domain.SheetMember
import com.zillit.desktop.feature.callsheet.ui.components.plainClick
import com.zillit.desktop.feature.callsheet.ui.components.rememberHover
import com.zillit.desktop.feature.callsheet.ui.components.sheetText
import com.zillit.desktop.feature.callsheet.ui.theme.SheetTheme

/**
 * The printed document's colours. The sheet is paper: light in both themes,
 * as the web's editor preview is — only the chrome around it follows the app.
 */
@Immutable
internal data class DocColors(
    val page: Color,
    val pageBorder: Color,
    val ink: Color,
    val rule: Color,
    val bar: Color,
    val titleBar: Color,
    val headerCell: Color,
    val headerText: Color,
    val dash: Color,
    val faint: Color,
    val meta: Color,
    val disclaimer: Color,
    val secondary: Color,
    val link: Color,
    val weatherFrom: Color,
    val weatherTo: Color,
    val weatherInk: Color,
    val weatherSub: Color,
    val pillBorder: Color,
    val pillText: Color,
    val pillHover: Color,
    val rowTint: Color,
    val cellTint: Color,
    val pickerRule: Color,
) {
    companion object {
        val Paper = DocColors(
            page = Color.White,
            pageBorder = Color(0xFFD0D5DD),
            ink = Color(0xFF111111),
            rule = Color.Black,
            bar = Color(0xFF475467),
            titleBar = Color(0xFF1D2939),
            headerCell = Color(0xFFF2F4F7),
            headerText = Color(0xFF344054),
            dash = Color(0xFFAAAAAA),
            faint = Color(0xFF999999),
            meta = Color(0xFF6F727A),
            disclaimer = Color(0xFF2F2F2F),
            secondary = Color(0xFF475467),
            link = Color(0xFF175CD3),
            weatherFrom = Color(0xFFF8FAFC),
            weatherTo = Color(0xFFEEF2F7),
            weatherInk = Color(0xFF1D2939),
            weatherSub = Color(0xFF667085),
            pillBorder = Color(0xFFD0D5DD),
            pillText = Color(0xFF667085),
            pillHover = Color(0xFFFFF7ED),
            rowTint = Color(0x0DFC9404),
            cellTint = Color(0x38FC9404),
            pickerRule = Color(0xFFE5E7EB),
        )
    }
}

@Suppress("FunctionOnlyReturningConstant") // A composable seam, kept so the paper could follow a theme later.
@Composable
internal fun docColors(): DocColors = DocColors.Paper

/** Draws a 1 px rule on the chosen sides — table cells share lines instead of doubling them. */
internal fun Modifier.rules(
    color: Color,
    top: Boolean = false,
    start: Boolean = false,
    end: Boolean = false,
    bottom: Boolean = false,
): Modifier =
    drawBehind {
        val stroke = 1.dp.toPx()
        if (top) drawRect(color, Offset.Zero, Size(size.width, stroke))
        if (bottom) drawRect(color, Offset(0f, size.height - stroke), Size(size.width, stroke))
        if (start) drawRect(color, Offset.Zero, Size(stroke, size.height))
        if (end) drawRect(color, Offset(size.width - stroke, 0f), Size(stroke, size.height))
    }

/**
 * `Clickable`: selects on click; a faint orange outline on hover and a solid
 * one when selected, both drawn inside the box.
 */
@Composable
internal fun SelectFrame(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val accent = SheetTheme.colors.accent
    val (source, hovered) = rememberHover()
    val outline by animateColorAsState(
        when {
            selected && hovered -> accent.copy(alpha = 0.55f)
            selected -> accent
            hovered -> accent.copy(alpha = 0.3f)
            else -> Color.Transparent
        },
        tween(OUTLINE_MS),
    )
    Box(
        modifier
            .hoverable(source)
            .plainClick(source = source, onClick = onClick)
            .drawWithContent {
                drawContent()
                if (outline.alpha > 0f) {
                    val stroke = 2.dp.toPx()
                    drawRect(
                        outline,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke),
                    )
                }
            },
    ) { content() }
}

private const val OUTLINE_MS = 120

/** How a dark bar lays out its title. */
internal enum class BarAlign { Start, Center }

/**
 * A section's dark title bar, with the edit badge on the right: "Editing"
 * (or "Editing: Row 2 → Name") when selected, "Click to edit" otherwise.
 */
@Composable
internal fun SectionBar(
    title: String,
    selected: Boolean,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 11.sp,
    weight: FontWeight = FontWeight.SemiBold,
    horizontal: Dp = 8.dp,
    vertical: Dp = 4.dp,
    align: BarAlign = BarAlign.Start,
    letterSpacing: TextUnit = 0.5.sp,
    detail: String? = null,
    background: Color = docColors().bar,
    minHeight: Dp = 0.dp,
    trailing: (@Composable () -> Unit)? = null,
    after: (@Composable () -> Unit)? = null,
    interactive: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .background(background)
            .padding(horizontal = horizontal, vertical = vertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title.uppercase(),
            style = sheetText(fontSize, weight, (fontSize.value * 1.25f).sp).copy(letterSpacing = letterSpacing),
            color = Color.White,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (align == BarAlign.Center) androidx.compose.ui.text.style.TextAlign.Center else null,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
        if (interactive) BarBadge(selected, detail, onEdit)
        after?.invoke()
    }
}

/** The badge inside a dark bar. */
@Composable
internal fun BarBadge(selected: Boolean, detail: String?, onEdit: () -> Unit) {
    val accent = SheetTheme.colors.accent
    if (selected) {
        val text = detail?.let { "Editing: $it" } ?: "Editing"
        Text(
            text,
            style = sheetText(9.sp, FontWeight.SemiBold, 12.sp),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 220.dp)
                .background(accent, RoundedCornerShape(4.dp))
                .padding(
                    horizontal = if (detail != null) 8.dp else 6.dp,
                    vertical = if (detail != null) 2.dp else 1.dp,
                ),
        )
    } else {
        val (source, hovered) = rememberHover()
        Text(
            "Click to edit",
            style = sheetText(9.sp, FontWeight.Medium, 12.sp),
            color = if (hovered) Color.White else Color.White.copy(alpha = 0.7f),
            maxLines = 1,
            modifier = Modifier
                .border(1.dp, if (hovered) accent else Color.White.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                .hoverable(source)
                .plainClick(source = source, onClick = onEdit)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** The light badge at the foot of a section whose title is hidden. */
@Composable
internal fun FooterBadge(selected: Boolean, detail: String?, onEdit: () -> Unit, small: Boolean = false) {
    val accent = SheetTheme.colors.accent
    val doc = docColors()
    Box(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 3.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (selected) {
            Text(
                detail?.let { "Editing: $it" } ?: "Editing",
                style = sheetText(9.sp, FontWeight.SemiBold, 12.sp),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .background(accent, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        } else {
            val (source, hovered) = rememberHover()
            Text(
                "Click to edit",
                style = sheetText(if (small) 8.sp else 9.sp, FontWeight.Medium, 12.sp),
                color = if (hovered) accent else if (small) doc.pillText.copy(alpha = 0.8f) else doc.pillText,
                modifier = Modifier
                    .background(if (hovered && !small) doc.pillHover else Color.Transparent, RoundedCornerShape(4.dp))
                    .border(1.dp, if (hovered) accent else doc.pillBorder, RoundedCornerShape(4.dp))
                    .hoverable(source)
                    .plainClick(source = source, onClick = onEdit)
                    .padding(horizontal = if (small) 6.dp else 8.dp, vertical = if (small) 1.dp else 2.dp),
            )
        }
    }
}

/**
 * A label that reads bottom-to-top — the vertical table header. Measured
 * along the column's height, then turned.
 */
internal fun Modifier.readsUpward(): Modifier = layout { measurable, constraints ->
    val along = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity
    val placeable = measurable.measure(Constraints(maxWidth = along))
    layout(placeable.height, placeable.width) {
        placeable.placeWithLayer(0, placeable.width) {
            rotationZ = QUARTER_TURN_BACK
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
        }
    }
}

// Typed values ------------------------------------------------------------------------------

/**
 * `CellValue`: how a stored value prints for its column type. Empty is an
 * em dash; users resolve to names (unknown ids dropped); email, url and phone
 * become links; epoch dates and times (pure digits from 2000 on) are
 * formatted in the local zone, anything else prints as written.
 */
@Composable
internal fun cellValueText(column: ColumnSpec?, value: String, members: List<SheetMember>): AnnotatedString {
    val doc = docColors()
    val v = value.trim()
    val linkStyle = TextLinkStyles(SpanStyle(color = doc.link, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        fun dash() = withStyle(SpanStyle(color = doc.dash)) { append("—") }
        if (v.isEmpty()) {
            dash()
            return@buildAnnotatedString
        }
        when (column?.type) {
            "users" -> {
                val names = v.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    .mapNotNull { id -> members.firstOrNull { it.userId == id }?.fullName?.ifBlank { null } }
                if (names.isEmpty()) dash() else append(names.joinToString(", "))
            }
            "email" -> withLink(LinkAnnotation.Url("mailto:$v", linkStyle)) { append(v) }
            "url" -> withLink(LinkAnnotation.Url(v, linkStyle)) { append(v) }
            "phone" -> withLink(LinkAnnotation.Url("tel:$v", TextLinkStyles(SpanStyle(color = doc.link)))) { append(v) }
            "date" -> append(SheetTime.dateOf(v))
            "time" -> append(SheetTime.clockOf(v))
            else -> append(v)
        }
    }
}

/** The crew table's In column, by header. */
internal fun ColumnSpec.isInColumn(): Boolean = label.trim().equals("in", ignoreCase = true)

/** Legacy crew columns kept in the data but not shown ("As Per", "To Include", "Additional"). */
internal val HIDDEN_CREW_COLUMN = Regex("""^(as\s*per|to\s*include|additional)$""", RegexOption.IGNORE_CASE)

private const val QUARTER_TURN_BACK = -90f
