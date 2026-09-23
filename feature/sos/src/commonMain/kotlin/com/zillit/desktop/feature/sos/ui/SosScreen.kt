package com.zillit.desktop.feature.sos.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitDimens
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitSkeletonBar
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.sos.domain.SosAlert

/**
 * The SOS page.
 *
 * A hero panel that carries the alarm and everything the reader should know
 * before pressing it, then the two lists side by side: the alerts this
 * production has raised, and the receivers who get the next one. The columns
 * stack when the window is narrower than [TWO_COLUMN_MIN] — the tool opens as
 * a window, and a window can be pulled to any width.
 *
 * The whole page scrolls rather than either list — a lazy list inside a
 * scrolling page crashes on infinite constraints, and a page of at most fifty
 * rows does not need virtualising.
 *
 * Every colour comes from [ZillitTheme], so light and dark are the same code.
 */
@Composable
fun SosScreen(state: SosUiState, onEvent: (SosEvent) -> Unit, mayCall: Boolean = false) {
    Box(Modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        ZillitScrollColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            SosHero(state, onEvent)
            state.error?.let { message -> ErrorNotice(message, onEvent) }
            SosBody(state, mayCall, onEvent)
        }
        state.confirm?.let { ConfirmDialog(it, onEvent) }
    }
}

@Composable
private fun SosBody(state: SosUiState, mayCall: Boolean, onEvent: (SosEvent) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= TWO_COLUMN_MIN) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                AlertsCard(state, mayCall, onEvent, Modifier.weight(ALERTS_COLUMN_WEIGHT))
                SosReceiversCard(state, onEvent, Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
                AlertsCard(state, mayCall, onEvent, Modifier.fillMaxWidth())
                SosReceiversCard(state, onEvent, Modifier.fillMaxWidth())
            }
        }
    }
}

// Hero ----------------------------------------------------------------------

/**
 * The alarm and its warning on one panel.
 *
 * The web puts the warning in a tooltip on the button (`SOSMain.jsx:589-605`);
 * here it is the panel's own copy, so nobody has to hover to learn what the
 * button does before pressing it.
 */
@Composable
private fun SosHero(state: SosUiState, onEvent: (SosEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.surface)
            .border(HAIRLINE, colors.border, ZillitTheme.shapes.large)
            .padding(ZillitTheme.spacing.xl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl),
    ) {
        SirenMark()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ZillitSectionLabel(str(S.desktop_sos_emergency))
            ZillitText(text = str(S.sos), style = ZillitTheme.typography.titleLarge, maxLines = 1)
            ZillitText(
                text = str(S.desktop_sos_intro),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 3,
            )
            ReachPill(state)
        }
        ZillitButton(
            text = str(S.desktop_sos_send),
            onClick = { onEvent(SosEvent.AskSendAlert) },
            variant = ButtonVariant.Danger,
            leadingIcon = ZillitIcons.Siren,
            enabled = !state.busy,
            loading = state.busy,
        )
    }
}

/** Who the next alarm reaches, so an empty receiver list is noticed before it matters. */
@Composable
private fun ReachPill(state: SosUiState) {
    val contacts = state.contacts
    if (contacts.loading && contacts.rows.isEmpty()) return
    val crew = contacts.members.size
    val outside = contacts.outsiders.size
    val label = when {
        crew + outside == 0 -> str(S.desktop_sos_no_receivers_yet)
        else -> str(
            S.desktop_sos_reaches,
            crew.count(S.desktop_sos_crew_member_one, S.desktop_sos_crew_members),
            outside.count(S.desktop_sos_outside_contact_one, S.desktop_sos_outside_contacts),
        )
    }
    Box(Modifier.padding(top = ZillitTheme.spacing.xs)) {
        ZillitStatusPill(
            label = label,
            tone = if (crew + outside == 0) StatusTone.Pending else StatusTone.Ready,
            dot = true,
        )
    }
}

private fun Int.count(one: String, many: String): String = if (this == 1) str(one) else str(many, this)

/**
 * The siren in a soft red disc, with two rings breathing out of it.
 *
 * Slow and faint on purpose: the page can sit open all day beside other
 * work, and a mark that flashes would be a nuisance long before it was an
 * alarm. The motion is only there to say the button is live.
 */
@Composable
private fun SirenMark() {
    val colors = ZillitTheme.colors
    val transition = rememberInfiniteTransition(label = "siren-pulse")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "siren-pulse-phase",
    )
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(SIREN_DISC + PULSE_ROOM)) {
            val base = SIREN_DISC.toPx() / 2f
            PULSE_OFFSETS.forEach { offset ->
                val progress = (phase + offset) % 1f
                drawCircle(
                    color = colors.danger,
                    radius = base * (1f + PULSE_GROWTH * progress),
                    alpha = PULSE_ALPHA * (1f - progress),
                    style = Stroke(width = PULSE_STROKE.toPx()),
                )
            }
        }
        Box(
            modifier = Modifier.size(SIREN_DISC).clip(CircleShape).background(colors.dangerSoft),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(ZillitIcons.Siren, tint = colors.danger, size = SIREN_GLYPH)
        }
    }
}

@Composable
private fun ErrorNotice(message: String, onEvent: (SosEvent) -> Unit) {
    ZillitNotice(
        text = message,
        tone = StatusTone.Rejected,
        icon = ZillitIcons.Warning,
        action = {
            ZillitButton(
                text = str(S.sync_action_dismiss),
                onClick = { onEvent(SosEvent.DismissError) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        },
    )
}

// Alerts -------------------------------------------------------------------

@Composable
private fun AlertsCard(
    state: SosUiState,
    mayCall: Boolean,
    onEvent: (SosEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    ZillitSectionCard(
        modifier = modifier,
        title = str(S.desktop_sos_alerts),
        icon = ZillitIcons.Bell,
        meta = if (state.alerts.isEmpty()) null else "${state.alerts.size}",
        action = {
            ZillitIconButton(
                icon = ZillitIcons.Reload,
                contentDescription = str(S.desktop_sos_refresh_alerts),
                onClick = { onEvent(SosEvent.Refresh) },
                enabled = !state.loading,
            )
            if (state.alerts.isNotEmpty()) {
                ZillitButton(
                    text = str(S.txt_clear_all),
                    onClick = { onEvent(SosEvent.AskDeleteAllAlerts) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Trash,
                    enabled = !state.busy,
                )
            }
        },
    ) {
        when {
            state.loading && state.alerts.isEmpty() -> AlertsSkeleton()

            state.loaded && state.alerts.isEmpty() -> ZillitEmptyState(
                title = str(S.desktop_sos_all_quiet),
                message = str(S.desktop_sos_nobody_raised_alarm),
                icon = ZillitIcons.Shield,
            )

            else -> Column(
                modifier = Modifier.animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                state.alerts.forEach { alert ->
                    AlertCard(
                        alert = alert,
                        viewerId = state.viewer.userId,
                        busy = state.busy,
                        mayCall = mayCall,
                        onEvent = onEvent,
                    )
                }
                MoreRow(state, onEvent)
            }
        }
    }
}

/** Three ghost cards while the first page is in flight, so the layout does not jump when it lands. */
@Composable
private fun AlertsSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        repeat(SKELETON_ROWS) {
            Row(
                // On the card's own surface, not a sunken well: a grey bar on a
                // grey well is invisible in light mode.
                modifier = Modifier
                    .fillMaxWidth()
                    .border(HAIRLINE, ZillitTheme.colors.divider, ZillitTheme.shapes.medium)
                    .padding(ZillitTheme.spacing.md),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                ZillitSkeletonBar(Modifier.size(AVATAR), height = AVATAR)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    ZillitSkeletonBar(Modifier.fillMaxWidth(SKELETON_TITLE_FRACTION))
                    ZillitSkeletonBar(Modifier.fillMaxWidth())
                    ZillitSkeletonBar(Modifier.fillMaxWidth(SKELETON_META_FRACTION))
                }
            }
        }
    }
}

@Composable
private fun MoreRow(state: SosUiState, onEvent: (SosEvent) -> Unit) {
    if (!state.hasMore && !state.loadingMore) return
    Box(Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.sm), Alignment.Center) {
        if (state.loadingMore) {
            ZillitSpinner()
        } else {
            ZillitButton(
                text = str(S.desktop_show_older),
                onClick = { onEvent(SosEvent.LoadOlder) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                trailingIcon = ZillitIcons.ChevronDown,
            )
        }
    }
}

/**
 * One alert: who raised it, what it said, when, and the things that can be
 * done about it — ring them, open the map link, delete the row.
 *
 * A received alert wears a red edge; one the viewer sent wears a grey one.
 * The actions are always composed and only the card's tint answers the
 * hover: a control that appears on hover never gets the click that
 * revealed it.
 */
@Composable
private fun AlertCard(
    alert: SosAlert,
    viewerId: String,
    busy: Boolean,
    mayCall: Boolean,
    onEvent: (SosEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val sent = alert.senderId.isNotBlank() && alert.senderId == viewerId
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        targetValue = if (hovered) colors.surfaceHover else colors.surfaceSunken,
        label = "alert-hover",
    )
    val edge = if (sent) colors.borderStrong else colors.danger
    val name = alert.senderNameHint.ifBlank { if (sent) str(S.you) else str(S.desktop_sos_unknown_sender) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(background)
            .drawBehind { drawRect(edge, size = Size(EDGE_STRIP.toPx(), size.height)) }
            .hoverable(interaction)
            .padding(start = ZillitTheme.spacing.lg, end = ZillitTheme.spacing.md)
            .padding(vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitAvatar(name = name, userId = alert.senderId, size = AVATAR)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            AlertHead(name, sent)
            ZillitText(text = alert.text, style = ZillitTheme.typography.bodyMedium)
            AlertMeta(alert)
        }
        AlertActions(alert, sent, busy, mayCall, onEvent)
    }
}

@Composable
private fun AlertHead(name: String, sent: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = name,
            style = ZillitTheme.typography.titleSmall,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        // "Sent" / "Received", the two words the web puts on the card
        // (`SOSMain.jsx:451-457`).
        ZillitStatusPill(
            label = if (sent) str(S.cs_sent) else str(S.recived),
            tone = if (sent) StatusTone.Neutral else StatusTone.Rejected,
            dot = !sent,
        )
    }
}

/** Stamp and contact number, wrapping rather than ellipsing — half a phone number is no number. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertMeta(alert: SosAlert) {
    val colors = ZillitTheme.colors
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        MetaChip(ZillitIcons.Clock, alert.timeLabel)
        if (alert.contactInfo.isNotBlank()) MetaChip(ZillitIcons.Phone, alert.contactInfo)
    }
    if (alert.isEntertainment) {
        ZillitText(
            text = str(S.desktop_sos_gsm_call_hint),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
        )
    }
}

@Composable
private fun MetaChip(icon: ImageVector, text: String) {
    if (text.isBlank()) return
    val colors = ZillitTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitIcon(icon, tint = colors.textMuted, size = ZillitDimens.iconSmall)
        ZillitText(text = text, style = ZillitTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1)
    }
}

@Composable
private fun AlertActions(
    alert: SosAlert,
    sent: Boolean,
    busy: Boolean,
    mayCall: Boolean,
    onEvent: (SosEvent) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        // Only on somebody else's alert: your own has nobody to ring, and a
        // button that always answers "this is your own alert" is furniture.
        if (mayCall && !sent && alert.senderId.isNotBlank()) {
            ZillitIconButton(
                icon = ZillitIcons.Phone,
                contentDescription = str(S.desktop_sos_call_them),
                onClick = { onEvent(SosEvent.CallSender(alert.id, video = false)) },
                enabled = !busy,
            )
            ZillitIconButton(
                icon = ZillitIcons.Camera,
                contentDescription = str(S.desktop_sos_video_call_them),
                onClick = { onEvent(SosEvent.CallSender(alert.id, video = true)) },
                enabled = !busy,
            )
        }
        ZillitIconButton(
            icon = ZillitToolIcons.Location,
            contentDescription = str(S.desktop_sos_open_location),
            onClick = { onEvent(SosEvent.OpenMap(alert.id)) },
            enabled = alert.mapsUrl.isNotBlank(),
        )
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = str(S.desktop_sos_delete_alert),
            onClick = { onEvent(SosEvent.AskDeleteAlert(alert.id)) },
            enabled = !busy,
            tint = ZillitTheme.colors.danger,
        )
    }
}

// Confirmation --------------------------------------------------------------

/**
 * One dialog for all four irreversible acts. The delete wording is Android's
 * `record_delete_confirmation`; the send wording is new, because neither other
 * client asks before raising the alarm.
 */
@Composable
private fun ConfirmDialog(confirm: SosConfirm, onEvent: (SosEvent) -> Unit) {
    val title = when (confirm) {
        SosConfirm.SendAlert -> str(S.desktop_sos_send_question)
        is SosConfirm.DeleteAlert -> str(S.desktop_sos_delete_alert_question)
        SosConfirm.DeleteAllAlerts -> str(S.desktop_sos_clear_all_alerts_question)
        is SosConfirm.DeleteContact -> str(S.desktop_sos_remove_receiver_question)
    }
    val message = when (confirm) {
        SosConfirm.SendAlert -> str(S.desktop_sos_send_confirm_body)
        is SosConfirm.DeleteAlert, is SosConfirm.DeleteContact -> str(S.desktop_delete_record_confirm)
        SosConfirm.DeleteAllAlerts -> str(S.desktop_sos_clear_all_confirm)
    }
    val confirmLabel = when (confirm) {
        SosConfirm.SendAlert -> str(S.desktop_sos_send_now)
        is SosConfirm.DeleteAlert -> str(S.delete)
        SosConfirm.DeleteAllAlerts -> str(S.txt_clear_all)
        is SosConfirm.DeleteContact -> str(S.remove)
    }
    ZillitDialogShell(
        title = title,
        onDismiss = { onEvent(SosEvent.CancelConfirm) },
        visible = true,
        icon = if (confirm == SosConfirm.SendAlert) ZillitIcons.Siren else ZillitIcons.Warning,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(SosEvent.CancelConfirm) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = confirmLabel,
                onClick = { onEvent(SosEvent.ConfirmAction) },
                variant = ButtonVariant.Danger,
                leadingIcon = if (confirm == SosConfirm.SendAlert) ZillitIcons.Siren else null,
            )
        },
    ) {
        ZillitText(text = message, color = ZillitTheme.colors.textSecondary)
    }
}

/** Below this the alerts and receivers stack instead of sitting side by side. */
private val TWO_COLUMN_MIN = 720.dp

/** Alerts get a little more room than receivers: their rows carry a message and four buttons. */
private const val ALERTS_COLUMN_WEIGHT = 1.15f

private val HAIRLINE = 1.dp
private val EDGE_STRIP = 3.dp
private val AVATAR = 36.dp

private val SIREN_DISC = 56.dp
private val SIREN_GLYPH = 26.dp
private val PULSE_ROOM = 28.dp
private val PULSE_STROKE = 1.5.dp
private const val PULSE_MS = 2800
private const val PULSE_GROWTH = 0.5f
private const val PULSE_ALPHA = 0.4f
private val PULSE_OFFSETS = listOf(0f, 0.5f)

private const val SKELETON_ROWS = 3
private const val SKELETON_TITLE_FRACTION = 0.4f
private const val SKELETON_META_FRACTION = 0.6f
