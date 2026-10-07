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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.calls.domain.CallLine
import com.zillit.desktop.feature.calls.domain.CallLogDirection
import com.zillit.desktop.feature.calls.domain.CallLogEntry
import com.zillit.desktop.feature.calls.domain.CallMode
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
    /**
     * The counterpart's job title, under their name on the row — the same
     * designation the stage tiles and the roster show. Null, or a blank, and
     * the row is the name alone, as it was.
     */
    designationFor: (String) -> String? = { null },
    nowMillis: Long,
    /** Names "You" in the detail sheet's roster; null leaves everyone by name. */
    selfUserId: String? = null,
    /**
     * The lines a call-back may go on — asked on the click, as the thread
     * header asks and Android's `launchWithLineSelection` asks. The host
     * appends Line 3 where the production has it.
     */
    lines: List<CallLine> = CallLine.DEFAULT,
    /**
     * True where a side pane shows the picked call ([CallLogSide]) — WhatsApp
     * Web's layout: a row click selects the call and the pane beside the list
     * shows it, with the call-back under the row's hover glyph. False (the
     * widget, with no room beside the list) keeps click-to-redial and the
     * detail dialog.
     */
    inlineDetail: Boolean = false,
) {
    // WhatsApp's calls list: the search pill and the filters in a padded
    // block, then the rows edge to edge under a "Recent" heading.
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        Column(
            modifier = Modifier.padding(horizontal = ZillitTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitSearchField(
                value = state.query,
                onValueChange = { onEvent(CallLogEvent.Search(it)) },
                placeholder = str(S.desktop_search_calls),
                containerColor = ZillitTheme.colors.panelGrey,
                bordered = false,
            )
            PaneHeader(state, onEvent)
            state.error?.let { message ->
                ZillitNotice(text = message, tone = StatusTone.Rejected)
            }
        }

        // The web's Ongoing rows, above the history: a call you can walk
        // into is the one thing on this tab that is happening now.
        if (state.ongoing.isNotEmpty()) OngoingSection(state.ongoing, onEvent)

        val shown = state.entries.matchingCounterpart(state.query, nameFor)
        when {
            state.entries.isEmpty() && state.isLoading -> PaneNote(str(S.desktop_call_loading_calls))
            // Android's `delete_call_record`: the wipe's own empty state.
            state.entries.isEmpty() && state.deletedAll -> PaneNote(NO_RECORDS)
            state.entries.isEmpty() && state.missedOnly -> PaneNote(str(S.desktop_call_no_missed_calls))
            state.entries.isEmpty() -> PaneNote(str(S.desktop_call_no_calls_yet))
            shown.isEmpty() -> PaneNote(str(S.desktop_call_no_calls_match, state.query.trim()))
            else -> CallLogList(state, shown, onEvent, nameFor, designationFor, nowMillis, lines, inlineDetail)
        }
    }

    PaneOverlays(state, onEvent, nameFor, selfUserId, showDetail = !inlineDetail)
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
    /** False where the side pane shows the picked call instead of a dialog. */
    showDetail: Boolean = true,
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
    state.detail?.takeIf { showDetail }?.let { entry ->
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
        FilterPill(str(S.all), selected = !state.missedOnly) { onEvent(CallLogEvent.ShowAll) }
        FilterPill(str(S.missed), selected = state.missedOnly) { onEvent(CallLogEvent.ShowMissed) }
        Spacer(Modifier.weight(1f))
        // Nothing to wipe, nothing to press: Android answers an empty list
        // with a snackbar; a disabled control says the same without one.
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = str(S.delete_all),
            onClick = { onEvent(CallLogEvent.DeleteAll) },
            enabled = state.entries.isNotEmpty() && !state.isDeleting,
            tint = ZillitTheme.colors.textMuted,
            size = HEADER_ICON,
        )
    }
}

/**
 * One view filter, the chat listing's pill: the panel grey at rest, the
 * accent's soft tint and text colour when chosen.
 */
@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    ZillitText(
        text = label,
        style = ZillitTheme.typography.label.copy(
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        ),
        color = if (selected) colors.accentText else colors.textSecondary,
        maxLines = 1,
        modifier = Modifier
            .clip(ZillitTheme.shapes.pill)
            .background(
                when {
                    selected -> colors.accentSoft
                    hovered -> colors.surfaceHover
                    else -> colors.panelGrey
                },
            )
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = PILL_PAD_V),
    )
}

/** A section's heading over its rows — "Ongoing", "Recent" — WhatsApp's. */
@Composable
private fun SectionHeading(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.titleSmall,
        color = ZillitTheme.colors.textPrimary,
        modifier = Modifier.padding(
            start = ZillitTheme.spacing.md,
            end = ZillitTheme.spacing.md,
            top = ZillitTheme.spacing.sm,
            bottom = ZillitTheme.spacing.xs,
        ),
    )
}

/** "Ongoing" — one row per live call, with the web's Join / Switch here / Return. */
@Composable
private fun OngoingSection(ongoing: List<OngoingCall>, onEvent: (CallLogEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier.padding(horizontal = ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        SectionHeading(str(S.ongoing))
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
                ZillitAvatar(
                    name = call.title,
                    // A 1:1 call's face is whoever is in it — the row is titled after them.
                    userId = call.inCall.singleOrNull()?.first.takeIf { call.mode != CallMode.Group },
                    size = ROW_AVATAR,
                )
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
        title = if (switch) str(S.desktop_call_switch_to_this_call) else str(S.desktop_call_join_this_call),
        icon = ZillitIcons.Phone,
        onDismiss = { onEvent(CallLogEvent.CancelJoinOngoing) },
        visible = true,
        width = CONFIRM_WIDTH,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(CallLogEvent.CancelJoinOngoing) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = if (switch) str(S.desktop_call_switch_here) else str(S.join_call),
                onClick = { onEvent(CallLogEvent.ConfirmJoinOngoing) },
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitText(
                text = str(S.desktop_call_title_count_in_call, call.title, call.count),
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            if (switch) {
                ZillitText(
                    text = str(S.desktop_call_already_in_on_another_device),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            if (call.inCall.isEmpty()) {
                ZillitText(
                    text = str(S.desktop_call_no_one_has_joined_yet),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textMuted,
                )
            }
            call.inCall.forEach { (id, name) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                ) {
                    ZillitAvatar(name = name.ifBlank { "?" }, userId = id, size = ROW_AVATAR)
                    val shown = name.ifBlank { str(S.history_someone) }
                    ZillitText(
                        text = if (id == selfUserId) str(S.desktop_name_you_suffix, shown) else shown,
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
        title = str(S.alert),
        icon = ZillitIcons.Trash,
        onDismiss = { onEvent(CallLogEvent.CancelDeleteAll) },
        visible = true,
        width = CONFIRM_WIDTH,
        actions = {
            ZillitButton(
                text = str(S.no),
                onClick = { onEvent(CallLogEvent.CancelDeleteAll) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.yes),
                onClick = { onEvent(CallLogEvent.ConfirmDeleteAll) },
                variant = ButtonVariant.Danger,
            )
        },
    ) {
        ZillitText(
            text = str(S.are_you_sure_you_want_to_delete_all),
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
@Suppress("LongParameterList") // One row's data and the pane's hooks, passed through.
private fun CallLogList(
    state: CallLogUiState,
    shown: List<CallLogEntry>,
    onEvent: (CallLogEvent) -> Unit,
    nameFor: (String) -> String?,
    designationFor: (String) -> String?,
    nowMillis: Long,
    lines: List<CallLine>,
    inlineDetail: Boolean,
) {
    ZillitLazyColumn {
        item(key = "recent-heading") { SectionHeading(str(S.recent)) }
        items(shown, key = CallLogEntry::callUuid) { entry ->
            CallLogRow(
                entry = entry,
                nameFor = nameFor,
                designationFor = designationFor,
                nowMillis = nowMillis,
                lines = lines,
                onRedial = { line -> onEvent(CallLogEvent.Redial(entry, line)) },
                onDetail = { onEvent(CallLogEvent.ShowDetail(entry)) },
                inlineDetail = inlineDetail,
                selected = inlineDetail && state.detail?.callUuid == entry.callUuid,
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
                        text = if (state.isLoading) str(S.ah_loading) else str(S.desktop_show_older),
                        style = ZillitTheme.typography.labelSmall,
                        color = ZillitTheme.colors.accentText,
                    )
                }
            }
        }
    }
}

/**
 * One history row, WhatsApp's: the face, the name with the day at the far
 * end, and under it how the call went — the kind's glyph and "Incoming",
 * "Outgoing" or a red "Missed" — and the line that carried it.
 *
 * Beside a side pane ([inlineDetail]) a click picks the row and the pane
 * shows the call; the call-back is the phone glyph that appears under the
 * cursor. Without one, a click on a redialable row asks which line and rings
 * — the same menu the thread header's call buttons open — and the info glyph
 * opens the detail sheet. Either way the call goes out with the row's own
 * type, as Android's recents redial it.
 */
@Composable
@Suppress("LongParameterList", "LongMethod") // The row's data, its two verbs and the line picker.
private fun CallLogRow(
    entry: CallLogEntry,
    nameFor: (String) -> String?,
    designationFor: (String) -> String?,
    nowMillis: Long,
    lines: List<CallLine>,
    onRedial: (CallLine) -> Unit,
    onDetail: () -> Unit,
    inlineDetail: Boolean = false,
    selected: Boolean = false,
) {
    val colors = ZillitTheme.colors
    val title = entry.displayTitle(nameFor)
    val designation = entry.displayDesignation(designationFor)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // The line picker, anchored to the row. Its open state lives here, not
    // in the trailing glyph: the popup steals the pointer and the row loses
    // hover, so a menu owned by a hover-only control closes as it opens.
    var pickingLine by remember { mutableStateOf(false) }
    val onRowClick: (() -> Unit)? = when {
        inlineDetail -> onDetail
        // Only rows that can actually ring something are pressable; a row
        // whose peer left the production has nothing to redial.
        entry.isRedialable -> ({ pickingLine = true })
        else -> null
    }

    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    when {
                        selected -> colors.rowSelected
                        hovered || pickingLine -> colors.surfaceHover
                        else -> colors.surface
                    },
                )
                .hoverable(interaction)
                .then(if (onRowClick != null) Modifier.clickable(onClick = onRowClick) else Modifier)
                .padding(start = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitAvatar(
                name = title,
                userId = entry.peerUserId.takeIf { entry.mode != CallMode.Group },
                size = ROW_AVATAR,
            )
            Box(Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ROW_HEIGHT)
                        .padding(end = ZillitTheme.spacing.md, top = ROW_PAD_V, bottom = ROW_PAD_V),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs, Alignment.CenterVertically),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    ) {
                        ZillitText(
                            text = title,
                            style = ZillitTheme.typography.bodyLarge,
                            color = colors.textPrimary,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        ZillitText(
                            text = callListStamp(entry.startedAtMillis, nowMillis),
                            style = ZillitTheme.typography.labelSmall,
                            color = colors.textMuted,
                            maxLines = 1,
                        )
                    }
                    // Its own line under the name, never beside it — the rule
                    // the stage tiles follow: squeezed in next to the name the
                    // designation is the first thing truncated, and it is the
                    // half that says WHICH Sam this was.
                    if (designation != null) {
                        ZillitText(
                            text = designation,
                            style = ZillitTheme.typography.labelSmall,
                            color = colors.textSecondary,
                            maxLines = 1,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    ) {
                        HowItWent(entry, Modifier.weight(1f))
                        RowTrailing(entry, hovered || pickingLine, inlineDetail, onDetail) { pickingLine = true }
                    }
                }
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(HAIRLINE)
                        .background(colors.divider),
                )
            }
        }
        // Each line by its number alone — the thread header's reasoning: the
        // media stacks are our vendors, not the user's vocabulary.
        ZillitActionMenu(
            expanded = pickingLine,
            onDismissRequest = { pickingLine = false },
            entries = lines.map { line ->
                ZillitMenuEntry.Action(
                    label = line.label,
                    icon = if (entry.type == CallType.Video) ZillitIcons.Camera else ZillitIcons.Phone,
                    tone = ZillitMenuTone.Approve,
                ) {
                    pickingLine = false
                    onRedial(line)
                }
            },
        )
    }
}

/**
 * The row's second line: the call's kind as a glyph, its direction as a word
 * — a missed call in red, the one row worth finding at a glance — and the
 * line that carried it. The line is its own word, not a subtitle segment:
 * the lines are different call stacks, and "which one rang me" is the first
 * question when one of them misbehaves.
 */
@Composable
private fun HowItWent(entry: CallLogEntry, modifier: Modifier = Modifier) {
    val colors = ZillitTheme.colors
    val tint = if (entry.missed) colors.danger else colors.textSecondary
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitIcon(
            icon = if (entry.type == CallType.Video) ZillitIcons.Camera else ZillitIcons.Phone,
            contentDescription = if (entry.type == CallType.Video) str(S.txt_video_call_label) else null,
            tint = tint,
            size = ROW_ICON,
        )
        ZillitText(
            text = when {
                entry.missed -> str(S.missed)
                entry.direction == CallLogDirection.Outgoing -> str(S.txt_call_outgoing)
                else -> str(S.txt_call_incoming)
            },
            style = ZillitTheme.typography.bodySmall,
            color = tint,
            maxLines = 1,
        )
        ZillitText(text = "·", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        ZillitText(
            text = entry.line.label,
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
            maxLines = 1,
        )
    }
}

/**
 * The second line's end: the call-back glyph beside a side pane, the info
 * glyph without one. Both are always composed and faded in under the
 * cursor — a control that exists only on hover loses the press to the row
 * beneath it (see `HomeFeedScreen`'s kebab) — and each swallows its own
 * press, so it never also picks or rings the row.
 */
@Composable
private fun RowTrailing(
    entry: CallLogEntry,
    hovered: Boolean,
    inlineDetail: Boolean,
    onDetail: () -> Unit,
    onCallBack: () -> Unit,
) {
    val colors = ZillitTheme.colors
    if (inlineDetail) {
        if (entry.isRedialable) {
            ZillitIconButton(
                icon = if (entry.type == CallType.Video) ZillitIcons.Camera else ZillitIcons.Phone,
                contentDescription = str(S.desktop_call_again),
                onClick = onCallBack,
                tint = colors.success,
                size = ROW_ACTION,
                modifier = Modifier.alpha(if (hovered) 1f else 0f),
            )
        }
    } else {
        ZillitIconButton(
            icon = ZillitIcons.Info,
            contentDescription = str(S.desktop_call_details),
            onClick = onDetail,
            tint = colors.textMuted,
            size = ROW_ACTION,
        )
    }
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
                entry.missed -> str(S.missed)
                outgoing -> str(S.txt_call_outgoing)
                else -> str(S.txt_call_incoming)
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

/** The bars' grey — the chat tool's panel colour, so the two tabs match. */
internal val com.zillit.desktop.core.designsystem.ZillitColors.panelGrey: androidx.compose.ui.graphics.Color
    get() = if (isDark) surfaceRaised else canvas

/**
 * The picked row: the light theme's selected tint, and in the dark a faint
 * wash of the accent — the dark selected tint read as a brown bar. The chat
 * listing's rule, so the two tabs agree.
 */
internal val com.zillit.desktop.core.designsystem.ZillitColors.rowSelected: androidx.compose.ui.graphics.Color
    get() = if (isDark) {
        accent.copy(alpha = SELECTED_DARK_ALPHA).compositeOver(surfaceRaised)
    } else {
        surfaceSelected
    }

private const val SELECTED_DARK_ALPHA = 0.1f

/** Android's `delete_call_record` (`res/values/strings.xml:1267`). */
internal val NO_RECORDS: String get() = str(S.delete_call_record)

private val ROW_CORNER = 10.dp
private val DIRECTION_DISC = 18.dp
private val DIRECTION_GLYPH = 11.dp
private val ROW_AVATAR = 48.dp
private val ROW_HEIGHT = 72.dp
private val ROW_PAD_V = 10.dp
private val ROW_ICON = 14.dp
private val ROW_ACTION = 24.dp
private val HAIRLINE = 1.dp
private val PILL_PAD_V = 6.dp
private val HEADER_ICON = 28.dp
private val CONFIRM_WIDTH = 380.dp
