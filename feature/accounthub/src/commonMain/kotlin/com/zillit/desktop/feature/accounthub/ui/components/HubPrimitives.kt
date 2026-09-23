package com.zillit.desktop.feature.accounthub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The small pieces the web's hub pages are built from — `atoms.jsx` and the
 * `ui/` tree — in Compose.
 *
 * Kept as one file rather than one per atom because they are read together:
 * a page composes a FieldLabel over a ToggleRow inside a SubCard, and the
 * three only make sense next to each other.
 */

/** The 11px mono uppercase eyebrow — `FieldLabel`, the sidebar's section headings, the "SECTIONS · N" divider. */
@Composable
fun MonoLabel(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        ),
        color = color ?: ZillitTheme.colors.textMuted,
        modifier = modifier,
        maxLines = 1,
    )
}

/** A field's label, with the web's required asterisk. */
@Composable
fun FieldLabel(text: String, required: Boolean = false, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(text = text, style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
        if (required) ZillitText(text = "*", style = ZillitTheme.typography.label, color = ZillitTheme.colors.danger)
    }
}

@Composable
fun FieldHint(text: String, modifier: Modifier = Modifier) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textMuted,
        modifier = modifier,
    )
}

/** A label, a hint and a switch on the right — the web's `ToggleRow`. */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(text = label, style = ZillitTheme.typography.bodyMedium)
            if (hint != null) FieldHint(hint)
        }
        ZillitSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** A label and a hint with a static "Always" pill where the switch would be — the web's `AlwaysRow`. */
@Composable
fun AlwaysRow(label: String, hint: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(text = label, style = ZillitTheme.typography.bodyMedium)
            FieldHint(hint)
        }
        Pill(text = str(S.desktop_always), tone = StatusTone.Done)
    }
}

/** A small rounded status word — peach, green, red or grey. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, tone: StatusTone = StatusTone.Neutral, dot: Boolean = false) {
    val colors = ZillitTheme.colors
    val (background, content) = when (tone) {
        StatusTone.Done, StatusTone.Ready -> colors.successSoft to colors.success
        StatusTone.Pending, StatusTone.Escalated -> colors.warningSoft to colors.warning
        StatusTone.Rejected -> colors.dangerSoft to colors.danger
        StatusTone.Progress, StatusTone.InTransit -> colors.infoSoft to colors.info
        StatusTone.Neutral -> colors.surfaceSunken to colors.textSecondary
    }
    Row(
        modifier = modifier
            .clip(ZillitTheme.shapes.pill)
            .background(background)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        if (dot) Box(Modifier.size(DOT).clip(CircleShape).background(content))
        ZillitText(
            text = text,
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = content,
            maxLines = 1,
        )
    }
}

/** The mono count chip beside a nav row or a tab — `12`, or a dash when there is nothing to count. */
@Composable
fun MonoChip(text: String, modifier: Modifier = Modifier, active: Boolean = false) {
    val colors = ZillitTheme.colors
    Box(
        modifier = modifier
            .clip(ZillitTheme.shapes.small)
            .background(if (active) colors.accentSoft else colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.xs, vertical = 1.dp),
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            ),
            color = if (active) colors.accentText else colors.textMuted,
        )
    }
}

/** A keyboard key — `esc`, `⌘S` — as the modal footer prints them. */
@Composable
fun Kbd(text: String) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .clip(ZillitTheme.shapes.small)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.small)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = 1.dp),
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = colors.textSecondary,
        )
    }
}

/**
 * A selectable card with a label and a sample line — the web's `RadioCard`.
 *
 * Used where a select would hide the thing being chosen: a description
 * format is picked by what it produces, not by its key.
 */
@Composable
fun RadioCard(
    label: String,
    sample: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(if (active) colors.accentSoft else colors.surface)
            .border(if (active) 2.dp else 1.dp, if (active) colors.accent else colors.border, ZillitTheme.shapes.large)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .size(RADIO)
                    .clip(CircleShape)
                    .border(2.dp, if (active) colors.accent else colors.borderStrong, CircleShape)
                    .padding(3.dp),
            ) {
                if (active) Box(Modifier.size(RADIO).clip(CircleShape).background(colors.accent))
            }
            ZillitText(
                text = label,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (active) colors.accentText else colors.textPrimary,
            )
        }
        ZillitText(
            text = sample,
            style = ZillitTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A bordered white pane with an optional hint bar and action — the web's `SubCard` / `SectionPane`. */
@Composable
fun SubCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    hint: String? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
    padded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large),
    ) {
        if (title != null || hint != null || action != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (title != null) ZillitText(text = title, style = ZillitTheme.typography.titleSmall)
                    if (hint != null) FieldHint(hint)
                }
                action?.invoke(this)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(if (padded) ZillitTheme.spacing.lg else 0.dp),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            content = content,
        )
    }
}

/** The peach info strip the web puts above a form or a builder — `TipBanner`. */
@Composable
fun TipBanner(text: String, modifier: Modifier = Modifier, icon: ImageVector = ZillitIcons.Info) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.accentSoft)
            .padding(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(icon = icon, tint = colors.accentText, size = 16.dp)
        ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = colors.textPrimary)
    }
}

/** A dashed-feel ghost button row — the web's "Add bureau" / "Add condition" affordance. */
@Composable
fun GhostAddButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    ZillitButton(
        text = text,
        onClick = onClick,
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Add,
        enabled = enabled,
        modifier = modifier,
    )
}

/**
 * An "are you sure" — the web's `ConfirmModal`.
 *
 * Danger for the irreversible (delete, deactivate, close a period); warning
 * for the reversible-but-surprising (empty levels dropped).
 */
@Composable
fun HubConfirmDialog(
    visible: Boolean,
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = true,
    loading: Boolean = false,
    cancelLabel: String = str(S.cancel),
) {
    ZillitDialogShell(
        title = title,
        visible = visible,
        onDismiss = onDismiss,
        icon = if (danger) ZillitIcons.Warning else ZillitIcons.Info,
        modifier = modifier,
        actions = {
            ZillitButton(text = cancelLabel, onClick = onDismiss, variant = ButtonVariant.Tertiary, enabled = !loading)
            ZillitButton(
                text = confirmLabel,
                onClick = onConfirm,
                variant = if (danger) ButtonVariant.Danger else ButtonVariant.Primary,
                loading = loading,
            )
        },
    ) {
        ZillitText(text = message, style = ZillitTheme.typography.bodyMedium)
    }
}

/**
 * A row that reveals its actions on hover, as the web's tables and cards do.
 *
 * Revealed by alpha, never by composing them only while hovered: on the press
 * Compose re-evaluates hover and reports an exit for that instant, so a button
 * that exists only while hovered leaves the composition under the pointer and
 * the click lands on the row instead — Edit still "worked" through the row's
 * own click, Remove silently did nothing.
 */
@Composable
fun HoverRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    actions: @Composable RowScope.(hovered: Boolean) -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .then(if (onClick != null) Modifier.clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ) else Modifier)
            .background(if (hovered) colors.surfaceHover else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        content()
        Row(
            modifier = Modifier.alpha(if (hovered) 1f else 0f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) { actions(hovered) }
    }
}

/** A stat card — label, big value, hint — the web's `StatTile` / `StatStrip` cell. */
@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier, hint: String? = null, accent: Color? = null) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        MonoLabel(label)
        ZillitText(text = value, style = ZillitTheme.typography.titleLarge, color = accent ?: colors.textPrimary)
        if (hint != null) FieldHint(hint)
    }
}

/** A thin horizontal rule. Not `ZillitDivider` — that one fills a Row and blanks it. */
@Composable
fun HairLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.border))
}

/** Spacer helpers, so page code reads as layout rather than arithmetic. */
@Composable
fun Gap(size: Dp) = Spacer(Modifier.size(size))

@Composable
fun RowScope.Fill() = Spacer(Modifier.weight(1f))

@Composable
fun WidthGap(width: Dp) = Spacer(Modifier.width(width))

private val DOT = 6.dp
private val RADIO = 16.dp
