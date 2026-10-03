package com.zillit.desktop.feature.calls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.calls.domain.CallLine
import com.zillit.desktop.feature.calls.domain.CallLogEntry
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallType

/**
 * The pane beside the calls list, WhatsApp Web's: "Voice and video calling"
 * until a row is picked, then that call's info — who, the call back, how it
 * went and who was on it. Closing the info returns to the invitation.
 *
 * Fed by the same [CallLogUiState] as [CallLogPane]: picking a row is
 * [CallLogEvent.ShowDetail], closing is [CallLogEvent.CloseDetail], and the
 * call back is the row's own [CallLogEvent.Redial].
 */
@Composable
fun CallLogSide(
    state: CallLogUiState,
    onEvent: (CallLogEvent) -> Unit,
    nameFor: (String) -> String?,
    nowMillis: Long,
    selfUserId: String? = null,
    lines: List<CallLine> = CallLine.DEFAULT,
    /** Opens the people to ring — the host's Contacts; null hides the button. */
    onStartCall: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(ZillitTheme.colors.panelGrey)) {
        val entry = state.detail
        if (entry == null) {
            CallsInvitation(onStartCall)
        } else {
            CallInfo(entry, nameFor, nowMillis, selfUserId, lines, onEvent)
        }
    }
}

/** Nothing picked: the big glyph, the two lines, and the way to start one. */
@Composable
private fun CallsInvitation(onStartCall: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md, Alignment.CenterVertically),
    ) {
        ZillitIcon(
            icon = ZillitIcons.Camera,
            contentDescription = null,
            tint = ZillitTheme.colors.textMuted,
            size = HERO_GLYPH,
        )
        ZillitText(
            text = str(S.desktop_voice_video_calling),
            style = ZillitTheme.typography.displayLarge.copy(fontWeight = FontWeight.Normal),
            textAlign = TextAlign.Center,
        )
        ZillitText(
            text = str(S.desktop_calls_empty_hint),
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = HINT_WIDTH),
        )
        if (onStartCall != null) {
            Box(Modifier.padding(top = ZillitTheme.spacing.md)) {
                RoundAction(ZillitIcons.Phone, str(S.desktop_start_call), onStartCall)
            }
        }
    }
}

/** A round glyph button with its word underneath — WhatsApp's pane actions. */
@Composable
private fun RoundAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clip(ZillitTheme.shapes.medium).clickable(onClick = onClick).padding(ZillitTheme.spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Box(
            Modifier.size(ACTION_DISC).clip(CircleShape).background(ZillitTheme.colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = icon, contentDescription = null, tint = ZillitTheme.colors.accentText, size = ACTION_GLYPH)
        }
        ZillitText(text = label, style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textPrimary)
    }
}

/** A picked call: its bar, who it was with, the call back, and the card. */
@Composable
@Suppress("LongParameterList") // The call and the pane's hooks, passed once.
private fun CallInfo(
    entry: CallLogEntry,
    nameFor: (String) -> String?,
    nowMillis: Long,
    selfUserId: String?,
    lines: List<CallLine>,
    onEvent: (CallLogEvent) -> Unit,
) {
    val title = entry.displayTitle(nameFor)
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitIconButton(
                icon = ZillitIcons.Close,
                contentDescription = str(S.close),
                onClick = { onEvent(CallLogEvent.CloseDetail) },
                tint = ZillitTheme.colors.textSecondary,
                size = BAR_ACTION,
            )
            ZillitText(text = str(S.desktop_call_info), style = ZillitTheme.typography.titleSmall)
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitAvatar(
                name = title,
                userId = entry.peerUserId.takeIf { entry.mode != CallMode.Group },
                size = INFO_AVATAR,
            )
            ZillitText(text = title, style = ZillitTheme.typography.titleLarge, textAlign = TextAlign.Center)
            ZillitText(
                text = entry.detailSubtitle(),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            if (entry.isRedialable) CallBack(entry, lines) { line -> onEvent(CallLogEvent.Redial(entry, line)) }
            CallCard(entry, nameFor, nowMillis, selfUserId)
        }
    }
}

/** The one call back, in the row's own kind; asks which line, as everywhere. */
@Composable
private fun CallBack(entry: CallLogEntry, lines: List<CallLine>, onPick: (CallLine) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val icon = if (entry.type == CallType.Video) ZillitIcons.Camera else ZillitIcons.Phone
    Box(Modifier.padding(vertical = ZillitTheme.spacing.sm)) {
        RoundAction(icon, str(S.desktop_call_again)) { if (lines.size == 1) onPick(lines.first()) else open = true }
        ZillitActionMenu(
            expanded = open,
            onDismissRequest = { open = false },
            entries = lines.map { line ->
                ZillitMenuEntry.Action(label = line.label, icon = icon, tone = ZillitMenuTone.Approve) {
                    open = false
                    onPick(line)
                }
            },
        )
    }
}

/** The white card: the day, how it went, and everyone who was on it. */
@Composable
private fun CallCard(entry: CallLogEntry, nameFor: (String) -> String?, nowMillis: Long, selfUserId: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = CARD_MAX_WIDTH)
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surface)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = callListStamp(entry.startedAtMillis, nowMillis),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        DetailChips(entry)
        ParticipantList(entry, selfUserId, nameFor)
    }
}

private val HERO_GLYPH = 72.dp
private val HINT_WIDTH = 420.dp
private val ACTION_DISC = 56.dp
private val ACTION_GLYPH = 22.dp
private val BAR_ACTION = 36.dp
private val INFO_AVATAR = 120.dp
private val CARD_MAX_WIDTH = 520.dp
