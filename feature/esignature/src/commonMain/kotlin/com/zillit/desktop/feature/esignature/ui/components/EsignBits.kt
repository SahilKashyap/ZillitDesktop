@file:Suppress("MagicNumber", "CyclomaticComplexMethod") // The web's colour tables, transcribed.

package com.zillit.desktop.feature.esignature.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitProgressBar
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.esignature.domain.Envelope
import com.zillit.desktop.feature.esignature.domain.EnvelopeRecipient
import com.zillit.desktop.feature.esignature.domain.EnvelopeStatus
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.ui.decodeImageBitmap

/** The web's `SIGNER_COLORS` — one hue per signer, in routing order. */
internal val SIGNER_COLORS = listOf(
    Color(0xFFE8930C), Color(0xFF3B82F6), Color(0xFF10B981), Color(0xFF8B5CF6),
    Color(0xFFF43F5E), Color(0xFF06B6D4), Color(0xFFF59E0B), Color(0xFFEC4899),
)

internal fun signerColor(index: Int): Color =
    SIGNER_COLORS[((index % SIGNER_COLORS.size) + SIGNER_COLORS.size) % SIGNER_COLORS.size]

/** Each field type's hue on the placer, the web's `FIELD_TYPES[].color`. */
internal fun FieldType.color(): Color = when (this) {
    FieldType.SignHere -> Color(0xFFE8930C)
    FieldType.InitialHere -> Color(0xFF3B82F6)
    FieldType.Checkbox -> Color(0xFF10B981)
    FieldType.Radio -> Color(0xFF8B5CF6)
    FieldType.Dropdown -> Color(0xFF06B6D4)
    FieldType.Text -> Color(0xFFF59E0B)
    FieldType.Date -> Color(0xFFD946EF)
    FieldType.DateSigned -> Color(0xFF64748B)
    FieldType.FullName, FieldType.Image -> Color(0xFF0891B2)
    FieldType.Email -> Color(0xFF0EA5E9)
    FieldType.Phone -> Color(0xFF14B8A6)
    FieldType.Number -> Color(0xFFEF4444)
    FieldType.Url, FieldType.Attachment -> Color(0xFF7C3AED)
    FieldType.Other -> Color(0xFF64748B)
}

internal fun EnvelopeStatus.tone(): StatusTone = when (this) {
    EnvelopeStatus.Completed -> StatusTone.Done
    EnvelopeStatus.Signed, EnvelopeStatus.Delivered -> StatusTone.Progress
    EnvelopeStatus.Declined, EnvelopeStatus.Rejected,
    EnvelopeStatus.Voided, EnvelopeStatus.Expired,
    -> StatusTone.Rejected
    EnvelopeStatus.Draft, EnvelopeStatus.Unknown -> StatusTone.Neutral
    EnvelopeStatus.Sent -> StatusTone.Pending
}

@Composable
internal fun EnvelopeStatusPill(status: EnvelopeStatus, label: String = status.label) {
    ZillitStatusPill(label = label.ifBlank { "—" }, tone = status.tone(), dot = true)
}

/** The Sent tab's delivery column: seen by all / awaiting view / seen by X of Y. */
@Composable
internal fun DeliveryPill(envelope: Envelope) {
    val total = envelope.signers.size
    val seen = envelope.seenCount
    val (label, tone) = when {
        total > 0 && seen == total -> str(S.desktop_ds_seen_by_all) to StatusTone.Done
        seen == 0 -> str(S.desktop_ds_awaiting_view) to StatusTone.Pending
        else -> "Seen by $seen/$total" to StatusTone.Progress
    }
    ZillitStatusPill(label = label, tone = tone, dot = true)
}

/** "2 of 3 signed" with a bar, or an italic "Awaiting". */
@Composable
internal fun SignedProgress(envelope: Envelope, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val total = envelope.signers.size
    val done = envelope.signedCount
    if (done == 0) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).border(1.dp, colors.textMuted, CircleShape))
            ZillitText(
                text = str(S.ds_sent_filter_awaiting),
                style = ZillitTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = colors.textMuted,
            )
        }
        return
    }
    Column(modifier = modifier.width(120.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ZillitText(
            text = "$done of $total signed",
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
        )
        ZillitProgressBar(
            fraction = if (total == 0) 0f else done.toFloat() / total,
            fillColor = if (done == total) colors.success else colors.accent,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Overlapping initials, the first [maxCount] then "+N", each tooltipped with the name. */
@Composable
internal fun SignerAvatarGroup(
    recipients: List<EnvelopeRecipient>,
    size: Dp = 26.dp,
    maxCount: Int = 4,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val shown = recipients.take(maxCount)
    val rest = recipients.size - shown.size
    if (recipients.isEmpty()) {
        ZillitText("—", color = colors.textMuted, style = ZillitTheme.typography.bodySmall)
        return
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        shown.forEachIndexed { index, recipient ->
            ZillitTooltip(text = recipient.name.ifBlank { recipient.email } + (if (recipient.isCc) " · CC" else "")) {
                Box(
                    modifier = Modifier
                        .offset(x = (-(size / 4) * index))
                        .size(size + 4.dp)
                        .clip(CircleShape)
                        .background(colors.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitAvatar(
                        name = recipient.name.ifBlank { recipient.email },
                        userId = recipient.userId,
                        size = size,
                    )
                    if (recipient.signed) {
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(10.dp).clip(CircleShape)
                                .background(colors.success).border(1.5.dp, colors.surface, CircleShape),
                        )
                    }
                }
            }
        }
        if (rest > 0) {
            Box(
                modifier = Modifier
                    .offset(x = (-(size / 4) * shown.size))
                    .size(size)
                    .clip(CircleShape)
                    .background(colors.surfaceSunken)
                    .border(1.dp, colors.border, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                ZillitText("+$rest", style = ZillitTheme.typography.labelSmall, color = colors.textSecondary)
            }
        }
    }
}

/** A round-cornered document icon tile, the accent-tinted square every card leads with. */
@Composable
internal fun DocTile(size: Dp = 40.dp, icon: ImageVector = ZillitIcons.File, tint: Color? = null) {
    val colors = ZillitTheme.colors
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(colors.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon = icon, tint = tint ?: colors.accentText, size = size / 2)
    }
}

/** A filter chip with a count, tinted by [accent] when active — the Sent tab's All / Awaiting / In progress. */
@Composable
internal fun CountChip(
    label: String,
    count: Int,
    active: Boolean,
    onClick: () -> Unit,
    accent: Color = ZillitTheme.colors.accent,
    help: String = "",
) {
    val colors = ZillitTheme.colors
    val disabled = count == 0 && !active
    val background by animateColorAsState(if (active) accent else Color.Transparent, label = "chip")
    ZillitTooltip(help) {
        Row(
            modifier = Modifier
                .clip(ZillitTheme.shapes.pill)
                .background(background)
                .border(1.dp, if (active) accent else colors.border, ZillitTheme.shapes.pill)
                .then(if (disabled) Modifier else Modifier.clickable(onClick = onClick))
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ZillitText(
                text = label,
                style = ZillitTheme.typography.label,
                color = if (active) Color.White else if (disabled) colors.textMuted else colors.textPrimary,
            )
            Box(
                modifier = Modifier
                    .clip(ZillitTheme.shapes.pill)
                    .background(if (active) Color.White.copy(alpha = 0.25f) else accent.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                ZillitText(
                    text = count.toString(),
                    style = ZillitTheme.typography.labelSmall,
                    color = if (active) Color.White else accent,
                )
            }
        }
    }
}

/** A small red count on a tab or a button's corner. */
@Composable
internal fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier = modifier.clip(ZillitTheme.shapes.pill).background(Color(0xFFDC2626)).padding(
            horizontal = 6.dp,
            vertical = 1.dp,
        ),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = count.toString(),
            style = ZillitTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = Color.White,
        )
    }
}

/** The accent-on-white segmented control the web puts above the panel. */
@Composable
internal fun AccentSegmented(
    options: List<Triple<String, String, Int>>,
    activeId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceSunken)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (id, label, count) ->
            val active = id == activeId
            val background by animateColorAsState(if (active) colors.accent else Color.Transparent, label = "segment")
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(background)
                    .clickable { onSelect(id) }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZillitText(
                    text = label,
                    style = ZillitTheme.typography.titleSmall,
                    color = if (active) Color.White else colors.textPrimary,
                    maxLines = 1,
                )
                CountBadge(count)
            }
        }
    }
}

/** List ↔ card, the two-button toggle. */
@Composable
internal fun LayoutToggle(isCard: Boolean, onChange: (Boolean) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.clip(ZillitTheme.shapes.medium).border(1.dp, colors.border, ZillitTheme.shapes.medium),
    ) {
        listOf(false to ZillitIcons.LayoutTabs, true to ZillitIcons.Grid).forEach { (card, icon) ->
            val active = card == isCard
            ZillitTooltip(if (card) str(S.av_card_view) else str(S.av_list_view)) {
                Box(
                    modifier = Modifier
                        .background(if (active) colors.accent else Color.Transparent)
                        .clickable { onChange(card) }
                        .size(width = 38.dp, height = 30.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitIcon(icon = icon, tint = if (active) Color.White else colors.textSecondary, size = 15.dp)
                }
            }
        }
    }
}

/** A card surface: hairline, radius, soft hover lift. */
@Composable
internal fun EsignCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Color? = null,
    padding: Dp = 16.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val border by animateColorAsState(
        if (hovered && onClick != null) colors.borderStrong else accent?.copy(alpha = 0.45f) ?: colors.border,
        label = "cardBorder",
    )
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(if (hovered && onClick != null) colors.surfaceHover else colors.surface)
            .border(1.dp, border, ZillitTheme.shapes.large)
            .hoverable(interaction)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick).hand() else Modifier)
            .padding(padding),
        content = content,
    )
}

/** A label/value line in a details block. */
@Composable
internal fun KeyValue(key: String, value: String) {
    val colors = ZillitTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        ZillitText(key, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        ZillitText(value, style = ZillitTheme.typography.bodySmall, textAlign = TextAlign.End)
    }
}

/** A subtle section heading inside a card. */
@Composable
internal fun BlockTitle(text: String, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ZillitText(text, style = ZillitTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/** A yes/no dialog for the destructive acts. */
@Composable
internal fun ConfirmDialog(
    visible: Boolean,
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = true,
    busy: Boolean = false,
) {
    ZillitDialogShell(
        title = title,
        visible = visible,
        onDismiss = onDismiss,
        scrollable = false,
        icon = if (danger) ZillitIcons.Warning else ZillitIcons.Info,
        width = 440.dp,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = onDismiss,
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                text = confirmLabel,
                onClick = onConfirm,
                variant = if (danger) ButtonVariant.Danger else ButtonVariant.Primary,
                size = ButtonSize.Small,
                loading = busy,
            )
        },
    ) {
        ZillitText(body, style = ZillitTheme.typography.bodyMedium)
    }
}

/** Encoded bytes drawn to fit a box, remembered per key. */
@Composable
internal fun BytesImage(
    bytes: ByteArray?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val bitmap: ImageBitmap? = remember(bytes?.size, bytes?.firstOrNull()) { bytes?.let(::decodeImageBitmap) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
    }
}

/** A row that lights on hover and takes a click — table rows and pickers. */
@Composable
internal fun Modifier.hoverRow(onClick: (() -> Unit)?): Modifier {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    return this
        .background(if (hovered && onClick != null) colors.surfaceHover else Color.Transparent)
        .hoverable(interaction)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick).hand() else Modifier)
}

private fun Modifier.hand(): Modifier = pointerHoverIcon(PointerIcon.Hand)

@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.border))
}
