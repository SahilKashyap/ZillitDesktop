package com.zillit.desktop.feature.email.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.email.domain.EmailSummary
import com.zillit.desktop.feature.email.domain.mailListTimeLabel

/**
 * One message as the web's three-line card (`NewEmailCard.jsx:199-313`):
 * an unread rail on the left edge, the tick and the face, then sender over
 * subject over snippet with the time and the paperclip on the right edge.
 * Unread rows carry the rail, the weight and the brand-orange time; read
 * rows recede to the canvas.
 */
@Composable
internal fun MessageRow(
    message: EmailSummary,
    isSelected: Boolean,
    isTicked: Boolean,
    selectable: Boolean,
    nowMillis: Long,
    /** The Sent folder leads with who it went to, not who wrote it. */
    showRecipients: Boolean,
    onClick: () -> Unit,
    onTick: () -> Unit,
    /** The sender's photo when they are crew here; initials otherwise. */
    loadAvatar: suspend (String) -> ImageBitmap? = { null },
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val weight = if (message.isRead) FontWeight.Normal else FontWeight.SemiBold

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.xs, vertical = 1.dp)
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
            .clip(ZillitTheme.shapes.medium)
            .background(
                when {
                    isSelected || isTicked -> colors.accentSoft
                    hovered -> colors.surfaceHover
                    message.isRead -> colors.canvas
                    else -> colors.surface
                },
            )
            .hoverable(interaction)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.Top,
    ) {
        // The unread rail — the web's 3px `border-l-primary`.
        Box(
            Modifier
                .width(UNREAD_RAIL)
                .fillMaxHeight()
                .background(if (message.isRead) Color.Transparent else colors.accent),
        )

        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            if (selectable) {
                ZillitCheckbox(checked = isTicked, onCheckedChange = { onTick() })
            }

            SenderFace(message, loadAvatar)

            MessageLines(message, showRecipients, weight, nowMillis)
        }
    }
}

/**
 * The three stacked lines beside the face: who and when, subject and clip,
 * then the snippet.
 *
 * Split out of [MessageRow] so the row itself stays about the surface it
 * draws — the rail, the background, the hover — rather than the text inside.
 */
@Composable
private fun MessageLines(
    message: EmailSummary,
    showRecipients: Boolean,
    weight: FontWeight,
    nowMillis: Long,
) {
    val colors = ZillitTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = if (showRecipients) {
                    "To: ${message.to.joinToString(", ").ifBlank { message.senderName }}"
                } else {
                    message.senderName
                },
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = weight),
                color = colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                text = mailListTimeLabel(message.receivedAtMillis, nowMillis),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = weight),
                color = if (message.isRead) colors.textMuted else colors.accentText,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = TIME_MIN_WIDTH),
            )
        }

        SubjectLine(message, weight)

        if (message.snippet.isNotBlank()) {
            ZillitText(
                text = message.snippet,
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The subject, with the paperclip the web puts beside it when there is one. */
@Composable
private fun SubjectLine(message: EmailSummary, weight: FontWeight) {
    val colors = ZillitTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = message.subject.ifBlank { "No Subject" },
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = weight),
            color = if (message.isRead) colors.textSecondary else colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (message.hasAttachments) {
            ZillitIcon(
                icon = ZillitIcons.Paperclip,
                contentDescription = "Has attachments",
                tint = colors.textMuted,
                size = META_ICON,
            )
        }
    }
}

/**
 * The face, with the web's unread dot pinned to its corner
 * (`NewEmailCard.jsx:244-246`).
 */
@Composable
private fun SenderFace(message: EmailSummary, loadAvatar: suspend (String) -> ImageBitmap?) {
    Box {
        ZillitAvatar(
            name = message.senderName,
            image = rememberSenderFace(message.senderAddress, loadAvatar),
            size = ROW_AVATAR,
        )
        if (!message.isRead) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 1.dp, y = (-1).dp)
                    .size(UNREAD_DOT)
                    .clip(CircleShape)
                    .background(ZillitTheme.colors.accent),
            )
        }
    }
}

/**
 * The sender's photo, fetched once per address. Null for anyone outside the
 * production — most mail is not from crew, and [ZillitAvatar] answers that
 * with initials rather than a hole.
 */
@Composable
private fun rememberSenderFace(
    address: String,
    load: suspend (String) -> ImageBitmap?,
): ImageBitmap? = produceState<ImageBitmap?>(initialValue = null, address) {
    value = address.takeIf { it.isNotBlank() }?.let { load(it) }
}.value

private val ROW_AVATAR = 36.dp
private val TIME_MIN_WIDTH = 56.dp
private val META_ICON = 14.dp
private val UNREAD_DOT = 9.dp
private val UNREAD_RAIL = 3.dp
