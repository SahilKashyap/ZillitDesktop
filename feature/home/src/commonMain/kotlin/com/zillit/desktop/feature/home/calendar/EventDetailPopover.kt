package com.zillit.desktop.feature.home.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * One event, in full.
 *
 * A port of the web's `EventDetailPopover`
 * (`calendarV3/components/popovers/EventDetailPopover.jsx`), which the phones
 * draw too — the same eyebrow, the same card of icon-tiled rows, the same
 * invitee card with its stack of faces, and the same tinted bar along the
 * bottom saying where this user stands. The desktop had its own, plainer
 * arrangement of the same facts and read as a different product beside them.
 *
 * Editing is not here. Edit opens the form; this offers the actions that need
 * no form — accepting, declining, joining the call, and deleting.
 */
@Composable
internal fun EventDetailPopover(
    detail: EventDetailState,
    onEvent: (CalendarEvent2Event) -> Unit,
    modifier: Modifier = Modifier,
    onJoinCall: ((CalendarEvent) -> Unit)? = null,
    /** Opens the location in the browser's maps. Null leaves it as plain text. */
    onOpenLink: ((String) -> Unit)? = null,
) {
    val colors = ZillitTheme.colors

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.scrim.copy(alpha = SCRIM_ALPHA))
            // A barrier, not a control.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onEvent(CalendarEvent2Event.CloseDetail) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = POPOVER_WIDTH)
                .heightIn(max = POPOVER_MAX_HEIGHT)
                .clip(ZillitTheme.shapes.large)
                .background(colors.surface)
                .border(HAIRLINE, colors.border, ZillitTheme.shapes.large)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            DetailHeader(detail, onEvent)
            ZillitDivider()

            ZillitScrollColumn(
                modifier = Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(ZillitTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                AudienceTag(detail.event.audience)
                DetailCard(detail, onOpenLink)
                InviteeCard(detail, onEvent)
                DescriptionBlock(detail.event.description)
            }

            DetailFooter(detail, onEvent, onJoinCall)
        }
    }
}

/**
 * The eyebrow, the title, and the organiser's two buttons.
 *
 * "EVENT DETAILS · RECURRING" above the title rather than beside it: the title
 * is the one thing somebody reads, and a row that put a label first pushed a
 * long event name into an ellipsis to say nothing new.
 */
@Composable
private fun DetailHeader(detail: EventDetailState, onEvent: (CalendarEvent2Event) -> Unit) {
    val colors = ZillitTheme.colors
    val event = detail.event

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            Eyebrow(event.isRecurring)
            ZillitText(
                text = detail.titleLine(),
                style = ZillitTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    textDecoration = if (detail.isCancelled || detail.isDeclined) {
                        TextDecoration.LineThrough
                    } else {
                        null
                    },
                ),
                color = if (detail.isDeclined) colors.warning else colors.textPrimary,
                maxLines = 1,
            )
        }

        if (detail.permissions.canManage) {
            ZillitButton(
                text = str(S.delete),
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Trash,
                loading = detail.isBusy,
                onClick = { onEvent(CalendarEvent2Event.AskDeleteEvent) },
            )
            ZillitButton(
                text = str(S.edit_event),
                leadingIcon = ZillitIcons.Edit,
                onClick = { onEvent(CalendarEvent2Event.OpenForm(event)) },
            )
        }

        ZillitIconButton(
            icon = ZillitIcons.Close,
            contentDescription = str(S.close),
            onClick = { onEvent(CalendarEvent2Event.CloseDetail) },
            modifier = Modifier.offset(y = (-2).dp),
        )
    }
}

/** "EVENT DETAILS · RECURRING" — small, spaced, and in the accent. */
@Composable
private fun Eyebrow(isRecurring: Boolean) {
    val colors = ZillitTheme.colors

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitIcon(
            icon = ZillitIcons.Calendar,
            contentDescription = null,
            tint = colors.accent,
            size = EYEBROW_ICON,
        )
        // Uppercased in Kotlin rather than stored shouting, so the word keeps
        // its 22 translations.
        ZillitText(
            text = str(S.event_details).uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = EYEBROW_TRACKING,
            ),
            color = colors.accent,
        )
        if (isRecurring) {
            ZillitText(
                text = "·",
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
            ZillitText(
                text = str(S.recurring).uppercase(),
                style = ZillitTheme.typography.labelSmall.copy(letterSpacing = EYEBROW_TRACKING),
                color = colors.textMuted,
            )
        }
    }
}

/** Members or Personal — the web's pill above the card. */
@Composable
private fun AudienceTag(audience: EventAudience) {
    val colors = ZillitTheme.colors
    val members = audience == EventAudience.Members

    Box(
        modifier = Modifier
            .clip(ZillitTheme.shapes.pill)
            .background(if (members) colors.accentSoft else colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = str(audience.label),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (members) colors.accentText else colors.textSecondary,
        )
    }
}

/** One line of the card: a tiled icon, a headline, and a note under it. */
private data class InfoLine(
    val icon: ImageVector,
    val primary: String,
    val secondary: String? = null,
    /** Set only on a location that was picked on a map, which opens there. */
    val onPrimaryClick: (() -> Unit)? = null,
)

/**
 * Everything the grid block could not fit, as one bordered card of rows.
 *
 * The lines are gathered as data before any of them is drawn, so the last one
 * can drop its divider without every row having to know how many follow it.
 */
@Composable
private fun DetailCard(detail: EventDetailState, onOpenLink: ((String) -> Unit)?) {
    val event = detail.event
    val callType = event.callType

    val lines = buildList {
        add(
            InfoLine(
                icon = ZillitIcons.Clock,
                primary = detail.rangeLabel,
                secondary = detail.durationText
                    .takeIf { it.isNotBlank() }
                    ?.let { str(S.desktop_cal_duration_value, it) },
            ),
        )
        add(InfoLine(ZillitIcons.Calendar, detail.fullDateLabel, detail.weekLabel))
        event.location?.takeIf { it.isNotBlank() }?.let { location ->
            add(InfoLine(ZillitIcons.Pin, location, onPrimaryClick = event.mapOpener(onOpenLink)))
        }
        // A personal event has nobody to meet, so it carries no way of
        // meeting — the same condition the web puts on this row.
        if (event.audience == EventAudience.Members && callType != null) {
            add(InfoLine(ZillitIcons.Camera, str(callType.label)))
        }
        if (event.reminderMinutes > 0) {
            add(InfoLine(ZillitIcons.Bell, reminderLabel(event.reminderMinutes)))
        }
        add(organiserLine(detail))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surfaceRaised)
            .border(HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.lg),
    ) {
        lines.forEachIndexed { index, line -> InfoRow(line, divider = index < lines.lastIndex) }
    }
}

/**
 * Opens the place in the browser's maps — null when there is nothing to open.
 *
 * Half a point is no point: a location that was typed rather than picked
 * carries no coordinates, and a link to "null,null" is worse than plain words.
 */
private fun CalendarEvent.mapOpener(onOpenLink: ((String) -> Unit)?): (() -> Unit)? {
    val lat = locationLat ?: return null
    val lng = locationLng ?: return null
    val open = onOpenLink ?: return null
    return { open("$MAPS_SEARCH$lat,$lng") }
}

/** Who arranged it, and when they wrote it down. */
@Composable
private fun organiserLine(detail: EventDetailState): InfoLine {
    val event = detail.event
    val name = event.creatorName?.takeIf { it.isNotBlank() } ?: str(S.desktop_unknown)

    return InfoLine(
        icon = ZillitIcons.User,
        // The organiser who is not attending their own meeting, as the web
        // marks them: without it the attendee count reads one too many.
        primary = if (event.excludeOrganiser) "$name · ${str(S.excluded)}" else name,
        secondary = detail.createdLabel,
    )
}

@Composable
private fun InfoRow(line: InfoLine, divider: Boolean) {
    val colors = ZillitTheme.colors
    val open = line.onPrimaryClick

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            TileIcon(line.icon, ROW_TILE, colors.surfaceSunken, colors.textSecondary)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            ) {
                ZillitText(
                    text = line.primary,
                    style = ZillitTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Bold,
                        textDecoration = if (open != null) TextDecoration.Underline else null,
                    ),
                    color = if (open != null) colors.accent else colors.textPrimary,
                    modifier = open?.let { Modifier.clickable(onClick = it) } ?: Modifier,
                )
                line.secondary?.takeIf { it.isNotBlank() }?.let {
                    ZillitText(
                        text = it,
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
            }
        }
        if (divider) ZillitDivider()
    }
}

/**
 * The invitee card: a count, a stack of faces, and the way into the list.
 *
 * The whole card is the control rather than the "See invitees" words alone —
 * it is the only thing on the popover that leads anywhere, and a 540pt-wide
 * target beats a twelve-point one.
 */
@Composable
private fun InviteeCard(detail: EventDetailState, onEvent: (CalendarEvent2Event) -> Unit) {
    if (detail.inviteeCount == 0) return
    val colors = ZillitTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.accentSoft)
            .border(HAIRLINE, colors.accent, ZillitTheme.shapes.large)
            .clickable { onEvent(CalendarEvent2Event.ShowInvitees) }
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            TileIcon(ZillitIcons.Users, INVITEE_TILE, colors.accent, colors.textOnAccent)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            ) {
                ZillitText(
                    text = inviteeCountLabel(detail.inviteeCount),
                    style = ZillitTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
                ZillitText(
                    text = str(S.desktop_cal_click_to_view_search),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
            SeeInviteesCue()
        }

        AvatarStack(detail.inviteeFaces)
    }
}

/** "SEE INVITEES →" — the words and the arrow the phones also draw. */
@Composable
private fun SeeInviteesCue() {
    val colors = ZillitTheme.colors

    ZillitText(
        text = str(S.see_invitees).uppercase(),
        style = ZillitTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = EYEBROW_TRACKING,
        ),
        color = colors.accent,
        maxLines = 1,
    )
    ZillitIcon(
        icon = ZillitIcons.ArrowRight,
        contentDescription = null,
        tint = colors.accent,
        size = EYEBROW_ICON,
    )
}

/** An icon on its own soft rounded tile — the chip every row of the card wears. */
@Composable
private fun TileIcon(icon: ImageVector, size: Dp, background: Color, tint: Color) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(ROW_TILE_RADIUS))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon = icon, contentDescription = null, tint = tint, size = ROW_ICON)
    }
}

/** "21 Invitees" — the singular is its own key, never an inflected "(s)". */
@Composable
private fun inviteeCountLabel(count: Int): String = str(
    if (count == 1) S.desktop_cal_invitee_count_one else S.desktop_cal_invitee_count_many,
    count,
)

/**
 * Up to five faces, overlapping, with a "+N" bubble for the rest.
 *
 * Each face carries a ring in the card's own colour so the overlap reads as
 * depth rather than as a smear.
 */
@Composable
private fun AvatarStack(faces: List<EventInvitee>) {
    if (faces.isEmpty()) return
    val colors = ZillitTheme.colors

    Row(verticalAlignment = Alignment.CenterVertically) {
        faces.take(FACES_SHOWN).forEachIndexed { index, face ->
            Box(
                modifier = Modifier
                    .offset(x = STACK_OVERLAP * index)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .padding(FACE_RING),
            ) {
                ZillitAvatar(
                    name = face.name,
                    userId = face.userId.takeIf { it.isNotBlank() },
                    size = STACK_FACE,
                )
            }
        }
        if (faces.size > FACES_SHOWN) {
            Box(
                modifier = Modifier
                    .offset(x = STACK_OVERLAP * FACES_SHOWN)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .padding(FACE_RING),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(STACK_FACE)
                        .clip(CircleShape)
                        .background(colors.surfaceSunken),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitText(
                        text = "+${faces.size - FACES_SHOWN}",
                        style = ZillitTheme.typography.labelSmall
                            .copy(fontWeight = FontWeight.Bold),
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun DescriptionBlock(description: String?) {
    val text = description?.takeIf { it.isNotBlank() } ?: return

    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(
            text = str(S.description).uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = EYEBROW_TRACKING,
            ),
            color = ZillitTheme.colors.textMuted,
        )
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}

/**
 * The bar along the bottom: where this user stands, and what they can do.
 *
 * Full-bleed and tinted by that standing, as the web and the phones draw it —
 * accepted reads green, declined red, an unanswered invitation the accent.
 * An event nobody can act on has no bar at all rather than a row of disabled
 * buttons, which is most of a production's calendar for most of its crew.
 */
@Composable
private fun DetailFooter(
    detail: EventDetailState,
    onEvent: (CalendarEvent2Event) -> Unit,
    onJoinCall: ((CalendarEvent) -> Unit)?,
) {
    val colors = ZillitTheme.colors
    val joinable = onJoinCall != null && detail.canJoinCall
    val status = detail.event.inviteStatus

    when {
        // Expired before cancelled, as the web orders it: a cancelled event
        // from last month is past first and cancelled second.
        detail.hasFinished ->
            FooterNote(str(S.desktop_cal_event_expired), colors.surfaceSunken, colors.textPrimary)

        detail.isCancelled ->
            FooterNote(str(S.desktop_cal_event_cancelled), colors.dangerSoft, colors.danger)

        !joinable && !detail.permissions.canRespond && detail.error == null -> Unit

        else -> {
            val tint = when {
                !detail.permissions.canRespond -> colors.accentSoft
                status == InviteStatus.Accepted -> colors.successSoft
                status == InviteStatus.Rejected -> colors.dangerSoft
                else -> colors.accentSoft
            }
            FooterBar(detail, tint) {
                if (joinable) {
                    // The room is the event's own; everyone invited walks into
                    // the same one rather than ringing each other.
                    ZillitButton(
                        text = str(S.join_call),
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Phone,
                        onClick = { onJoinCall?.invoke(detail.event) },
                    )
                }
                AnswerButtons(detail, onEvent)
            }
        }
    }
}

/** A whole-width sentence in place of the buttons: expired, or cancelled. */
@Composable
private fun FooterNote(text: String, background: Color, textColour: Color) {
    ZillitDivider()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = textColour,
        )
    }
}

@Composable
private fun FooterBar(
    detail: EventDetailState,
    tint: Color,
    actions: @Composable RowScope.() -> Unit,
) {
    ZillitDivider()
    Column(modifier = Modifier.fillMaxWidth().background(tint)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            val line = detail.event.inviteStatus.message.takeIf { detail.permissions.canRespond }
            ZillitText(
                text = line.orEmpty(),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = ZillitTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            actions()
        }

        detail.error?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.danger,
                modifier = Modifier.padding(
                    start = ZillitTheme.spacing.lg,
                    end = ZillitTheme.spacing.lg,
                    bottom = ZillitTheme.spacing.sm,
                ),
            )
        }
    }
}

/**
 * Accept and Decline — only the answer they have not already given.
 *
 * Offering "Accept" to somebody who has accepted is a button that does
 * nothing, and the status line beside it already says so.
 */
@Composable
private fun RowScope.AnswerButtons(
    detail: EventDetailState,
    onEvent: (CalendarEvent2Event) -> Unit,
) {
    if (!detail.permissions.canRespond) return

    if (detail.event.inviteStatus != InviteStatus.Accepted) {
        ZillitButton(
            text = str(S.accept),
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Check,
            loading = detail.isBusy,
            onClick = { onEvent(CalendarEvent2Event.RespondToInvite(accept = true)) },
        )
    }
    if (detail.event.inviteStatus != InviteStatus.Rejected) {
        ZillitButton(
            text = str(S.decline),
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Close,
            loading = detail.isBusy,
            onClick = { onEvent(CalendarEvent2Event.RespondToInvite(accept = false)) },
        )
    }
}

/**
 * The title, prefixed when the event is not simply on.
 *
 * `desktop_cal_declined_reason` is reused for the declined prefix: it is
 * already "Declined: %1$s" in 22 languages, and a second key for the same
 * words would read English in every one of them.
 */
@Composable
private fun EventDetailState.titleLine(): String = when {
    isCancelled -> str(S.desktop_cal_cancelled_title, event.title)
    isDeclined -> str(S.desktop_cal_declined_reason, event.title)
    else -> event.title
}

/** The web's `maps/search` link, built only when the place has a point. */
private const val MAPS_SEARCH = "https://www.google.com/maps/search/?api=1&query="

private val POPOVER_WIDTH = 540.dp
private val POPOVER_MAX_HEIGHT = 680.dp
private val HAIRLINE = 1.dp
private val ROW_TILE = 40.dp
private val ROW_TILE_RADIUS = 12.dp
private val ROW_ICON = 18.dp
private val EYEBROW_ICON = 14.dp
private val EYEBROW_TRACKING = 0.6.sp
private val INVITEE_TILE = 36.dp
private val STACK_FACE = 30.dp
private val FACE_RING = 2.dp
private val STACK_OVERLAP = (-10).dp
private const val FACES_SHOWN = 5
private const val SCRIM_ALPHA = 0.55f
