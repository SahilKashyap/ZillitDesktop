package com.zillit.desktop.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitColors
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme

/**
 * Visual weight of an action. Naming the intent rather than the colours means a
 * screen never picks a shade, and both themes stay correct for free.
 */
enum class ButtonVariant { Primary, Secondary, Tertiary, Danger }

enum class ButtonSize { Small, Medium }

/**
 * The app's only button.
 *
 * Every variant, size, icon placement and state is a parameter rather than a
 * separate composable, so there is one hover/press/disabled implementation to
 * get right instead of a dozen that drift apart.
 */
@Composable
fun ZillitButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    size: ButtonSize = ButtonSize.Medium,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val spacing = ZillitTheme.spacing
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()

    val active = enabled && !loading
    val style = variant.style(active = active, hovered = hovered, pressed = pressed)
    val background by animateColorAsState(style.background, label = "buttonBackground")

    val height = when (size) {
        ButtonSize.Small -> ZillitDimens.controlHeightSmall
        ButtonSize.Medium -> ZillitDimens.controlHeight
    }
    val horizontalPadding = if (size == ButtonSize.Small) spacing.sm else spacing.md

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = height)
            .clip(ZillitTheme.shapes.medium)
            .background(background)
            .then(
                style.border?.let { Modifier.border(it, ZillitTheme.shapes.medium) } ?: Modifier,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = active,
                onClick = onClick,
            )
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            loading -> ZillitSpinner(size = ZillitDimens.iconSmall, color = style.content)
            leadingIcon != null -> ZillitIcon(leadingIcon, tint = style.content, size = iconSize(size))
        }
        ZillitText(text = text, style = ZillitTheme.typography.button, color = style.content)
        trailingIcon?.let { ZillitIcon(it, tint = style.content, size = iconSize(size)) }
    }
}

/** Square icon-only button — toolbars, tab close, window controls. */
@Composable
fun ZillitIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
    size: Dp = ZillitDimens.controlHeightSmall,
    /**
     * The accent-filled circle the web's composer buttons wear. Plain stays
     * the default — a filled button asks for attention, and most icon buttons
     * should not.
     */
    filled: Boolean = false,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val background by animateColorAsState(
        iconButtonBackground(colors, filled = filled, enabled = enabled, hovered = hovered),
        label = "iconButtonBackground",
    )

    Row(
        modifier = modifier
            .size(size)
            .clip(if (filled) ZillitTheme.shapes.pill else ZillitTheme.shapes.small)
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitIcon(
            icon = icon,
            contentDescription = contentDescription,
            tint = iconButtonTint(colors, filled = filled, enabled = enabled, hovered = hovered, override = tint),
        )
    }
}

private fun iconButtonBackground(
    colors: com.zillit.desktop.core.designsystem.ZillitColors,
    filled: Boolean,
    enabled: Boolean,
    hovered: Boolean,
): Color = when {
    filled && !enabled -> colors.surfaceHover
    filled && hovered -> colors.accentHover
    filled -> colors.accent
    hovered && enabled -> colors.surfaceHover
    else -> Color.Transparent
}

private fun iconButtonTint(
    colors: com.zillit.desktop.core.designsystem.ZillitColors,
    filled: Boolean,
    enabled: Boolean,
    hovered: Boolean,
    override: Color?,
): Color = when {
    !enabled -> colors.textDisabled
    filled -> colors.textOnAccent
    override != null -> override
    hovered -> colors.textPrimary
    else -> colors.textSecondary
}

private fun iconSize(size: ButtonSize): Dp =
    if (size == ButtonSize.Small) ZillitDimens.iconSmall else ZillitDimens.icon

private data class ButtonStyle(
    val background: Color,
    val content: Color,
    val border: BorderStroke?,
)

@Composable
private fun ButtonVariant.style(active: Boolean, hovered: Boolean, pressed: Boolean): ButtonStyle {
    val colors = ZillitTheme.colors
    if (!active) return disabledStyle(colors)
    return when (this) {
        ButtonVariant.Primary -> primaryStyle(colors, hovered, pressed)
        ButtonVariant.Secondary -> secondaryStyle(colors, hovered)
        ButtonVariant.Tertiary -> tertiaryStyle(colors, hovered)
        ButtonVariant.Danger -> dangerStyle(colors, hovered)
    }
}

private fun ButtonVariant.disabledStyle(colors: ZillitColors) = ButtonStyle(
    background = if (this == ButtonVariant.Tertiary) Color.Transparent else colors.surfaceHover,
    content = colors.textDisabled,
    border = null,
)

private fun primaryStyle(colors: ZillitColors, hovered: Boolean, pressed: Boolean) = ButtonStyle(
    background = when {
        pressed -> colors.accentPressed
        hovered -> colors.accentHover
        else -> colors.accent
    },
    content = colors.textOnAccent,
    border = null,
)

private fun secondaryStyle(colors: ZillitColors, hovered: Boolean) = ButtonStyle(
    background = if (hovered) colors.surfaceHover else colors.surface,
    content = colors.textPrimary,
    border = BorderStroke(1.dp, if (hovered) colors.borderStrong else colors.border),
)

private fun tertiaryStyle(colors: ZillitColors, hovered: Boolean) = ButtonStyle(
    background = if (hovered) colors.surfaceHover else Color.Transparent,
    content = if (hovered) colors.textPrimary else colors.textSecondary,
    border = null,
)

private fun dangerStyle(colors: ZillitColors, hovered: Boolean) = ButtonStyle(
    background = if (hovered) colors.danger.copy(alpha = DANGER_HOVER_ALPHA) else colors.danger,
    content = Color.White,
    border = null,
)

private const val DANGER_HOVER_ALPHA = 0.9f
