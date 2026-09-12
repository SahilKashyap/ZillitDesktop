package com.zillit.desktop.feature.taxfiling.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText

/** The web's `Btn` variants, by what the button does. */
internal enum class MtdButtonVariant { Primary, Secondary, Ghost, Green, Danger, RedSolid }

internal enum class MtdButtonSize(val minHeight: Dp, val horizontal: Dp, val font: TextUnit, val icon: Dp) {
    Small(minHeight = 32.dp, horizontal = 12.dp, font = 12.5.sp, icon = 14.dp),
    Medium(minHeight = 38.dp, horizontal = 15.dp, font = 13.5.sp, icon = 15.dp),
    Large(minHeight = 44.dp, horizontal = 18.dp, font = 14.sp, icon = 15.dp),
}

private val ButtonShape = RoundedCornerShape(10.dp)

/**
 * The filing surface's button — the web's `Btn`.
 *
 * Larger and softer than the app's compact toolbar button: this surface asks
 * for a handful of deliberate actions, and the web draws them as such — an
 * orange call to action with dark ink, a green submit, a red remove. A
 * disabled button stays in place, faded, so the row does not reflow when a
 * requirement is met.
 */
@Composable
internal fun MtdButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: MtdButtonVariant = MtdButtonVariant.Secondary,
    size: MtdButtonSize = MtdButtonSize.Medium,
    icon: ImageVector? = null,
    iconRight: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    full: Boolean = false,
    /** The accent-tinted secondary the "Open VAT return" button wears once it can open. */
    tinted: Boolean = false,
) {
    val palette = mtdPalette()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val active = enabled && !loading
    val look = look(variant, palette, hovered && active, tinted)
    val background by animateColorAsState(look.background, label = "mtdButton")
    val glow = if (active) look.glow else null

    Row(
        modifier = modifier
            .then(if (full) Modifier.fillMaxWidth() else Modifier)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .then(
                if (glow != null) {
                    Modifier.shadow(6.dp, ButtonShape, clip = false, ambientColor = glow, spotColor = glow)
                } else {
                    Modifier
                },
            )
            .defaultMinSize(minHeight = size.minHeight)
            .clip(ButtonShape)
            .background(background)
            .then(look.border?.let { Modifier.border(it, ButtonShape) } ?: Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = active,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = size.horizontal),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            loading -> ZillitSpinner(size = size.icon, color = look.content)
            icon != null -> ZillitIcon(icon = icon, tint = look.content, size = size.icon)
        }
        ZillitText(
            text = text,
            style = mtdText(size.font, FontWeight.SemiBold, tracking = (-0.01).em),
            color = look.content,
            maxLines = 1,
        )
        if (iconRight != null && !loading) ZillitIcon(icon = iconRight, tint = look.content, size = size.icon)
    }
}

private data class ButtonLook(
    val background: Color,
    val content: Color,
    val border: BorderStroke? = null,
    val glow: Color? = null,
)

private fun look(variant: MtdButtonVariant, palette: MtdPalette, hovered: Boolean, tinted: Boolean): ButtonLook =
    when (variant) {
        MtdButtonVariant.Primary -> palette.primaryLook(hovered)
        MtdButtonVariant.Secondary -> if (tinted) palette.tintedLook(hovered) else palette.secondaryLook(hovered)
        MtdButtonVariant.Ghost -> ButtonLook(
            background = if (hovered) palette.surface3 else Color.Transparent,
            content = palette.ink2,
        )
        MtdButtonVariant.Green -> palette.solidLook(palette.green, hovered)
        MtdButtonVariant.Danger -> palette.dangerLook(hovered)
        MtdButtonVariant.RedSolid -> palette.solidLook(palette.red, hovered)
    }

private fun MtdPalette.primaryLook(hovered: Boolean) = ButtonLook(
    background = if (hovered) accentHover else accent,
    content = accentInk,
    glow = accent.copy(alpha = GLOW),
)

private fun MtdPalette.tintedLook(hovered: Boolean) = ButtonLook(
    background = if (hovered) accentBorder else accentWash,
    content = accentText,
    border = BorderStroke(1.dp, accentBorder),
)

private fun MtdPalette.secondaryLook(hovered: Boolean) = ButtonLook(
    background = if (hovered) surface3 else surface,
    content = ink,
    border = BorderStroke(1.dp, border2),
)

private fun MtdPalette.dangerLook(hovered: Boolean) = ButtonLook(
    background = if (hovered) redWash else surface,
    content = red,
    border = BorderStroke(1.dp, if (hovered) redBorder else border2),
)

/** A filled button in [tone] — green submit, red remove — with ink that reads on it in either theme. */
private fun MtdPalette.solidLook(tone: Color, hovered: Boolean) = ButtonLook(
    background = if (hovered) tone.copy(alpha = HOVER_DIM) else tone,
    content = if (isDark) accentInk else surface,
    glow = tone.copy(alpha = GLOW),
)

private const val DISABLED_ALPHA = 0.5f
private const val GLOW = 0.45f
private const val HOVER_DIM = 0.88f
