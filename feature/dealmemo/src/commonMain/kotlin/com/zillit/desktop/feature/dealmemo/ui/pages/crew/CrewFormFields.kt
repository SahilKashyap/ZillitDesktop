package com.zillit.desktop.feature.dealmemo.ui.pages.crew

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.dealmemo.domain.preview.MemoFormat
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import com.zillit.desktop.feature.dealmemo.ui.pages.preview.shadowed
import com.zillit.desktop.feature.dealmemo.ui.pages.rules.BelowStartPosition

/** A section card (`Card` with the wizard's `cardCls`): an optional titled header with its tag, then the body. */
@Composable
internal fun SectionCard(
    title: String?,
    tag: String? = null,
    tone: TagTone = TagTone.Dim,
    accent: CardAccent = CardAccent.None,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = cp
    val dark = ZillitTheme.colors.isDark
    val shape = RoundedCornerShape(16.dp)
    val (border, top) = when (accent) {
        CardAccent.None -> p.cardBorder to p.card
        CardAccent.Red -> p.redBorder to p.redTop
        CardAccent.Blue -> p.blueBorder to p.blueTop
        CardAccent.Teal -> p.tealBorder to p.tealTop
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(top, p.card)))
            .border(1.dp, border, shape),
    ) {
        if (title != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = title,
                    style = DmType.sans(14.sp, FontWeight.Bold),
                    color = p.title,
                    modifier = Modifier.weight(1f),
                )
                tag?.let {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(tone.wash?.copy(alpha = TAG_WASH) ?: p.tile)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        ZillitText(
                            text = it,
                            style = DmType.sans(12.sp, FontWeight.Medium, 0.025.em),
                            color = if (dark) tone.dark else tone.light,
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.headerRule))
        }
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), content = content)
    }
}

/** `RowLabel`: DM Mono 11, bold, upper-case — darker than the wizard's label on purpose — and a red asterisk. */
@Composable
internal fun RowLabel(text: String, required: Boolean = false) {
    ZillitText(
        text = buildAnnotatedString {
            append(text.uppercase())
            if (required) withStyle(SpanStyle(color = Color(0xFFEF4444))) { append(" *") }
        },
        style = DmType.mono(11.sp, FontWeight.Bold, 0.09.em),
        color = cp.label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(7.dp))
}

/** The wizard's `Field` label, which the UK fieldset keeps on both surfaces; its hint sits under it. */
@Composable
internal fun WizLabel(text: String, hint: String? = null) {
    val p = cp
    ZillitText(
        text = text.uppercase(),
        style = DmType.mono(11.sp, FontWeight.Bold, 0.09.em),
        color = p.wizLabel,
        maxLines = 1,
    )
    Spacer(Modifier.height(7.dp))
    hint?.let {
        ZillitText(
            text = it,
            style = DmType.sans(11.5.sp, FontWeight.Medium),
            color = p.muted,
            modifier = Modifier.padding(bottom = 7.dp),
        )
    }
}

/** `Ro`: a value the production accountant manages — unboxed, so it never looks typeable. */
@Composable
internal fun ReadOnlyRow(label: String, value: String?, mono: Boolean = false) {
    Column {
        RowLabel(label)
        ZillitText(
            text = value?.takeIf { it.isNotBlank() } ?: MemoFormat.DASH,
            style = if (mono) DmType.mono(12.5.sp) else DmType.sans(13.5.sp, FontWeight.Medium),
            color = cp.ink,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** `Ed`: the inline error, in flow directly under its control. */
@Composable
internal fun InlineError(text: String?) {
    text ?: return
    ZillitText(
        text = text,
        style = DmType.sans(11.sp, FontWeight.Medium),
        color = cp.error,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** A labelled text input (`INP`) with its in-flow error. */
@Composable
internal fun LabelledInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    required: Boolean = false,
    error: String? = null,
    mono: Boolean = false,
    onBlur: (() -> Unit)? = null,
) {
    Column {
        RowLabel(label, required)
        FormInput(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            mono = mono,
            error = error != null,
            onBlur = onBlur,
        )
        InlineError(error)
    }
}

/**
 * `W.inp`: 44 tall, white, a hairline; focus turns the border amber with a
 * soft outer ring. An emptied box hands back `""`.
 */
@Composable
internal fun FormInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    enabled: Boolean = true,
    error: Boolean = false,
    onBlur: (() -> Unit)? = null,
    onEnter: (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
    height: Dp = 44.dp,
    textSize: Float = 13.5f,
) {
    val p = cp
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(RADIUS)
    val style = if (mono) {
        DmType.mono((textSize - 0.5f).sp, FontWeight.Medium)
    } else {
        DmType.sans(textSize.sp, FontWeight.Medium)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED)
            .focusRing(focused && enabled, RADIUS, p.amber)
            .height(height)
            .clip(shape)
            .background(p.inputBg)
            .border(
                1.dp,
                when {
                    error -> Color(0xFFF87171)
                    focused -> p.focusBorder
                    else -> p.inputBorder
                },
                shape,
            )
            .padding(start = 14.dp, end = if (trailing != null) 6.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                ZillitText(
                    text = placeholder,
                    style = style,
                    color = p.placeholder,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = style.copy(color = p.ink),
                cursorBrush = SolidColor(p.amber),
                visualTransformation = visualTransformation,
                keyboardActions = KeyboardActions(onDone = { onEnter?.invoke() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state ->
                        if (focused && !state.isFocused) onBlur?.invoke()
                        focused = state.isFocused
                    },
            )
        }
        trailing?.invoke()
    }
}

/** A 3dp ring just outside the control while it has focus — drawn, so it never moves the layout. */
internal fun Modifier.focusRing(show: Boolean, radius: Dp, color: Color): Modifier =
    if (!show) {
        this
    } else {
        drawBehind {
            val ring = RING.toPx()
            drawRoundRect(
                color = color.copy(alpha = RING_ALPHA),
                topLeft = Offset(-ring / 2, -ring / 2),
                size = Size(size.width + ring, size.height + ring),
                cornerRadius = CornerRadius(radius.toPx() + ring / 2),
                style = Stroke(ring),
            )
        }
    }

/** A native `<select>` (`W.sel`) with its label; "— Select —" first, so a choice can be taken back. */
@Composable
internal fun LabelledSelect(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    required: Boolean = false,
) {
    Column {
        RowLabel(label, required)
        FormSelect(value, listOf("" to SELECT_PLACEHOLDER) + options, onPick)
    }
}

@Composable
internal fun FormSelect(
    value: String,
    options: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    menuWidth: Dp = 280.dp,
    textSize: Float = 13.5f,
) {
    val p = cp
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(RADIUS)
    val shown = options.firstOrNull { it.first == value }?.second ?: value
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusRing(open, RADIUS, p.amber)
                .height(height)
                .clip(shape)
                .background(p.inputBg)
                .border(1.dp, if (open) p.focusBorder else p.inputBorder, shape)
                .clickable { open = !open }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(start = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = shown,
                style = DmType.sans(textSize.sp, FontWeight.SemiBold),
                color = if (value.isEmpty()) p.muted else p.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ZillitIcon(ZillitIcons.ChevronDown, size = 11.dp, tint = p.muted)
        }
        if (open) {
            Popup(
                popupPositionProvider = remember { BelowStartPosition(gap = 6) },
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                val menuShape = RoundedCornerShape(12.dp)
                Column(
                    modifier = Modifier
                        .width(menuWidth)
                        .shadowed(menuShape)
                        .clip(menuShape)
                        .background(p.menu)
                        .border(1.dp, p.pickerBorder, menuShape)
                        .padding(5.dp),
                ) {
                    options.forEach { (optionValue, optionLabel) ->
                        MenuRow(optionLabel, optionValue == value, muted = optionValue.isEmpty()) {
                            open = false
                            onPick(optionValue)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, selected: Boolean, muted: Boolean, onClick: () -> Unit) {
    val p = cp
    val (source, hovered) = rememberHover()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected && !muted -> p.selected
                    hovered -> p.hover
                    else -> Color.Transparent
                },
            )
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = label,
            style = DmType.sans(13.sp, if (selected && !muted) FontWeight.SemiBold else FontWeight.Medium),
            color = if (muted) p.muted else p.ink,
            modifier = Modifier.weight(1f),
        )
        if (selected && !muted) ZillitIcon(ZillitIcons.Check, size = 12.dp, tint = p.amber)
    }
}

/** A cell of a [FormGrid]: half a row, or all of it. */
internal class GridCell(val wide: Boolean, val content: @Composable () -> Unit)

internal class FormGridScope {
    val cells = mutableListOf<GridCell>()

    fun half(content: @Composable () -> Unit) {
        cells += GridCell(wide = false, content = content)
    }

    fun wide(content: @Composable () -> Unit) {
        cells += GridCell(wide = true, content = content)
    }
}

/**
 * The crew form's grid: two columns 18 apart; a full-row cell takes a row of
 * its own — a half cell just before it keeps its row to itself, as CSS
 * auto-placement does — and everything stacks under 640.
 */
@Composable
internal fun FormGrid(modifier: Modifier = Modifier, build: FormGridScope.() -> Unit) {
    val cells = FormGridScope().apply(build).cells
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val single = maxWidth < SINGLE_COLUMN_BELOW
        Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
            if (single) {
                cells.forEach { it.content() }
            } else {
                gridRows(cells).forEach { row ->
                    if (row.size == 1 && row[0].wide) {
                        row[0].content()
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(GAP), verticalAlignment = Alignment.Top) {
                            Box(Modifier.weight(1f)) { row[0].content() }
                            Box(Modifier.weight(1f)) { row.getOrNull(1)?.content?.invoke() }
                        }
                    }
                }
            }
        }
    }
}

private fun gridRows(cells: List<GridCell>): List<List<GridCell>> {
    val rows = mutableListOf<List<GridCell>>()
    var pending: GridCell? = null
    cells.forEach { cell ->
        val open = pending
        when {
            cell.wide -> {
                if (open != null) rows += listOf(open)
                pending = null
                rows += listOf(cell)
            }
            open == null -> pending = cell
            else -> {
                rows += listOf(open, cell)
                pending = null
            }
        }
    }
    pending?.let { rows += listOf(it) }
    return rows
}

internal const val SELECT_PLACEHOLDER = "— Select —"
private const val DISABLED = 0.6f
private const val TAG_WASH = 0.15f
private const val RING_ALPHA = 0.10f
private val RING = 3.dp
private val RADIUS = 10.dp
private val GAP = 18.dp
private val SINGLE_COLUMN_BELOW = 640.dp
