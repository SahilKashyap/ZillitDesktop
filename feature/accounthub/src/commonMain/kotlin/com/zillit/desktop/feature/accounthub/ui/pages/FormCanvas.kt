package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.forms.FormFieldType
import com.zillit.desktop.core.forms.FormModule
import com.zillit.desktop.core.forms.FormSection

/*
 * The form as its submitters will see it — shared by the preview and the
 * editor, which the web draws with the same markup: a section card with an
 * amber eyebrow, then a grid of labelled placeholder inputs, or a one-row
 * table for line items.
 */

/** The section every Purchase Order line is built from, drawn as a table rather than a grid. */
internal const val LINE_ITEMS_SECTION = "line_items"

internal val FormSection.isLineItems: Boolean get() = key == LINE_ITEMS_SECTION

internal val FormModule.icon: ImageVector
    get() = when (this) {
        FormModule.PurchaseOrders -> ZillitIcons.Receipt
        FormModule.CashExpenses -> ZillitIcons.Wallet
    }

/** The section card: a sunken header band holding the eyebrow and whatever the caller adds, then the body. */
@Composable
internal fun FormSectionCard(
    header: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(SectionShape)
            .background(colors.surface)
            .border(1.dp, colors.border, SectionShape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceSunken)
                .padding(horizontal = ZillitTheme.spacing.lg + ZillitTheme.spacing.xs, vertical = HEADER_VERTICAL),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            content = header,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/** "📄 PO DETAILS" — the amber, tracked section eyebrow. */
@Composable
internal fun SectionEyebrow(text: String, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(icon = ZillitIcons.File, tint = colors.accentText, size = 13.dp)
        ZillitText(text = text.uppercase(), style = formEyebrow(), color = colors.accentText, maxLines = 1)
    }
}

/** The web's 10.5px bold uppercase label with wide tracking — sans, unlike the hub's mono eyebrow. */
@Composable
internal fun formEyebrow(): TextStyle = ZillitTheme.typography.labelSmall.copy(
    fontSize = 10.5.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 1.4.sp,
)

/**
 * A section's fields as the form lays them out.
 *
 * Three columns on a Purchase Order, two on Petty Cash, where a multi-line box
 * takes the whole row — the web's grid, placement included: a wide field that
 * does not fit what is left of a row starts the next one.
 *
 * With [onField] the cells are the editor's: clickable, ringed on hover, and
 * the [focusedId] one lit.
 */
@Composable
internal fun FieldGrid(
    fields: List<FormField>,
    module: FormModule,
    modifier: Modifier = Modifier,
    focusedId: String? = null,
    onField: ((FormField) -> Unit)? = null,
) {
    val columns = if (module == FormModule.CashExpenses) 2 else GRID_COLUMNS
    val wide = { field: FormField -> module == FormModule.CashExpenses && field.type == TEXTAREA }
    Column(
        modifier = modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg + ZillitTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        packRows(fields, columns) { if (wide(it)) columns else 1 }.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                var used = 0
                row.forEach { field ->
                    val span = if (wide(field)) columns else 1
                    used += span
                    FieldCell(
                        field = field,
                        focused = field.id == focusedId,
                        onClick = onField?.let { { it(field) } },
                        modifier = Modifier.weight(span.toFloat()),
                    )
                }
                if (used < columns) Spacer(Modifier.weight((columns - used).toFloat()))
            }
        }
    }
}

/** Rows of fields, each row holding at most [columns] spans; a field that does not fit opens the next row. */
internal fun packRows(fields: List<FormField>, columns: Int, span: (FormField) -> Int): List<List<FormField>> {
    val rows = mutableListOf<MutableList<FormField>>()
    var used = columns
    fields.forEach { field ->
        val width = span(field).coerceIn(1, columns)
        if (used + width > columns) {
            rows += mutableListOf<FormField>()
            used = 0
        }
        rows.last() += field
        used += width
    }
    return rows
}

/** One field: its label with the required star or "(optional)", over the input it will be. */
@Composable
private fun FieldCell(field: FormField, focused: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val ring by animateColorAsState(
        when {
            focused -> colors.accent
            hovered && onClick != null -> colors.borderStrong
            else -> Color.Transparent
        },
        label = "fieldCellRing",
    )
    Column(
        modifier = modifier
            .clip(CellShape)
            .background(if (focused) colors.accentSoft else Color.Transparent)
            .border(if (focused) 2.dp else 1.dp, ring, CellShape)
            .then(
                if (onClick != null) {
                    Modifier
                        .hoverable(interaction)
                        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(CELL_PADDING),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs + 2.dp),
    ) {
        FieldLabelLine(field)
        InputPlaceholder(field)
    }
}

/** "VENDOR *" or "NOTES (optional)". */
@Composable
internal fun FieldLabelLine(field: FormField, showOptional: Boolean = true) {
    val colors = ZillitTheme.colors
    ZillitText(
        text = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)) {
                append(field.name.uppercase())
            }
            if (field.required) {
                withStyle(SpanStyle(color = colors.accent, fontWeight = FontWeight.Bold)) { append(" *") }
            } else if (showOptional) {
                withStyle(
                    SpanStyle(
                        color = colors.textMuted,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = 0.sp,
                    ),
                ) { append("  (optional)") }
            }
        },
        style = ZillitTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
        color = colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The input a submitter will fill, drawn empty with the web's placeholder for its type. */
@Composable
private fun InputPlaceholder(field: FormField, height: Dp = INPUT_HEIGHT) {
    val colors = ZillitTheme.colors
    val tall = field.type == TEXTAREA
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (tall) INPUT_TALL else height)
            .clip(InputShape)
            .background(colors.surface)
            .border(1.dp, colors.border, InputShape)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = if (tall) ZillitTheme.spacing.sm else 0.dp),
        verticalAlignment = if (tall) Alignment.Top else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = placeholderFor(field),
            style = ZillitTheme.typography.bodyMedium,
            color = colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (field.type == FormFieldType.Select.wire) {
            ZillitIcon(icon = ZillitIcons.ChevronDown, tint = colors.textMuted, size = 12.dp)
        }
    }
}

/** The web's placeholder per type: "Select vendor…", "0", "dd/mm/yyyy", "email@example.com", "+44…", "https://…". */
internal fun placeholderFor(field: FormField): String = when (field.type) {
    FormFieldType.Select.wire -> "Select ${field.name.lowercase()}..."
    FormFieldType.Number.wire -> "0"
    FormFieldType.Date.wire -> "dd/mm/yyyy"
    FormFieldType.Email.wire -> "email@example.com"
    FormFieldType.Phone.wire -> "+44..."
    FormFieldType.Url.wire -> "https://..."
    else -> "${field.name}..."
}

/**
 * Line items as the order form draws them: a header of column names over one
 * row of inputs. Columns keep a readable width and the table scrolls sideways
 * when a production has added more than fit, as the web's does.
 */
@Composable
internal fun LineItemsTable(
    fields: List<FormField>,
    focusedId: String? = null,
    onField: ((FormField) -> Unit)? = null,
) {
    val colors = ZillitTheme.colors
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = maxWidth - ZillitTheme.spacing.lg * 2
        val columnWidth = if (fields.isEmpty()) MIN_COLUMN else maxOf(MIN_COLUMN, available / fields.size)
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll)
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .background(colors.surfaceSunken),
            ) {
                fields.forEach { field ->
                    Box(Modifier.width(columnWidth).padding(horizontal = 10.dp, vertical = 9.dp)) {
                        FieldLabelLine(field, showOptional = false)
                    }
                }
            }
            Box(Modifier.width(columnWidth * fields.size).height(1.dp).background(colors.border))
            Row {
                fields.forEach { field ->
                    TableCell(
                        field = field,
                        focused = field.id == focusedId,
                        onClick = onField?.let { { it(field) } },
                        modifier = Modifier.width(columnWidth),
                    )
                }
            }
        }
        // Soft edges where the table runs on, so a column cut by the card reads
        // as "more this way" rather than as a broken layout.
        EdgeFades(start = scroll.canScrollBackward, end = scroll.canScrollForward)
    }
}

/** The card's surface fading over whichever edges the table continues past. */
@Composable
private fun BoxScope.EdgeFades(start: Boolean, end: Boolean) {
    if (!start && !end) return
    val surface = ZillitTheme.colors.surface
    Box(
        Modifier.matchParentSize().drawBehind {
            val fade = EDGE_FADE.toPx()
            if (end) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(surface.copy(alpha = 0f), surface),
                        startX = size.width - fade,
                        endX = size.width,
                    ),
                    topLeft = Offset(size.width - fade, 0f),
                    size = Size(fade, size.height),
                )
            }
            if (start) {
                drawRect(
                    brush = Brush.horizontalGradient(listOf(surface, surface.copy(alpha = 0f)), 0f, fade),
                    size = Size(fade, size.height),
                )
            }
        },
    )
}

@Composable
private fun TableCell(field: FormField, focused: Boolean, onClick: (() -> Unit)?, modifier: Modifier) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(modifier = modifier.padding(horizontal = 6.dp, vertical = 8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(InputShape)
                .border(
                    if (focused) 2.dp else 1.dp,
                    when {
                        focused -> colors.accent
                        hovered && onClick != null -> colors.borderStrong
                        else -> Color.Transparent
                    },
                    InputShape,
                )
                .then(
                    if (onClick != null) {
                        Modifier
                            .hoverable(interaction)
                            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(2.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TABLE_INPUT)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(6.dp))
                    .padding(horizontal = ZillitTheme.spacing.sm),
                contentAlignment = Alignment.CenterStart,
            ) {
                ZillitText(
                    text = when (field.type) {
                        FormFieldType.Select.wire -> "Select..."
                        FormFieldType.Number.wire -> "0"
                        FormFieldType.Date.wire -> "dd/mm/yyyy"
                        else -> "${field.name}..."
                    },
                    style = ZillitTheme.typography.bodySmall,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// -- badges -----------------------------------------------------------------------------

/** The palette of a small uppercase badge: system amber, custom grey, type blue. */
internal enum class BadgeTone { System, Custom, Type }

/** "SYSTEM", "CUSTOM", "SELECT" — the web's rounded uppercase field badges. */
@Composable
internal fun FormBadge(text: String, tone: BadgeTone, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val (background, content) = when (tone) {
        BadgeTone.System -> colors.accentSoft to colors.accentText
        BadgeTone.Custom -> colors.surfaceSunken to colors.textSecondary
        BadgeTone.Type -> colors.infoSoft to colors.info
    }
    Box(
        modifier = modifier
            .clip(ZillitTheme.shapes.pill)
            .background(background)
            .border(1.dp, content.copy(alpha = BADGE_RING_ALPHA), ZillitTheme.shapes.pill)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = 1.dp),
    ) {
        ZillitText(
            text = text.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            ),
            color = content,
            maxLines = 1,
        )
    }
}

/** A field's type as its badge says it — the picker's word when it has one, the server's otherwise. */
internal val FormField.typeBadge: String get() = knownType?.wire ?: type.ifBlank { FormFieldType.Text.wire }

// -- the right-hand panel ---------------------------------------------------------------

/**
 * The floating card the property and rearrange panels live in — the web's
 * 300px panel beside the fields, with its title, a line under it, and a close.
 */
@Composable
internal fun SidePanel(
    title: String,
    subtitle: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    above: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .width(PANEL_WIDTH)
            .clip(PanelShape)
            .background(colors.surface)
            .border(1.dp, colors.border, PanelShape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ZillitTheme.spacing.lg + ZillitTheme.spacing.xs, end = ZillitTheme.spacing.md)
                .padding(vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                above?.invoke(this)
                ZillitText(text = title, style = ZillitTheme.typography.titleSmall, maxLines = 2)
                if (subtitle != null) {
                    ZillitText(
                        text = subtitle,
                        style = ZillitTheme.typography.bodySmall,
                        color = colors.textMuted,
                        maxLines = 2,
                    )
                }
            }
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = "Close panel",
                onClick = onClose,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        content()
    }
}

/** The panel's small uppercase field label — "FIELD NAME", "TYPE". */
@Composable
internal fun PanelLabel(text: String) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
        color = ZillitTheme.colors.textSecondary,
    )
}

// -- icons the design system does not have ------------------------------------------------

/** A light bulb — the tip banner's tile. */
internal val BulbIcon: ImageVector = formIcon("FormBulb") {
    moveTo(9f, 18f); lineTo(15f, 18f)
    moveTo(10f, 21f); lineTo(14f, 21f)
    moveTo(12f, 3f)
    curveTo(8.7f, 3f, 6f, 5.6f, 6f, 8.9f)
    curveTo(6f, 11.1f, 7.2f, 12.7f, 8.4f, 13.8f)
    curveTo(9.1f, 14.4f, 9.5f, 15.1f, 9.5f, 15.9f)
    lineTo(14.5f, 15.9f)
    curveTo(14.5f, 15.1f, 14.9f, 14.4f, 15.6f, 13.8f)
    curveTo(16.8f, 12.7f, 18f, 11.1f, 18f, 8.9f)
    curveTo(18f, 5.6f, 15.3f, 3f, 12f, 3f)
    close()
}

/** Two arrows passing each other — Rearrange. */
internal val SwapIcon: ImageVector = formIcon("FormSwap") {
    moveTo(4f, 8f); lineTo(18f, 8f)
    moveTo(14f, 4f); lineTo(18f, 8f); lineTo(14f, 12f)
    moveTo(20f, 16f); lineTo(6f, 16f)
    moveTo(10f, 12f); lineTo(6f, 16f); lineTo(10f, 20f)
}

/** Six dots — a row that can be dragged. */
internal val GripIcon: ImageVector = formIcon("FormGrip") {
    listOf(9f, 15f).forEach { x ->
        listOf(6f, 12f, 18f).forEach { y ->
            moveTo(x, y); lineTo(x + 0.01f, y)
        }
    }
}

private fun formIcon(name: String, draw: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = VIEWPORT.dp,
        defaultHeight = VIEWPORT.dp,
        viewportWidth = VIEWPORT,
        viewportHeight = VIEWPORT,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = if (name == "FormGrip") GRIP_STROKE else STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = draw,
        )
    }.build()

/** The server's multi-line type, which the add-a-field picker does not offer. */
internal const val TEXTAREA = "textarea"

private const val GRID_COLUMNS = 3
private const val VIEWPORT = 24f
private const val STROKE = 1.75f
private const val GRIP_STROKE = 2.6f
private const val BADGE_RING_ALPHA = 0.25f
private val SectionShape = RoundedCornerShape(14.dp)
private val CellShape = RoundedCornerShape(10.dp)
private val InputShape = RoundedCornerShape(10.dp)
private val PanelShape = RoundedCornerShape(12.dp)
private val HEADER_VERTICAL = 11.dp
private val CELL_PADDING = 8.dp
private val INPUT_HEIGHT = 38.dp
private val INPUT_TALL = 60.dp
private val TABLE_INPUT = 32.dp
private val MIN_COLUMN = 124.dp
private val EDGE_FADE = 36.dp
internal val PANEL_WIDTH = 300.dp
