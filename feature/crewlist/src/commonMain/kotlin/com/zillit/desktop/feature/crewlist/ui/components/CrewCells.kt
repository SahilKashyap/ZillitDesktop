package com.zillit.desktop.feature.crewlist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons

/**
 * The crew sheet's four columns — `.crewlist-grid` in `CrewListCustom.css`:
 * `minmax(180px, 260px) minmax(150px, 240px) 1fr 1.4fr`, 12px apart. One
 * layout drives both the column-header strip and every member row, so the
 * headings can never drift off their columns.
 *
 * Name and designation are short and roughly fixed-width; they take their
 * maximum when there is room and give way down to their minimum on a narrow
 * window, leaving phone and the two-line email cell to share the rest.
 */
@Composable
internal fun CrewGrid(
    modifier: Modifier = Modifier,
    centreVertically: Boolean = false,
    name: @Composable () -> Unit,
    designation: @Composable () -> Unit,
    phone: @Composable () -> Unit,
    email: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(name, designation, phone, email),
        modifier = modifier,
    ) { measurables, constraints ->
        val gap = GRID_GAP.roundToPx()
        val total = constraints.maxWidth.coerceAtLeast(0)
        val free = (total - gap * GAPS).coerceAtLeast(0)
        val nameWidth = (free * NAME_SHARE).toInt().coerceIn(NAME_MIN.roundToPx(), NAME_MAX.roundToPx())
        val designationWidth = (free * DESIGNATION_SHARE).toInt()
            .coerceIn(DESIGNATION_MIN.roundToPx(), DESIGNATION_MAX.roundToPx())
        val rest = (free - nameWidth - designationWidth).coerceAtLeast(0)
        val phoneWidth = (rest / (1f + EMAIL_FR)).toInt()
        val emailWidth = (rest - phoneWidth).coerceAtLeast(0)
        val widths = listOf(nameWidth, designationWidth, phoneWidth, emailWidth)

        val placeables = measurables.mapIndexed { index, cell ->
            val width = widths[index]
            cell.firstOrNull()?.measure(Constraints(minWidth = width, maxWidth = width))
        }
        val height = placeables.maxOfOrNull { it?.height ?: 0 } ?: 0
        layout(total, height) {
            var x = 0
            placeables.forEachIndexed { index, placeable ->
                val y = if (centreVertically && placeable != null) (height - placeable.height) / 2 else 0
                placeable?.placeRelative(x, y)
                x += widths[index] + gap
            }
        }
    }
}

/** A band across the sheet: the unit (dark) or a department (light), centred. */
@Composable
internal fun CrewBand(text: String, unit: Boolean, modifier: Modifier = Modifier) {
    val palette = crewPalette()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(if (unit) palette.unitBand else palette.departmentBand)
            .padding(horizontal = 16.dp, vertical = if (unit) 9.dp else 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium.copy(
                fontSize = if (unit) 14.sp else 13.sp,
                fontWeight = if (unit) FontWeight.SemiBold else FontWeight.Medium,
                letterSpacing = if (unit) 0.02.em else 0.01.em,
            ),
            color = if (unit) palette.unitInk else palette.departmentInk,
            maxLines = 1,
        )
    }
}

/** NAME · DESIGNATION · PHONE · EMAIL — 12px bold uppercase on the header strip. */
@Composable
internal fun CrewColumnHeader(labels: List<String>) {
    val palette = crewPalette()
    Box(
        Modifier
            .fillMaxWidth()
            .background(palette.columnHeaderStrip)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        val cell: @Composable (Int) -> Unit = { index ->
            ZillitText(
                text = labels.getOrElse(index) { "" }.uppercase(),
                style = ZillitTheme.typography.labelSmall.copy(
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.06.em,
                ),
                color = palette.columnHeaderInk,
                maxLines = 1,
                modifier = Modifier.padding(start = if (index >= 2) 8.dp else 0.dp),
            )
        }
        CrewGrid(
            centreVertically = true,
            name = { cell(0) },
            designation = { cell(1) },
            phone = { cell(2) },
            email = { cell(3) },
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.border))
}

/**
 * One labelled address line — PROFILE or PROJECT. The label is a fixed 58
 * wide so both values start at the same x all the way down the column.
 */
@Composable
internal fun EmailLine(label: String, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZillitText(
            text = label,
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.08.em,
            ),
            color = ZillitTheme.colors.textMuted,
            maxLines = 1,
            modifier = Modifier.width(EMAIL_LABEL_WIDTH),
        )
        Box(Modifier.weight(1f)) { content() }
    }
}

/** A value, or a placeholder that must not read as one ("Not set", "—"). */
@Composable
internal fun CellValue(value: String, empty: String, modifier: Modifier = Modifier) {
    ZillitText(
        text = value.ifEmpty { empty },
        style = ZillitTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
        color = if (value.isEmpty()) ZillitTheme.colors.textDisabled else ZillitTheme.colors.textSecondary,
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * The sheet's inline input: 34 tall, a quiet border that warms to the accent
 * on hover and rings it on focus — so an editable row still scans like a crew
 * sheet rather than a dense form.
 */
@Composable
internal fun CrewCellField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    digits: Boolean = false,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val borderColor = when {
        isError -> crewPalette().error
        focused || hovered -> colors.accent
        else -> colors.borderStrong
    }
    Box(
        modifier = modifier
            .height(CELL_HEIGHT)
            .clip(CELL_SHAPE)
            .background(if (hovered && !focused) colors.accentSoft.copy(alpha = HOVER_TINT) else colors.surface)
            .border(if (focused) 1.5.dp else 1.dp, borderColor, CELL_SHAPE)
            .hoverable(interaction)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            ZillitText(
                text = placeholder,
                style = ZillitTheme.typography.bodyMedium,
                color = colors.textDisabled,
                maxLines = 1,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            interactionSource = interaction,
            keyboardOptions = if (digits) {
                KeyboardOptions(keyboardType = KeyboardType.Phone)
            } else {
                KeyboardOptions.Default
            },
            textStyle = ZillitTheme.typography.bodyMedium.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * PROJECT while editing: shaped like the field beside it so the pair still
 * scans, but sunken and padlocked so it never invites a click — a value that
 * vanished when editing started would read as data loss.
 */
@Composable
internal fun LockedField(value: String, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier
            .height(CELL_HEIGHT)
            .clip(CELL_SHAPE)
            .background(crewPalette().lockedField)
            .border(1.dp, colors.border, CELL_SHAPE)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitIcon(icon = ZillitIcons.Lock, tint = colors.textMuted, size = 13.dp)
        ZillitText(
            text = value.ifEmpty { "—" },
            style = ZillitTheme.typography.bodyMedium,
            color = colors.textMuted,
            maxLines = 1,
        )
    }
}

/** An inline validation line under a cell — 11px, the web's `#e74c3c`. */
@Composable
internal fun CellError(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
        color = crewPalette().error,
        maxLines = 2,
        modifier = Modifier.padding(start = 2.dp),
    )
}

internal val CELL_HEIGHT = 34.dp
internal val CELL_SHAPE = RoundedCornerShape(6.dp)
private const val HOVER_TINT = 0.35f
private val GRID_GAP = 12.dp
private const val GAPS = 3
private const val NAME_SHARE = 0.25f
private const val DESIGNATION_SHARE = 0.22f
private const val EMAIL_FR = 1.4f
private val NAME_MIN = 180.dp
private val NAME_MAX = 260.dp
private val DESIGNATION_MIN = 150.dp
private val DESIGNATION_MAX = 240.dp
private val EMAIL_LABEL_WIDTH = 58.dp
