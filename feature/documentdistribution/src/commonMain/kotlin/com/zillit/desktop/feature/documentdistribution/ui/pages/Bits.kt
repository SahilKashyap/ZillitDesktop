package com.zillit.desktop.feature.documentdistribution.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.documentdistribution.domain.FileKind
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.RecipientStatus
import com.zillit.desktop.feature.documentdistribution.domain.SendStatus
import com.zillit.desktop.feature.documentdistribution.domain.SupportedUploads
import com.zillit.desktop.feature.documentdistribution.domain.fileKindOf

/**
 * The small pieces every page here shares — a file's tinted glyph, the
 * status tones, the uppercase field label, a hover row and a menu.
 */

/** The colour a file kind wears, matched to the web's icon palette. */
internal data class FileTint(val fore: Color, val back: Color)

@Composable
internal fun fileTint(kind: FileKind): FileTint {
    val c = ZillitTheme.colors
    return when (kind) {
        FileKind.Pdf -> FileTint(c.danger, c.dangerSoft)
        FileKind.Image, FileKind.ImageUnsupported -> FileTint(c.info, c.infoSoft)
        FileKind.Word -> FileTint(c.files.indigo, c.infoSoft)
        FileKind.Excel -> FileTint(c.success, c.successSoft)
        FileKind.VCard -> FileTint(c.warning, c.warningSoft)
        FileKind.Other -> FileTint(c.textSecondary, c.surfaceSunken)
    }
}

/**
 * A rounded tile carrying the extension in the kind's colour — the one
 * glyph the library, the picker, the composer and History all draw.
 */
@Composable
internal fun FileGlyph(name: String, contentType: String?, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val kind = fileKindOf(contentType, name)
    val tint = fileTint(kind)
    val ext = SupportedUploads.extensionOf(name).uppercase().take(4).ifBlank { "FILE" }
    Box(
        modifier = modifier.size(size).clip(ZillitTheme.shapes.medium).background(tint.back),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = ext,
            style = ZillitTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = (size.value / 3.2f).sp,
            ),
            color = tint.fore,
            maxLines = 1,
        )
    }
}

@Composable
internal fun FileGlyph(document: LibraryDocument, modifier: Modifier = Modifier, size: Dp = 32.dp) =
    FileGlyph(document.name, document.contentType, modifier, size)

/** The folder glyph, in the web's amber. */
@Composable
internal fun FolderGlyph(modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val c = ZillitTheme.colors
    Box(
        modifier = modifier.size(size).clip(ZillitTheme.shapes.medium).background(c.goldSoft),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon = ZillitIcons.Grid, tint = c.gold, size = size * 0.55f)
    }
}

internal fun RecipientStatus.tone(): StatusTone = when (this) {
    RecipientStatus.Pending -> StatusTone.Neutral
    RecipientStatus.Accepted -> StatusTone.Progress
    RecipientStatus.Opened -> StatusTone.Done
    RecipientStatus.Rejected, RecipientStatus.Bounced, RecipientStatus.Failed -> StatusTone.Rejected
}

internal fun SendStatus.tone(): StatusTone = when (this) {
    SendStatus.Sent -> StatusTone.Done
    SendStatus.Queued -> StatusTone.Pending
    SendStatus.Failed -> StatusTone.Rejected
    SendStatus.Unknown -> StatusTone.Neutral
}

/** The web's `dd-label`: small caps above a field. */
@Composable
internal fun FieldLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ZillitText(
            text = text.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
            color = ZillitTheme.colors.textMuted,
        )
        trailing?.let { Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs), content = it) }
    }
}

/**
 * A row that lifts on hover and marks itself when selected — the shape of
 * every list in this tool. [onClick] null draws it inert.
 */
@Composable
internal fun HoverRow(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    padding: Dp = ZillitTheme.spacing.md,
    content: @Composable RowScope.(hovered: Boolean) -> Unit,
) {
    val c = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        selected -> c.surfaceSelected
        hovered -> c.surfaceHover
        else -> Color.Transparent
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(background)
            .hoverable(interaction)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = padding, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        content(hovered)
    }
}

/** One entry of a [MenuPopup]. */
internal data class MenuEntry(
    val label: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    /** Draws a rule above this entry. */
    val dividerBefore: Boolean = false,
)

/** The tool's one dropdown menu, drawn on the raised surface with hover rows. */
@Composable
internal fun MenuPopup(
    expanded: Boolean,
    onDismiss: () -> Unit,
    entries: List<MenuEntry>,
    width: Dp = 240.dp,
) {
    val c = ZillitTheme.colors
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.width(width).padding(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            entries.forEach { entry ->
                if (entry.dividerBefore) {
                    Box(Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs).border(0.5.dp, c.divider))
                }
                HoverRow(
                    onClick = if (entry.enabled) {
                        { onDismiss(); entry.onClick() }
                    } else {
                        null
                    },
                    padding = ZillitTheme.spacing.sm,
                ) {
                    val fore = when {
                        !entry.enabled -> c.textDisabled
                        entry.danger -> c.danger
                        else -> c.textPrimary
                    }
                    entry.icon?.let { ZillitIcon(icon = it, tint = fore, size = 16.dp) }
                    ZillitText(text = entry.label, color = fore, maxLines = 1)
                }
            }
        }
    }
}

/** "Sep 1, 2026 · 3:15 PM" from a `YYYY-MM-DD` key — the columns show the date part only. */
internal fun prettyIsoDate(key: String): String {
    if (key.length < ISO_DATE_LENGTH) return key.ifBlank { "—" }
    val month = key.substring(MONTH_START, MONTH_END).toIntOrNull()?.let { MONTHS.getOrNull(it - 1) } ?: return key
    val day = key.substring(DAY_START, ISO_DATE_LENGTH).trimStart('0')
    return "$month $day, ${key.substring(0, YEAR_LENGTH)}"
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private const val ISO_DATE_LENGTH = 10
private const val YEAR_LENGTH = 4
private const val MONTH_START = 5
private const val MONTH_END = 7
private const val DAY_START = 8

/**
 * A multi-line message field that fills its height — the body of a send
 * or a template. [ZillitTextField] wraps its border round the text, so a
 * tall minimum leaves the space below the box rather than inside it; this
 * draws the same chrome round a box the text grows into.
 */
@Composable
internal fun MessageBodyField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    minHeight: Dp = BODY_MIN_HEIGHT,
) {
    val c = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // No scroll of its own: the field grows with the text and the dialog
    // around it scrolls. A scrolling field inside a scrolling dialog is
    // measured against infinite height, which Compose refuses outright.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .clip(ZillitTheme.shapes.medium)
            .background(c.surface)
            .border(1.dp, if (focused) c.focusRing else c.border, ZillitTheme.shapes.medium)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
    ) {
        if (value.isEmpty()) {
            ZillitText(text = placeholder, style = ZillitTheme.typography.bodyMedium, color = c.textMuted)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = minHeight - BODY_INSET),
            textStyle = ZillitTheme.typography.bodyMedium.copy(color = c.textPrimary),
            cursorBrush = SolidColor(c.accent),
            interactionSource = interaction,
        )
    }
}

private val BODY_MIN_HEIGHT = 160.dp
private val BODY_INSET = 20.dp
