package com.zillit.desktop.feature.dealmemo.ui.pages.builder

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover

/**
 * Whether the builder is a deal page — its field labels are upper-case at
 * 12.5, a setup page's Title Case at 14 (`.tpl-deal` vs `.tpl-plain`).
 */
internal val LocalDealLabels = compositionLocalOf { false }

@Composable
internal fun ProvideDealLabels(deal: Boolean, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalDealLabels provides deal, content = content)

/** CSS `text-transform: capitalize`: each word's first letter up, the rest as authored. */
internal fun capitalizeWords(text: String): String =
    text.split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }

/** A field label in the builder skin, with an optional amber `*` and a control pushed to its right. */
@Composable
internal fun FieldLabel(
    text: String,
    required: Boolean = false,
    requiredColor: Color? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val p = bp
    val deal = LocalDealLabels.current
    Row(modifier = modifier.fillMaxWidth().padding(bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        ZillitText(
            text = buildAnnotatedString {
                append(if (deal) text.uppercase() else capitalizeWords(text))
                if (required) withStyle(SpanStyle(color = requiredColor ?: p.cta)) { append(" *") }
            },
            style = DmType.sans(if (deal) 12.5.sp else 14.sp, FontWeight.SemiBold, 0.04.em),
            color = p.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.let {
            Spacer(Modifier.width(8.dp))
            it()
        }
    }
}

/** `Field`: label, optional hint between label and control, the control, and its error under it. */
@Composable
internal fun Field(
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    hint: String? = null,
    error: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier) {
        FieldLabel(label, required, trailing = trailing)
        hint?.let { HintText(it, Modifier.padding(bottom = 7.dp)) }
        content()
        error?.let { ErrorText(it) }
    }
}

@Composable
internal fun HintText(text: String, modifier: Modifier = Modifier) {
    ZillitText(text = text, style = DmType.sans(11.5.sp, FontWeight.Medium), color = bp.muted, modifier = modifier)
}

@Composable
internal fun ErrorText(text: String) {
    ZillitText(
        text = text,
        style = DmType.sans(11.sp, FontWeight.Medium),
        color = Color(0xFFDC2626),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** A sub-section heading inside a section — amber, upper-case (`.tpl-subsec-label`). */
@Composable
internal fun SubsectionLabel(text: String, modifier: Modifier = Modifier) {
    ZillitText(
        text = text.uppercase(),
        style = DmType.sans(12.5.sp, FontWeight.Bold, 0.04.em),
        color = bp.sectionLabel,
        modifier = modifier.padding(bottom = 8.dp),
    )
}

/**
 * A text input in the builder skin: 39 tall, radius 10, a hairline that
 * turns amber on focus with a soft ring; red while flagged.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
internal fun BuilderInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    mono: Boolean = false,
    enabled: Boolean = true,
    error: Boolean = false,
    height: Dp = CONTROL_HEIGHT,
    textSize: Float = 14f,
    prefix: String? = null,
    align: TextAlignEnd = TextAlignEnd.Start,
    onBlur: (() -> Unit)? = null,
    onEnter: (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = bp
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(RADIUS)
    val border by animateColorAsState(
        when {
            error -> p.red
            focused -> p.focusBorder
            else -> p.inputBorder
        },
        tween(BORDER_MILLIS),
    )
    val style = if (mono) {
        DmType.mono((textSize - 0.5f).sp, FontWeight.Medium)
    } else {
        DmType.sans(textSize.sp, FontWeight.Normal)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .focusRingBuilder(focused && enabled, p.cta)
            .height(height)
            .clip(shape)
            .background(if (enabled) p.inputBg else p.disabledBg)
            .border(1.dp, border, shape)
            .padding(start = 12.dp, end = if (trailing != null) 6.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        prefix?.takeIf { it.isNotEmpty() }?.let {
            ZillitText(
                text = it,
                style = DmType.sans(12.sp, FontWeight.Medium),
                color = p.muted,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        Box(
            Modifier.weight(1f),
            contentAlignment = if (align == TextAlignEnd.End) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
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
                textStyle = style.copy(
                    color = p.ink,
                    textAlign = if (align == TextAlignEnd.End) TextAlign.End else TextAlign.Start,
                ),
                cursorBrush = SolidColor(p.cta),
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

/** Which edge a control's text sits on. */
internal enum class TextAlignEnd { Start, End }

/** A multi-line text area (`W.inp` rows=3), growing with its text. */
@Composable
internal fun BuilderTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    minHeight: Dp = 70.dp,
) {
    val p = bp
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(RADIUS)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .focusRingBuilder(focused, p.cta)
            .defaultMinSize(minHeight = minHeight)
            .clip(shape)
            .background(p.inputBg)
            .border(1.dp, if (focused) p.focusBorder else p.inputBorder, shape)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        val style = DmType.sans(14.sp)
        if (value.isEmpty()) ZillitText(text = placeholder, style = style, color = p.placeholder)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = style.copy(color = p.ink),
            cursorBrush = SolidColor(p.cta),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
    }
}

/** The 3 dp amber ring just outside a focused control — drawn, so the layout never moves. */
internal fun Modifier.focusRingBuilder(show: Boolean, color: Color, radius: Dp = RADIUS): Modifier =
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

/** A dashed rounded border, drawn — Compose borders have no dash. */
internal fun Modifier.dashedBorder(color: Color, radius: Dp, width: Dp = 1.dp): Modifier = drawBehind {
    val stroke = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH, GAP_DASH))),
    )
}

/** `ToggleSwitch`: a 36×20 pill, amber when on. */
@Composable
internal fun BuilderSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val p = bp
    val track by animateColorAsState(if (checked) p.toggleOn else p.toggleOff, tween(SWITCH_MILLIS))
    val knob by animateDpAsState(if (checked) 18.dp else 4.dp, tween(SWITCH_MILLIS))
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .size(width = 36.dp, height = 20.dp)
            .clip(CircleShape)
            .background(track)
            .then(
                if (enabled) {
                    Modifier.clickable { onChange(!checked) }.pointerHoverIcon(PointerIcon.Hand)
                } else {
                    Modifier
                },
            ),
    ) {
        Box(Modifier.offset(x = knob, y = 3.dp).size(14.dp).clip(CircleShape).background(Color.White))
    }
}

/** `ToggleRow`: a title and its sub-line, the switch on the right. */
@Composable
internal fun ToggleRow(
    title: String,
    sub: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val p = bp
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            ZillitText(text = title, style = DmType.sans(13.sp, FontWeight.SemiBold), color = p.ink)
            sub?.let {
                ZillitText(
                    text = it,
                    style = DmType.sans(11.5.sp),
                    color = p.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        BuilderSwitch(checked, onChange, enabled)
    }
}

/** The skinned `Alert`: grey on grey, a small icon chip, whatever the variant was on the wizard. */
@Composable
internal fun BuilderAlert(text: String, icon: ImageVector = ZillitIcons.Info, modifier: Modifier = Modifier) =
    BuilderAlert(AnnotatedString(text), icon, modifier)

/** [BuilderAlert] with bold runs — `**Picture Deal** — …`. */
@Composable
internal fun BuilderAlert(text: AnnotatedString, icon: ImageVector = ZillitIcons.Info, modifier: Modifier = Modifier) {
    val p = bp
    val shape = RoundedCornerShape(RADIUS)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(p.alertBg)
            .border(1.dp, p.alertBorder, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, p.alertBorder, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) { ZillitIcon(icon, size = 12.dp, tint = p.muted) }
        ZillitText(
            text = text,
            style = DmType.sans(12.5.sp).copy(lineHeight = 18.sp),
            color = p.alertInk,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A wizard card as the builder skin flattens it: its title and tag over its blocks, no box. */
@Composable
internal fun CardBlock(
    title: String?,
    modifier: Modifier = Modifier,
    tag: String? = null,
    tone: BuilderTone = BuilderTone.Dim,
    headerTrailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        if (title != null || tag != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZillitText(
                    text = title.orEmpty(),
                    style = DmType.sans(13.5.sp, FontWeight.Bold),
                    color = bp.ink2,
                    modifier = Modifier.weight(1f),
                )
                headerTrailing?.invoke(this)
                tag?.let { BuilderTag(it, tone) }
            }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), content = content)
    }
}

/** The web `Tag` hues. */
@Suppress("MagicNumber") // The web's swatches, verbatim.
internal enum class BuilderTone(val light: Color, val dark: Color, val wash: Color?) {
    Gold(Color(0xFFD4A030), Color(0xFFE8B84B), Color(0xFFFC9404)),
    Blue(Color(0xFF2563EB), Color(0xFF60A5FA), Color(0xFF3B82F6)),
    Purple(Color(0xFF9333EA), Color(0xFFC084FC), Color(0xFFA855F7)),
    Teal(Color(0xFF0D9488), Color(0xFF2DD4BF), Color(0xFF14B8A6)),
    Green(Color(0xFF16A34A), Color(0xFF4ADE80), Color(0xFF22C55E)),
    Amber(Color(0xFFD97706), Color(0xFFFBBF24), Color(0xFFF59E0B)),
    Red(Color(0xFFDC2626), Color(0xFFF87171), Color(0xFFEF4444)),
    Dim(Color(0xFF6B7280), Color(0xFF8B95A8), null),
}

@Composable
internal fun BuilderTag(
    text: String,
    tone: BuilderTone,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val dark = ZillitTheme.colors.isDark
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(tone.wash?.copy(alpha = TAG_WASH) ?: bp.tile)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        leading?.invoke()
        ZillitText(
            text = text,
            style = DmType.sans(12.sp, FontWeight.Medium, 0.025.em),
            color = if (dark) tone.dark else tone.light,
            maxLines = 1,
        )
    }
}

/** The dashed add button — `+ Add Condition`, `+ Add Bureau`, `+ Add custom day`. */
@Composable
internal fun DashedAddButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 32.dp) {
    val p = bp
    val (source, hovered) = rememberHover()
    val ink = if (hovered) p.cta else p.ink2
    Row(
        modifier = modifier
            .height(height)
            .dashedBorder(if (hovered) p.cta else p.dashed, 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitIcon(ZillitIcons.Add, size = 11.dp, tint = ink)
        ZillitText(
            text = label.removePrefix("+ "),
            style = DmType.sans(12.5.sp, FontWeight.SemiBold),
            color = ink,
            maxLines = 1,
        )
    }
}

/** The square red `×` beside a list row. */
@Composable
internal fun RemoveButton(tooltip: String, onClick: () -> Unit, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val p = bp
    val (source, hovered) = rememberHover()
    ZillitTooltip(text = tooltip) {
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
                .background(if (hovered) p.redSoft else Color.Transparent)
                .hoverable(source)
                .clickable(interactionSource = source, indication = null, onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand),
            contentAlignment = Alignment.Center,
        ) { ZillitText(text = "×", style = DmType.sans(19.sp, FontWeight.Medium), color = p.red) }
    }
}

/** A pill chip (notice period, work location): amber or teal when chosen. */
@Composable
internal fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, teal: Boolean = false) {
    val p = bp
    val (source, hovered) = rememberHover()
    val accent = if (teal) p.teal else p.cta
    val soft = if (teal) p.tealSoft else p.amberSoft
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) soft else p.chipBg)
            .border(1.5.dp, if (selected) accent else if (hovered) p.menuBorder else p.hairline, CircleShape)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        ZillitText(
            text = label,
            style = DmType.sans(12.5.sp, FontWeight.Bold),
            color = if (selected) accent else p.ink2,
            maxLines = 1,
        )
    }
}

/** `infoBox`: a quiet panel for a notice. */
@Composable
internal fun InfoBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = bp
    val shape = RoundedCornerShape(RADIUS)
    Column(
        modifier = modifier.fillMaxWidth()
            .clip(shape)
            .background(p.infoBox)
            .border(1.dp, p.infoBorder, shape)
            .padding(14.dp),
        content = content,
    )
}

/** An italic empty-state line. */
@Composable
internal fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    ZillitText(
        text = text,
        style = DmType.sans(12.5.sp).copy(fontStyle = FontStyle.Italic),
        color = bp.muted,
        modifier = modifier,
    )
}

/**
 * The step grids (`grid2` / `grid3`): equal columns 18 apart, a cell may span
 * several; below 640 everything stacks.
 */
@Composable
internal fun BuilderGrid(
    columns: Int,
    modifier: Modifier = Modifier,
    gap: Dp = 18.dp,
    verticalAlignment: Alignment.Vertical = Alignment.Bottom,
    build: BuilderGridScope.() -> Unit,
) {
    val cells = BuilderGridScope().apply(build).cells
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val stacked = maxWidth < STACK_BELOW
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            if (stacked) {
                cells.forEach { it.content() }
            } else {
                rowsOf(cells, columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = verticalAlignment) {
                        var used = 0
                        row.forEach { cell ->
                            Box(Modifier.weight(cell.span.toFloat())) { cell.content() }
                            used += cell.span
                        }
                        if (used < columns) Spacer(Modifier.weight((columns - used).toFloat()))
                    }
                }
            }
        }
    }
}

internal class BuilderGridCell(val span: Int, val content: @Composable () -> Unit)

internal class BuilderGridScope {
    val cells = mutableListOf<BuilderGridCell>()

    fun cell(span: Int = 1, content: @Composable () -> Unit) {
        cells += BuilderGridCell(span, content)
    }
}

private fun rowsOf(cells: List<BuilderGridCell>, columns: Int): List<List<BuilderGridCell>> {
    val rows = mutableListOf<List<BuilderGridCell>>()
    var current = mutableListOf<BuilderGridCell>()
    var used = 0
    cells.forEach { cell ->
        val span = cell.span.coerceIn(1, columns)
        if (used + span > columns) {
            rows += current
            current = mutableListOf()
            used = 0
        }
        current += cell
        used += span
    }
    if (current.isNotEmpty()) rows += current
    return rows
}

/** A 1 px rule across a block. */
@Composable
internal fun Rule(color: Color = bp.hairline, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** A small action link in a label row — `+ ADD COMPANY`. */
@Composable
internal fun LabelAction(text: String, onClick: () -> Unit) {
    val p = bp
    val (source, hovered) = rememberHover()
    Row(
        modifier = Modifier
            .alpha(if (hovered) HOVER_ALPHA else 1f)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ZillitIcon(ZillitIcons.Add, size = 9.dp, tint = p.sectionLabel)
        ZillitText(
            text = text.uppercase(),
            style = DmType.sans(10.sp, FontWeight.Bold, 0.08.em),
            color = p.sectionLabel,
            maxLines = 1,
        )
    }
}

/** A read-only box styled as an input — Production Type. */
@Composable
internal fun ReadOnlyBox(text: String?, emptyText: String, tooltip: String? = null) {
    val p = bp
    val shape = RoundedCornerShape(RADIUS)
    val box: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = CONTROL_HEIGHT)
                .clip(shape)
                .background(p.inputBg)
                .border(1.dp, p.inputBorder, shape)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isNullOrBlank()) {
                ZillitText(
                    text = emptyText,
                    style = DmType.sans(14.sp).copy(fontStyle = FontStyle.Italic),
                    color = p.muted,
                )
            } else {
                ZillitText(
                    text = text,
                    style = DmType.sans(14.sp),
                    color = p.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    if (tooltip != null) ZillitTooltip(text = tooltip) { box() } else box()
}

internal val CONTROL_HEIGHT = 39.dp
internal val RADIUS = 10.dp
private val RING = 3.dp
private val STACK_BELOW = 640.dp
private const val RING_ALPHA = 0.10f
private const val DISABLED_ALPHA = 0.5f
private const val HOVER_ALPHA = 0.8f
private const val TAG_WASH = 0.15f
private const val BORDER_MILLIS = 250
private const val SWITCH_MILLIS = 150
private const val DASH = 6f
private const val GAP_DASH = 4f
