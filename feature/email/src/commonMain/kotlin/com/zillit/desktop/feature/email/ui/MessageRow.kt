package com.zillit.desktop.feature.email.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.email.domain.EmailSummary
import com.zillit.desktop.feature.email.domain.mailTimeLabel

/**
 * One message as a single dense line, the desktop mail convention: sender in
 * a fixed column so the eye can walk down it, subject and snippet sharing the
 * middle, the time on the right edge. Unread rows sit on the raised surface
 * and carry weight; read rows recede to the canvas — the same split Gmail
 * draws between white and grey.
 */
@Composable
internal fun MessageRow(
    message: EmailSummary,
    isSelected: Boolean,
    isTicked: Boolean,
    selectable: Boolean,
    nowMillis: Long,
    compact: Boolean,
    onClick: () -> Unit,
    onTick: () -> Unit,
    onTrash: () -> Unit,
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
            .background(
                when {
                    isSelected -> colors.accentSoft
                    hovered -> colors.surfaceHover
                    message.isRead -> colors.canvas
                    else -> colors.surface
                },
            )
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = ROW_PADDING, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        LeadingCell(
            showTick = selectable && (isTicked || hovered),
            isTicked = isTicked,
            isRead = message.isRead,
            onTick = onTick,
        )

        SenderCell(message, weight, compact, loadAvatar)

        SubjectCell(message, weight, Modifier.weight(1f))

        if (message.hasAttachments) {
            ZillitIcon(
                icon = ZillitIcons.Drive,
                contentDescription = "Has attachments",
                tint = colors.textMuted,
                size = META_ICON,
            )
        }

        TrailingCell(
            showTrash = hovered && selectable,
            message = message,
            nowMillis = nowMillis,
            weight = weight,
            onTrash = onTrash,
        )
    }
}

/**
 * Hovering swaps the time for the row's one quick action — Gmail's bargain:
 * metadata at rest, verbs under the pointer. Drafts are not IMAP messages,
 * so they have nothing to move to Trash and keep their time.
 */
@Composable
private fun TrailingCell(
    showTrash: Boolean,
    message: EmailSummary,
    nowMillis: Long,
    weight: FontWeight,
    onTrash: () -> Unit,
) {
    if (showTrash) {
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = "Move to Trash",
            onClick = onTrash,
            tint = ZillitTheme.colors.danger,
            size = TRASH_BUTTON,
        )
    } else {
        ZillitText(
            text = mailTimeLabel(message.receivedAtMillis, nowMillis),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = weight),
            color = if (message.isRead) ZillitTheme.colors.textMuted else ZillitTheme.colors.accentText,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = TIME_MIN_WIDTH),
        )
    }
}

/**
 * The tick box replaces the unread dot on hover rather than sitting beside
 * it — the dot is decoration, the box is an action, and two controls in one
 * 8dp column feels fiddly.
 */
@Composable
private fun LeadingCell(showTick: Boolean, isTicked: Boolean, isRead: Boolean, onTick: () -> Unit) {
    if (showTick) {
        ZillitCheckbox(checked = isTicked, onCheckedChange = { onTick() })
    } else {
        Box(
            modifier = Modifier
                .size(UNREAD_DOT)
                .clip(CircleShape)
                .background(if (isRead) Color.Transparent else ZillitTheme.colors.accent),
        )
    }
}

/**
 * What it is about, then a taste of it.
 *
 * Two columns rather than one line: sharing let a chatty preview push the
 * subject out of view, and the subject is what the list is read for.
 */
@Composable
private fun SubjectCell(message: EmailSummary, weight: FontWeight, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = message.subject.ifBlank { "(no subject)" },
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = weight),
            color = ZillitTheme.colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(SUBJECT_SHARE),
        )
        if (message.snippet.isNotBlank()) {
            ZillitText(
                text = message.snippet,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
                modifier = Modifier.weight(SNIPPET_SHARE),
            )
        }
    }
}

/**
 * Who wrote it: a face, then the name in a fixed column so the eye can walk
 * down it. A photo is read faster than a name — the board's rule, here too.
 */
@Composable
private fun SenderCell(
    message: EmailSummary,
    weight: FontWeight,
    compact: Boolean,
    loadAvatar: suspend (String) -> ImageBitmap?,
) {
    ZillitAvatar(
        name = message.senderName,
        image = rememberSenderFace(message.senderAddress, loadAvatar),
        size = ROW_AVATAR,
    )
    ZillitText(
        text = message.senderName,
        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = weight),
        color = ZillitTheme.colors.textPrimary,
        maxLines = 1,
        // Narrower when the reading pane has taken the width, so the subject
        // and time keep room to breathe.
        modifier = Modifier.width(if (compact) SENDER_WIDTH_COMPACT else SENDER_WIDTH),
    )
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

// The subject keeps the larger share; the snippet gives way first.
private const val SUBJECT_SHARE = 0.55f
private const val SNIPPET_SHARE = 0.45f
private val ROW_PADDING = 16.dp
private val ROW_AVATAR = 28.dp
private val SENDER_WIDTH = 180.dp
private val SENDER_WIDTH_COMPACT = 120.dp
private val TRASH_BUTTON = 24.dp
private val TIME_MIN_WIDTH = 56.dp
private val META_ICON = 14.dp
private val UNREAD_DOT = 8.dp
