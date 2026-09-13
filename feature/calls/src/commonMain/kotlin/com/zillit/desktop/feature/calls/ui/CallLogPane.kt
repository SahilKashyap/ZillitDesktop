package com.zillit.desktop.feature.calls.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.calls.domain.CallLogDirection
import com.zillit.desktop.feature.calls.domain.CallLogEntry
import com.zillit.desktop.feature.calls.domain.CallType

/**
 * The call history, as a list.
 *
 * Lives in `feature:calls` rather than in the chat tool it appears inside: the
 * chat module knows nothing about calls, and the app composes the two. Same
 * arrangement as the call buttons in the thread header.
 *
 * Android's Recent/Missed fragments in one pane: the two views as chips, a
 * search over the names, the trash that wipes the view after asking, and an
 * info affordance per row for the Call activity sheet.
 */
@Composable
fun CallLogPane(
    state: CallLogUiState,
    onEvent: (CallLogEvent) -> Unit,
    nameFor: (String) -> String?,
    nowMillis: Long,
    /** Names "You" in the detail sheet's roster; null leaves everyone by name. */
    selfUserId: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        PaneHeader(state, onEvent)
        ZillitSearchField(
            value = state.query,
            onValueChange = { onEvent(CallLogEvent.Search(it)) },
            placeholder = "Search calls",
        )
        state.error?.let { message ->
            ZillitNotice(text = message, tone = StatusTone.Rejected)
        }

        // The web's Ongoing rows, above the history: a call you can walk
        // into is the one thing on this tab that is happening now.
        if (state.ongoing.isNotEmpty()) OngoingSection(state.ongoing, onEvent)

        val shown = state.entries.matchingCounterpart(state.query, nameFor)
        when {
            state.entries.isEmpty() && state.isLoading -> PaneNote("Loading calls…")
            // Android's `delete_call_record`: the wipe's own empty state.
            state.entries.isEmpty() && state.deletedAll -> PaneNote(NO_RECORDS)
            state.entries.isEmpty() && state.missedOnly -> PaneNote("No missed calls.")
            state.entries.isEmpty() -> PaneNote("No calls yet.")
            shown.isEmpty() -> PaneNote("No calls match \"${state.query.trim()}\".")
            else -> CallLogList(state, shown, onEvent, nameFor, nowMillis)
        }
    }

    PaneOverlays(state, onEvent, nameFor, selfUserId)
}

/**
 * The dialogs, riding a window-level popup: this pane sits in the chat
 * tool's 320dp column, and a shell composed in place would be clipped to
 * it — a dialog wider than its host and a scrim over a third of the screen.
 */
@Composable
private fun PaneOverlays(
    state: CallLogUiState,
    onEvent: (CallLogEvent) -> Unit,
    nameFor: (String) -> String?,
    selfUserId: String?,
) {
    if (state.confirmingDelete) {
        WindowOverlay(onDismiss = { onEvent(CallLogEvent.CancelDeleteAll) }) {
            DeleteAllDialog(onEvent)
        }
    }
    state.joinConfirm?.let { call ->
        WindowOverlay(onDismiss = { onEvent(CallLogEvent.CancelJoinOngoing) }) {
            JoinOngoingDialog(call, selfUserId, onEvent)
        }
    }
    state.detail?.let { entry ->
        WindowOverlay(onDismiss = { onEvent(CallLogEvent.CloseDetail) }) {
            CallDetailDialog(
                entry = entry,
                selfUserId = selfUserId,
                nameFor = nameFor,
                onDismiss = { onEvent(CallLogEvent.CloseDetail) },
            )
        }
    }
}

/** The view chips, and the trash at the far end. */
@Composable
private fun PaneHeader(state: CallLogUiState, onEvent: (CallLogEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitChoiceChip(
            label = "All",
            selected = !state.missedOnly,
            onClick = { onEvent(CallLogEvent.ShowAll) },
        )
        ZillitChoiceChip(
            label = "Missed",
            selected = state.missedOnly,
            onClick = { onEvent(CallLogEvent.ShowMissed) },
        )
        Spacer(Modifier.weight(1f))
        // Nothing to wipe, nothing to press: Android answers an empty list
        // with a snackbar; a disabled control says the same without one.
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = "Delete all",
            onClick = { onEvent(CallLogEvent.DeleteAll) },
            enabled = state.entries.isNotEmpty() && !state.isDeleting,
            tint = ZillitTheme.colors.textMuted,
            size = HEADER_ICON,
        )
    }
}

/** "Ongoing" — one row per live call, with the web's Join / Switch here / Return. */
@Composable
private fun OngoingSection(ongoing: List<OngoingCall>, onEvent: (CallLogEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(
            text = "Ongoing",
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
            modifier = Modifier.padding(horizontal = ZillitTheme.spacing.sm),
        )
        ongoing.forEach { call ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(ROW_CORNER))
                    .background(colors.successSoft)
                    .padding(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitAvatar(name = call.title, size = ROW_AVATAR)
                Column(modifier = Modifier.weight(1f)) {
                    ZillitText(
                        text = call.title,
                        style = ZillitTheme.typography.bodyMedium,
                        color = colors.textPrimary,
                        maxLines = 1,
                    )
                    ZillitText(
                        text = "● Ongoing ${call.type.wire} call · ${call.count} in call",
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.success,
                        maxLines = 1,
                    )
                }
                ZillitButton(
                    text = call.verb.label,
                    size = ButtonSize.Small,
                    variant = if (call.verb == OngoingVerb.Return) ButtonVariant.Secondary else ButtonVariant.Primary,
                    onClick = { onEvent(CallLogEvent.PressOngoing(call)) },
                )
            }
        }
    }
}

/**
 * The web's join confirm (`Line3CallJoinButton.jsx`, `JoinConfirmDialog`):
 * who is already in, then the one button. A switch says why it is one.
 */
@Composable
private fun JoinOngoingDialog(call: OngoingCall, selfUserId: String?, onEvent: (CallLogEvent) -> Unit) {
    val switch = call.verb == OngoingVerb.Switch
    ZillitDialogShell(
        title = if (switch) "Switch to this call?" else "Join this call?",
        icon = ZillitIcons.Phone,
        onDismiss = { onEvent(CallLogEvent.CancelJoinOngoing) },
        visible = true,
        width = CONFIRM_WIDTH,
        actions = {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(CallLogEvent.CancelJoinOngoing) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (switch) "Switch here" else "Join call",
                onClick = { onEvent(CallLogEvent.ConfirmJoinOngoing) },
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitText(
                text = "${call.title} · ${call.count} in call",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            if (switch) {
                ZillitText(
                    text = "You're already in this call on another device.",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            if (call.inCall.isEmpty()) {
                ZillitText(
                    text = "No one has joined yet.",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            call.inCall.forEach { (id, name) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitAvatar(name = name.ifBlank { "?" }, size = ROW_AVATAR)
                    ZillitText(
                        text = name.ifBlank { "Someone" } + if (id == selfUserId) " (you)" else "",
                        style = ZillitTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Android's `are_you_sure_you_want_to_delete_all` (`strings.xml:912`), with
 * its No/Yes (`RecentCallFragment.kt:194-211`). Yes closes the question at
 * once and the wipe runs behind it, as the phone's dialog does.
 */
@Composable
private fun DeleteAllDialog(onEvent: (CallLogEvent) -> Unit) {
    ZillitDialogShell(
        title = "Alert",
        icon = ZillitIcons.Trash,
        onDismiss = { onEvent(CallLogEvent.CancelDeleteAll) },
        visible = true,
        width = CONFIRM_WIDTH,
        actions = {
            ZillitButton(
                text = "No",
                onClick = { onEvent(CallLogEvent.CancelDeleteAll) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = "Yes",
                onClick = { onEvent(CallLogEvent.ConfirmDeleteAll) },
                variant = ButtonVariant.Danger,
            )
        },
    ) {
        ZillitText(
            text = "Are you sure you want to delete all call logs?",
            style = ZillitTheme.typography.bodyMedium,
        )
    }
}

/**
 * A full-window layer for a dialog whose host is a narrow pane. The shell
 * inside paints its own scrim across the layer, so an outside click never
 * reaches the popup's own dismiss — the shell's barrier answers it.
 */
@Composable
private fun WindowOverlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun CallLogList(
    state: CallLogUiState,
    shown: List<CallLogEntry>,
    onEvent: (CallLogEvent) -> Unit,
    nameFor: (String) -> String?,
    nowMillis: Long,
) {
    ZillitLazyColumn(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        items(shown, key = CallLogEntry::callUuid) { entry ->
            CallLogRow(
                entry = entry,
                nameFor = nameFor,
                nowMillis = nowMillis,
                onRedial = { onEvent(CallLogEvent.Redial(entry)) },
                onDetail = { onEvent(CallLogEvent.ShowDetail(entry)) },
            )
        }
        // A search narrows what is on screen, not what is fetched — older
        // pages may hold the name being looked for.
        if (state.canLoadMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEvent(CallLogEvent.LoadMore) }
                        .padding(ZillitTheme.spacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    ZillitText(
                        text = if (state.isLoading) "Loading…" else "Show older",
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.accentText,
                    )
                }
            }
        }
    }
}

@Composable
private fun CallLogRow(
    entry: CallLogEntry,
    nameFor: (String) -> String?,
    nowMillis: Long,
    onRedial: () -> Unit,
    onDetail: () -> Unit,
) {
    val colors = ZillitTheme.colors
    val title = entry.displayTitle(nameFor)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ROW_CORNER))
            .background(if (hovered) colors.surfaceHover else colors.surface)
            .hoverable(interaction)
            // Only rows that can actually ring something are pressable; a row
            // whose peer left the production has nothing to redial.
            .then(if (entry.isRedialable) Modifier.clickable(onClick = onRedial) else Modifier)
            .padding(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitAvatar(name = title, size = ROW_AVATAR)
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitText(
                    text = title,
                    style = ZillitTheme.typography.bodyMedium,
                    // A missed call is the one row worth finding at a glance.
                    color = if (entry.missed) colors.danger else colors.textPrimary,
                    maxLines = 1,
                    // Yields to the tag, never the other way round: a long name
                    // ellipsises, and the line is still readable.
                    modifier = Modifier.weight(1f, fill = false),
                )
                // Which line carried it, as the detail sheet already says and
                // Android's rows leave to the sheet. On the row because the
                // lines are different call stacks, and "which one rang me" is
                // the first question when one of them is misbehaving. A tag,
                // not a subtitle segment: the subtitle is one line at 320dp
                // and the appended word is exactly what the ellipsis eats.
                ZillitTag(entry.line.label, tone = TagTone.Neutral)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                DirectionMark(entry)
                ZillitText(
                    text = entry.subtitle(nowMillis),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        RowTrailing(entry, hovered, onDetail)
    }
}

/**
 * The row's right edge: what the call was (or, under the cursor, what a click
 * does), then the info affordance.
 *
 * The info button is always composed, like the chat rows' star: a control
 * that exists only on hover loses the press to the row beneath it (see
 * `HomeFeedScreen`'s kebab). Its own click swallows the press, so opening the
 * sheet never also redials.
 */
@Composable
private fun RowTrailing(entry: CallLogEntry, hovered: Boolean, onDetail: () -> Unit) {
    val colors = ZillitTheme.colors
    // Under the cursor a redialable row says what a click does; at rest
    // it says what the call was. The two never show together — the phone
    // replaces the camera glyph rather than crowding it.
    when {
        hovered && entry.isRedialable -> ZillitIcon(
            icon = ZillitIcons.Phone,
            contentDescription = "Call again",
            tint = colors.success,
            size = ROW_ICON,
        )

        entry.type == CallType.Video -> ZillitIcon(
            icon = ZillitIcons.Camera,
            contentDescription = "Video call",
            tint = colors.textMuted,
            size = ROW_ICON,
        )
    }
    ZillitIconButton(
        icon = ZillitIcons.Info,
        contentDescription = "Call details",
        onClick = onDetail,
        tint = colors.textMuted,
        size = INFO_BUTTON,
    )
}

/**
 * The direction, on a tinted disc: red for missed, green for answered
 * incoming, the app accent for outgoing. The colour is the summary — the
 * list can be triaged without reading a word.
 */
@Composable
internal fun DirectionMark(entry: CallLogEntry) {
    val colors = ZillitTheme.colors
    val outgoing = entry.direction == CallLogDirection.Outgoing
    val disc = when {
        entry.missed -> colors.dangerSoft
        outgoing -> colors.accentSoft
        else -> colors.successSoft
    }
    val glyph = when {
        entry.missed -> colors.danger
        outgoing -> colors.accentText
        else -> colors.success
    }
    Box(
        modifier = Modifier
            .size(DIRECTION_DISC)
            .clip(CircleShape)
            .background(disc),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(
            icon = if (outgoing) ZillitIcons.ArrowRight else ZillitIcons.ArrowLeft,
            contentDescription = when {
                entry.missed -> "Missed"
                outgoing -> "Outgoing"
                else -> "Incoming"
            },
            tint = glyph,
            size = DIRECTION_GLYPH,
        )
    }
}

@Composable
private fun PaneNote(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ZillitText(
            text = text,
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
}

/** Android's `delete_call_record` (`res/values/strings.xml:1267`). */
internal const val NO_RECORDS = "There are no call records to delete."

private val ROW_CORNER = 10.dp
private val DIRECTION_DISC = 18.dp
private val DIRECTION_GLYPH = 11.dp
private val ROW_AVATAR = 32.dp
private val ROW_ICON = 14.dp
private val INFO_BUTTON = 22.dp
private val HEADER_ICON = 28.dp
private val CONFIRM_WIDTH = 380.dp
