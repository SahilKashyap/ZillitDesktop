@file:Suppress("TooManyFunctions") // One small composable per visual idiom the web's Sides screens share.

package com.zillit.desktop.feature.sides.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.sides.domain.SceneInfo
import com.zillit.desktop.feature.sides.domain.SidesRules
import com.zillit.desktop.feature.sides.ui.SidesDestination
import com.zillit.desktop.feature.sides.ui.SidesLayout

/**
 * Who each creator is, by face — provided by the host from the crew list
 * loader the boards and calls share. A local, because the faces sit inside
 * cards and table cells well below the screen's signature.
 */
val LocalSidesFaces: ProvidableCompositionLocal<suspend (String) -> ImageBitmap?> =
    staticCompositionLocalOf { { _: String -> null } }

/** `#e53935` → a Color, or null when the string is not a six-digit hex. */
fun hexColor(value: String): Color? {
    if (!SidesRules.isHexColor(value)) return null
    val rgb = value.drop(1).toLongOrNull(radix = 16) ?: return null
    return Color(OPAQUE or rgb)
}

internal fun Modifier.hand(): Modifier = pointerHoverIcon(PointerIcon.Hand)

private const val OPAQUE = 0xFF000000L
private val TILE_RADIUS = 10.dp
private const val TINT_ALPHA = 0.14f

/** The accent-tinted document square every card leads with. */
@Composable
internal fun SidesTile(icon: ImageVector = ZillitIcons.File, size: Dp = 40.dp, tint: Color? = null) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(TILE_RADIUS))
            .background(tint?.copy(alpha = TINT_ALPHA) ?: colors.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon = icon, tint = tint ?: colors.accentText, size = size / 2)
    }
}

/** A creator's face — the profile picture when the crew list has one, initials otherwise. */
@Composable
internal fun CreatorAvatar(userId: String, name: String, size: Dp = 22.dp) {
    val load = LocalSidesFaces.current
    var image by remember(userId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(userId) { if (userId.isNotBlank()) image = load(userId) }
    ZillitAvatar(name = name.ifBlank { "?" }, size = size, image = image)
}

/** An icon + text pair in the muted meta line. */
@Composable
internal fun MetaCell(icon: ImageVector, text: String, color: Color = ZillitTheme.colors.textMuted) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        ZillitIcon(icon = icon, tint = color, size = 13.dp)
        ZillitText(
            text,
            style = ZillitTheme.typography.bodySmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The web's `·` between meta cells. */
@Composable
internal fun MetaDot() {
    Box(Modifier.size(3.dp).clip(CircleShape).background(ZillitTheme.colors.textMuted.copy(alpha = 0.6f)))
}

/**
 * A scene number chip. [tint] colours a page chip after its folder; `+N`
 * overflow chips are muted.
 */
@Composable
internal fun SceneChip(text: String, tint: Color? = null, more: Boolean = false, tip: String = "") {
    val colors = ZillitTheme.colors
    val fill = when {
        more -> colors.surfaceSunken
        tint != null -> tint.copy(alpha = 0.13f)
        else -> colors.accentSoft
    }
    val ink = when {
        more -> colors.textSecondary
        tint != null -> tint
        else -> colors.accentText
    }
    ZillitTooltip(tip) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(fill)
                .border(1.dp, ink.copy(alpha = if (more) 0.2f else 0.35f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            ZillitText(
                text,
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = ink,
            )
        }
    }
}

/** A small count pill beside a heading. */
@Composable
internal fun CountChip(count: Int) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier.clip(ZillitTheme.shapes.pill).background(colors.accentSoft).padding(
            horizontal = 8.dp,
            vertical = 2.dp,
        ),
    ) {
        ZillitText(count.toString(), style = ZillitTheme.typography.labelSmall, color = colors.accentText)
    }
}

/** The `Sides | Script` switch — the web's accent-on-sunken Segmented. */
@Composable
internal fun SidesSegmented(active: SidesDestination, onSelect: (SidesDestination) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(colors.surfaceSunken).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SidesDestination.entries.forEach { destination ->
            val on = destination == active
            val background by animateColorAsState(if (on) colors.accent else Color.Transparent, label = "segment")
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(background)
                    .clickable { onSelect(destination) }
                    .hand()
                    .padding(horizontal = 22.dp, vertical = 8.dp),
            ) {
                ZillitText(
                    text = destination.label,
                    style = ZillitTheme.typography.titleSmall,
                    color = if (on) Color.White else colors.textPrimary,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The labelled `List view | Table view` switcher. */
@Composable
internal fun LayoutToggle(layout: SidesLayout, onChange: (SidesLayout) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(colors.surfaceSunken).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        listOf(
            Triple(SidesLayout.List, "List view", ZillitIcons.LayoutTabs),
            Triple(SidesLayout.Table, "Table view", ZillitIcons.Grid),
        ).forEach { (value, label, icon) ->
            val on = value == layout
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) colors.surface else Color.Transparent)
                    .then(if (on) Modifier.border(1.dp, colors.border, RoundedCornerShape(8.dp)) else Modifier)
                    .clickable { onChange(value) }
                    .hand()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ZillitIcon(icon = icon, tint = if (on) colors.textPrimary else colors.textMuted, size = 14.dp)
                ZillitText(
                    text = label,
                    style = ZillitTheme.typography.label,
                    color = if (on) colors.textPrimary else colors.textMuted,
                )
            }
        }
    }
}

/** A text-only link button: `Select all`, `Clear`, `Remove`. */
@Composable
internal fun LinkButton(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    muted: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = ZillitTheme.colors
    val ink = if (!enabled) colors.textDisabled else if (muted) colors.textSecondary else colors.accentText
    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.small)
            .then(if (enabled) Modifier.clickable(onClick = onClick).hand() else Modifier)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        icon?.let { ZillitIcon(icon = it, tint = ink, size = 12.dp) }
        ZillitText(text, style = ZillitTheme.typography.label, color = ink)
    }
}

/** A section label with an optional `(optional)` tail. */
@Composable
internal fun FieldLabel(text: String, optional: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        ZillitText(text, style = ZillitTheme.typography.titleSmall)
        if (optional) ZillitText(
            "(optional)",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/** A small tag: `CURRENT`, `ACTIVE`. */
@Composable
internal fun SidesBadge(text: String, tint: Color = ZillitTheme.colors.accentText) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        ZillitText(text, style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = tint)
    }
}

/** A radio dot, on or off. */
@Composable
internal fun RadioDot(on: Boolean) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier.size(16.dp).clip(CircleShape).border(
            2.dp,
            if (on) colors.accent else colors.borderStrong,
            CircleShape,
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Box(Modifier.size(8.dp).clip(CircleShape).background(colors.accent))
    }
}

/**
 * A selectable row with a radio dot — the call sheet and schedule pickers.
 * Accent-glow when on, sunken otherwise.
 */
@Composable
internal fun RadioRow(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    val border by animateColorAsState(if (selected) colors.accent else colors.border, label = "radioRow")
    val fill by animateColorAsState(if (selected) colors.accentSoft else colors.surfaceSunken, label = "radioRowFill")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(fill)
            .border(1.dp, border, ZillitTheme.shapes.medium)
            .clickable(onClick = onClick)
            .hand()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioDot(selected)
        content()
    }
}

/** The two unselected-scenes cards: `Cross out` / `Hide`. */
@Composable
internal fun RadioCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RadioRow(selected = selected, onClick = onClick, modifier = modifier) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ZillitText(title, style = ZillitTheme.typography.titleSmall)
            ZillitText(subtitle, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
        }
    }
}

/**
 * A collapsible source — a script version or a page folder — with a
 * chevron head and a body that composes only while open.
 */
@Composable
internal fun Accordion(
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    head: @Composable RowScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    val chevron by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(if (open) colors.surface else colors.surfaceSunken)
            .border(1.dp, if (open) colors.borderStrong else colors.border, ZillitTheme.shapes.medium),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (hovered) colors.surfaceHover else Color.Transparent)
                .hoverable(interaction)
                .clickable(onClick = onToggle)
                .hand()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZillitIcon(
                icon = ZillitIcons.ChevronDown,
                tint = colors.textMuted,
                size = 14.dp,
                modifier = Modifier.rotate(chevron),
            )
            head()
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp), content = body)
        }
    }
}

/**
 * The compact grid of numbered scene chips: each toggles, hovering shows the
 * heading, and Select all / Clear sit above — the web's `SceneNumberGrid`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SceneNumberGrid(
    scenes: List<SceneInfo>,
    picked: Set<String>,
    onToggle: (String) -> Unit,
    onSelectAll: (List<String>) -> Unit,
    onClear: () -> Unit,
) {
    val colors = ZillitTheme.colors
    if (scenes.isEmpty()) {
        ZillitText("No scenes detected.", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        return
    }
    val pickedCount = scenes.count { it.sceneNumber in picked }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LinkButton("Select all", icon = ZillitIcons.Check, onClick = { onSelectAll(scenes.map { it.sceneNumber }) })
            LinkButton("Clear", icon = ZillitIcons.Close, onClick = onClear, muted = true, enabled = pickedCount > 0)
            if (pickedCount > 0) {
                ZillitText(
                    "$pickedCount / ${scenes.size}",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            scenes.forEach { scene ->
                val on = scene.sceneNumber in picked
                val fill by animateColorAsState(if (on) colors.accent else colors.surface, label = "scene")
                ZillitTooltip(SidesRules.sceneTip(scene)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(fill)
                            .border(1.dp, if (on) colors.accent else colors.border, RoundedCornerShape(6.dp))
                            .clickable { onToggle(scene.sceneNumber) }
                            .hand()
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        ZillitText(
                            text = scene.sceneNumber,
                            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
                            color = if (on) Color.White else colors.textPrimary,
                        )
                    }
                }
            }
        }
    }
}

/** A centred spinner with a caption — the web's `SidesLoader`. */
@Composable
internal fun SidesLoader(tip: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZillitSpinner(size = 28.dp)
        ZillitText(tip, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
    }
}

/** The `Generating…` pill on a row still rendering. */
@Composable
internal fun GeneratingPill(label: String = "Generating…") {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.clip(ZillitTheme.shapes.pill).background(colors.warningSoft).padding(
            horizontal = 10.dp,
            vertical = 4.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZillitSpinner(size = 12.dp, color = colors.warning)
        ZillitText(label, style = ZillitTheme.typography.labelSmall, color = colors.warning)
    }
}

/** A yes/no dialog for the destructive acts — the web's `ConfirmModal`. */
@Composable
internal fun SidesConfirmDialog(
    title: String,
    message: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "Delete",
    busyLabel: String = "Deleting…",
) {
    ZillitDialogShell(
        title = title,
        visible = true,
        onDismiss = { if (!busy) onDismiss() },
        scrollable = false,
        icon = ZillitIcons.Warning,
        width = 420.dp,
        actions = {
            ZillitButton(
                "Cancel",
                onClick = onDismiss,
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = !busy,
            )
            ZillitButton(
                text = if (busy) busyLabel else confirmLabel,
                onClick = onConfirm,
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                loading = busy,
            )
        },
    ) {
        ZillitText(message, style = ZillitTheme.typography.bodyMedium, color = ZillitTheme.colors.textSecondary)
    }
}

@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.border))
}
