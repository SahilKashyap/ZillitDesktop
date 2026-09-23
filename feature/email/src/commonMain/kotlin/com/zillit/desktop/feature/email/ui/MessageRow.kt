package com.zillit.desktop.feature.email.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.email.domain.EmailSummary
import com.zillit.desktop.feature.email.domain.MailRow
import com.zillit.desktop.feature.email.domain.headerName
import com.zillit.desktop.feature.email.domain.mailListTimeLabel

/**
 * One row as the web's three-line card (`NewEmailCard.jsx`): an unread rail
 * on the left edge, the tick and the face, then sender — with the
 * conversation's `(N)` — over subject over snippet, the time and the
 * paperclip on the right edge. Unread rows carry the rail, the weight and
 * the brand-orange time; read rows recede to the canvas. With conversation
 * view on, the name and subject are the newest message's across every folder
 * (ZL-17843), while the snippet, the time and the tick stay this folder's.
 */
@Composable
@Suppress("LongParameterList")
internal fun MessageRow(
    row: MailRow,
    isActive: Boolean,
    isTicked: Boolean,
    nowMillis: Long,
    /** The Sent folder leads with who it went to, not who wrote it. */
    showRecipients: Boolean,
    onClick: () -> Unit,
    onTick: () -> Unit,
    /** The sender's photo when they are crew here; initials otherwise. */
    loadAvatar: suspend (String) -> ImageBitmap? = { null },
    /** What makes the row draggable onto a folder. */
    dragModifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val unread = row.hasUnread
    val weight = if (unread) FontWeight.SemiBold else FontWeight.Normal
    val message = row.message
    val shown = row.latest
    val background by animateColorAsState(
        when {
            isActive || isTicked -> colors.accentSoft
            hovered -> colors.surfaceHover
            else -> colors.surface
        },
        label = "messageRow",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = 1.dp)
            .height(IntrinsicSize.Min)
            .clip(ZillitTheme.shapes.medium)
            .background(background)
            .hoverable(interaction)
            .then(dragModifier)
            .clickable(onClick = onClick)
            .testTag("email-row-${row.id}"),
        verticalAlignment = Alignment.Top,
    ) {
        // The unread rail — the web's 3px `border-l-primary`.
        Box(
            Modifier
                .width(UNREAD_RAIL)
                .fillMaxHeight()
                .background(if (unread) colors.accent else Color.Transparent),
        )

        Row(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            Box(Modifier.padding(top = 2.dp)) {
                ZillitCheckbox(
                    checked = isTicked,
                    onCheckedChange = { onTick() },
                    modifier = Modifier.testTag("email-tick-${row.id}"),
                )
            }

            SenderFace(shown, unread, loadAvatar)

            MessageLines(message, shown, row, showRecipients, weight, nowMillis)
        }
    }
}

/**
 * The three stacked lines beside the face: who and when, subject and clip,
 * then the snippet.
 */
@Composable
@Suppress("LongParameterList")
private fun MessageLines(
    message: EmailSummary,
    shown: EmailSummary,
    row: MailRow,
    showRecipients: Boolean,
    weight: FontWeight,
    nowMillis: Long,
) {
    val colors = ZillitTheme.colors

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        val who = if (showRecipients) {
            "To: ${shown.to.joinToString(", ") { it.headerName() }.ifBlank { shown.senderName }}"
        } else {
            shown.senderName
        }
        WhoAndWhen(who, row, weight, mailListTimeLabel(message.receivedAtMillis, nowMillis))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = shown.subject.ifBlank { str(S.no_subject) },
                style = ZillitTheme.typography.bodySmall.copy(fontWeight = weight),
                color = if (row.hasUnread) colors.textPrimary else colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (message.hasAttachments) {
                ZillitIcon(
                    icon = ZillitIcons.Paperclip,
                    contentDescription = str(S.has_attachments_txt),
                    tint = colors.textMuted,
                    size = META_ICON,
                )
            }
        }

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

/**
 * The first line: the name (or the recipients, in Sent), the web's `(N)`
 * count in its own non-shrinking span so the name is what truncates on a
 * narrow list (ZL-17837), and the time hard against the right edge.
 */
@Composable
private fun WhoAndWhen(who: String, row: MailRow, weight: FontWeight, time: String) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitText(
                text = who,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = weight),
                color = colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (row.showsCount) {
                ZillitText(
                    text = "(${row.count})",
                    style = ZillitTheme.typography.labelSmall,
                    color = if (row.hasUnread) colors.textSecondary else colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        ZillitText(
            text = time,
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = weight),
            color = if (row.hasUnread) colors.accentText else colors.textMuted,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = TIME_MIN_WIDTH),
        )
    }
}

/**
 * The face, with the web's unread dot pinned to its corner
 * (`NewEmailCard.jsx` — the initial fallback derives from the same key as
 * the photo lookup, so the two never disagree).
 */
@Composable
private fun SenderFace(shown: EmailSummary, unread: Boolean, loadAvatar: suspend (String) -> ImageBitmap?) {
    Box {
        ZillitAvatar(
            name = shown.senderName,
            image = rememberSenderFace(shown.senderAddress, loadAvatar),
            size = ROW_AVATAR,
        )
        if (unread) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 1.dp, y = (-1).dp)
                    .size(UNREAD_DOT)
                    .clip(CircleShape)
                    .background(ZillitTheme.colors.surface)
                    .padding(1.dp)
                    .clip(CircleShape)
                    .background(ZillitTheme.colors.accent),
            )
        }
    }
}

/**
 * A saved draft as the web's `DraftEmailCard`: a red "Draft" label where the
 * sender goes, its subject, its preview, and the time it was last touched.
 */
@Composable
internal fun DraftRow(
    row: MailRow,
    isTicked: Boolean,
    nowMillis: Long,
    onClick: () -> Unit,
    onTick: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val message = row.message

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = 1.dp)
            .clip(ZillitTheme.shapes.medium)
            .background(
                when {
                    isTicked -> colors.accentSoft
                    hovered -> colors.surfaceHover
                    else -> colors.surface
                },
            )
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm)
            .testTag("email-row-${row.id}"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Box(Modifier.padding(top = 2.dp)) {
            ZillitCheckbox(checked = isTicked, onCheckedChange = { onTick() })
        }
        ZillitAvatar(name = "D", size = ROW_AVATAR)
        DraftLines(message, mailListTimeLabel(message.receivedAtMillis, nowMillis), Modifier.weight(1f))
    }
}

/** "Draft" in the web's red, the time, the subject and clip, the snippet. */
@Composable
private fun DraftLines(message: EmailSummary, time: String, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = str(S.draft),
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.danger,
                modifier = Modifier.weight(1f),
            )
            ZillitText(
                text = time,
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = message.subject.ifBlank { str(S.no_subject_parenthesis) },
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (message.hasAttachments) {
                ZillitIcon(
                    ZillitIcons.Paperclip,
                    contentDescription = null,
                    tint = colors.textMuted,
                    size = META_ICON,
                )
            }
        }
        if (message.snippet.isNotBlank()) {
            ZillitText(
                text = message.snippet,
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                maxLines = 1,
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
internal fun rememberSenderFace(
    address: String,
    load: suspend (String) -> ImageBitmap?,
): ImageBitmap? = produceState<ImageBitmap?>(initialValue = null, address) {
    value = address.takeIf { it.isNotBlank() }?.let { load(it) }
}.value

private val ROW_AVATAR = 36.dp
private val TIME_MIN_WIDTH = 56.dp
private val META_ICON = 14.dp
private val UNREAD_DOT = 10.dp
private val UNREAD_RAIL = 3.dp
