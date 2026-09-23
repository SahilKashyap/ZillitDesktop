package com.zillit.desktop.feature.taxfiling.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/*
 * The filing surface's small pieces — the web's `mtd-ui.jsx` — in Compose.
 * Read together: a card holds a section head over a strip of summary stats,
 * and the three only make sense next to each other.
 */

internal val CardShape = RoundedCornerShape(16.dp)
/** The app's own field corner, so these fields sit beside its text and date fields without a seam. */
internal val FieldShape = RoundedCornerShape(6.dp)

/**
 * The web's `Card`: a white panel, a hairline border and a whisper of shadow.
 * [hoverLift] deepens both as the pointer arrives, as the registration and
 * filing cards do.
 */
@Composable
internal fun MtdCard(
    modifier: Modifier = Modifier,
    padding: Dp = 22.dp,
    hoverLift: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = mtdPalette()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lifted = hoverLift && hovered
    val elevation by animateDpAsState(if (lifted) 10.dp else 1.dp, label = "cardLift")
    val edge by animateColorAsState(if (lifted) palette.border2 else palette.border, label = "cardEdge")
    Column(
        modifier = modifier
            .shadow(elevation, CardShape, clip = false, ambientColor = shadowTint, spotColor = shadowTint)
            .clip(CardShape)
            .background(palette.surface)
            .border(1.dp, edge, CardShape)
            .then(if (hoverLift) Modifier.hoverable(interaction) else Modifier)
            .padding(padding),
        content = content,
    )
}

private val shadowTint = Color.Black.copy(alpha = 0.18f)

/** Title, a line under it, and whatever sits on the right — the web's `SectionHead`. */
@Composable
internal fun MtdSectionHead(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    right: (@Composable RowScope.() -> Unit)? = null,
) {
    val palette = mtdPalette()
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            ZillitText(
                text = title,
                style = mtdText(16.5.sp, FontWeight.Bold, tracking = (-0.02).em),
                color = palette.ink,
            )
            if (subtitle != null) {
                ZillitText(
                    text = subtitle,
                    style = mtdText(13.5.sp),
                    color = palette.ink3,
                    modifier = Modifier.widthIn(max = 640.dp),
                )
            }
        }
        if (right != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = right,
            )
        }
    }
}

/** A hairline rule across a card. */
@Composable
internal fun MtdRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(mtdPalette().divider))
}

/** The web's pill tones, by what they announce. */
internal enum class PillTone { Active, Connected, Computed, Auto, Zero, Open, Fulfilled, Neutral }

/** One choice: a label, an optional quiet line under it, and an optional status pill. */
internal data class MtdOption<T>(
    val value: T,
    val label: String,
    val sub: String? = null,
    val pill: Pair<String, PillTone>? = null,
)

/** A small rounded uppercase status word — the web's `Pill`. */
@Composable
internal fun MtdPill(
    text: String,
    tone: PillTone,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    leading: ImageVector? = null,
) {
    val palette = mtdPalette()
    val (wash, ink, edge) = when (tone) {
        PillTone.Active, PillTone.Connected -> Triple(palette.greenWash, palette.green, palette.greenBorder)
        PillTone.Auto -> Triple(palette.blueWash, palette.blue, palette.blueBorder)
        PillTone.Zero -> Triple(palette.amberWash, palette.amber, palette.amberBorder)
        PillTone.Open -> Triple(palette.accentWash, palette.accentText, palette.accentBorder)
        PillTone.Computed, PillTone.Fulfilled, PillTone.Neutral ->
            Triple(palette.surface3, palette.ink3, palette.border)
    }
    val dotted = tone == PillTone.Active || tone == PillTone.Connected || tone == PillTone.Open
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(wash)
            .border(1.dp, edge, RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            leading != null -> ZillitIcon(icon = leading, tint = ink, size = 12.dp)
            dotted && !mono -> Box(Modifier.size(5.5.dp).clip(CircleShape).background(dotColor(tone, palette)))
        }
        ZillitText(
            text = if (mono) text else text.uppercase(),
            style = mtdText(10.5.sp, FontWeight.Bold, mono = mono, tracking = 0.05.em),
            color = ink,
            maxLines = 1,
        )
    }
}

private fun dotColor(tone: PillTone, palette: MtdPalette): Color =
    if (tone == PillTone.Open) palette.accent else palette.green

/**
 * A picked code, layer or tag — the web's `TagChip`. [solid] is the accent
 * fill the layers wear; the outline is for tags.
 */
@Composable
internal fun MtdTagChip(
    text: String,
    modifier: Modifier = Modifier,
    solid: Boolean = false,
    onRemove: (() -> Unit)? = null,
) {
    val palette = mtdPalette()
    val background = if (solid) palette.accent else palette.accentWash
    val ink = if (solid) palette.accentInk else palette.accentText
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(background)
            .border(1.dp, if (solid) palette.accent else palette.accentBorder, RoundedCornerShape(7.dp))
            .padding(start = 8.dp, end = if (onRemove == null) 8.dp else 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ZillitText(
            text = text.uppercase(),
            style = mtdText(11.sp, FontWeight.Bold, mono = true, tracking = 0.04.em),
            color = ink,
            maxLines = 1,
        )
        if (onRemove != null) {
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = str(S.bs_chip_remove, text),
                onClick = onRemove,
                tint = ink,
                size = 18.dp,
            )
        }
    }
}

/** A company's initial on the accent wash — the web's `Avatar`. */
@Composable
internal fun MtdAvatar(name: String, size: Dp, modifier: Modifier = Modifier) {
    val palette = mtdPalette()
    val shape = RoundedCornerShape(size * AVATAR_RADIUS)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(palette.accentWash)
            .border(1.dp, palette.accentBorder, shape),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = name.trim().firstOrNull()?.uppercase() ?: "?",
            style = mtdText((size.value * AVATAR_TEXT).sp, FontWeight.ExtraBold),
            color = palette.accentText,
            textAlign = TextAlign.Center,
        )
    }
}

private const val AVATAR_RADIUS = 0.28f
private const val AVATAR_TEXT = 0.4f

/** An icon on the accent wash, in a rounded square — title blocks, empty states, dialogs. */
@Composable
internal fun MtdIconTile(
    icon: ImageVector,
    size: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    radius: Dp = 14.dp,
    tint: Color? = null,
    wash: Color? = null,
    edge: Color? = null,
) {
    val palette = mtdPalette()
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(wash ?: palette.accentWash)
            .border(1.dp, edge ?: palette.accentBorder, shape),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon = icon, tint = tint ?: palette.accent, size = iconSize)
    }
}

/** The faint middle dot between facts on one line. */
@Composable
internal fun MtdDot() {
    ZillitText(text = "·", style = mtdText(13.sp), color = mtdPalette().faint)
}

/** A short upright rule between the facts in a summary strip. */
@Composable
internal fun MtdStripDivider() {
    Box(Modifier.padding(horizontal = 16.dp).width(1.dp).height(30.dp).background(mtdPalette().border))
}

/** An eyebrow over a value — the web's `SummaryStat`. */
@Composable
internal fun MtdSummaryStat(label: String, value: String, mono: Boolean = false) {
    val palette = mtdPalette()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ZillitText(text = label.uppercase(), style = mtdEyebrow(), color = palette.muted)
        ZillitText(
            text = value,
            style = mtdText(13.sp, FontWeight.SemiBold, mono = mono, tracking = if (mono) 0.02.em else 0.em),
            color = palette.ink,
            maxLines = 1,
        )
    }
}

/** A field's label, with an optional quiet hint beside it. */
@Composable
internal fun MtdFieldLabel(text: String, modifier: Modifier = Modifier, hint: String? = null) {
    val palette = mtdPalette()
    Row(
        modifier = modifier.padding(bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitText(text = text, style = mtdText(12.5.sp, FontWeight.SemiBold), color = palette.ink2)
        if (hint != null) ZillitText(text = hint, style = mtdText(11.5.sp, FontWeight.Medium), color = palette.muted)
    }
}

/** "BOX / 6" — the badge every box row starts with. [highlight] is box 5's accent. */
@Composable
internal fun MtdBoxBadge(number: Int, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val palette = mtdPalette()
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .size(38.dp)
            .clip(shape)
            .background(if (highlight) palette.surface else palette.surface3)
            .border(1.dp, if (highlight) palette.accentBorder else palette.border, shape),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ZillitText(
            text = str(S.desktop_tax_box_caps),
            style = mtdText(7.5.sp, FontWeight.Bold, tracking = 0.08.em, lineHeight = 9.sp),
            color = if (highlight) palette.accentText else palette.muted,
        )
        ZillitText(
            text = number.toString(),
            style = mtdText(15.sp, FontWeight.Bold, mono = true, lineHeight = 17.sp),
            color = if (highlight) palette.accentText else palette.ink,
        )
    }
}

/**
 * Pulsing placeholder rows — the web's `TableSkeleton`: a short first column,
 * flexible middles and a short right-aligned last one.
 */
@Composable
internal fun MtdSkeletonRows(rows: Int = 4, columns: Int = 4) {
    val palette = mtdPalette()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        repeat(rows) { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(columns) { column ->
                    val cell = when (column) {
                        0 -> Modifier.width(160.dp)
                        columns - 1 -> Modifier.width(96.dp)
                        else -> Modifier.weight(1f)
                    }
                    ZillitSkeletonBar(modifier = cell, height = 14.dp)
                }
            }
            if (row < rows - 1) Box(Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
        }
    }
}
